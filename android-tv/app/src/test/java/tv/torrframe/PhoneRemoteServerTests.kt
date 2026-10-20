package tv.torrframe

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.phoneRemote.server.*
import tv.torrframe.phoneRemote.security.*
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

private class TestBackend : RemoteBackend {
    override val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val epoch = "test"; override val sequence = 0L
    var writes = 0
    override suspend fun snapshot(session: RemoteSession) = JSONObject().put("csrf", session.csrf).put("actual", true)
    override suspend fun command(session: RemoteSession, request: JSONObject): JSONObject { writes++; return JSONObject().put("operationId", "test") }
    override suspend fun operation(session: RemoteSession, id: String): JSONObject? = null
    override suspend fun close() {}
}
class PhoneRemoteServerTests {
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun port() = ServerSocket(0).use { it.localPort }
    private suspend fun withServer(block: suspend (PhoneRemoteServer, SessionManager, PairingManager, TestBackend, Int) -> Unit) {
        val port = port(); val sessions = SessionManager(MemorySessions()); val pairing = PairingManager(); val backend = TestBackend()
        val assets = object : WebAssets { override suspend fun read(path: String) = if (path == "index.html") "<html>Phone Remote</html>".toByteArray() else null }
        val server = PhoneRemoteServer(RequestGuard("127.0.0.1", port), sessions, pairing, backend, assets)
        try { server.start(); block(server, sessions, pairing, backend, port) } finally { server.stop() }
    }
    private fun request(port: Int, path: String, cookie: String? = null, body: String? = null, origin: String? = null, csrf: String? = null): Response {
        val request = Request.Builder().url("http://127.0.0.1:$port$path")
        cookie?.let { request.header("Cookie", "$PHONE_COOKIE=$it") }; origin?.let { request.header("Origin", it) }; csrf?.let { request.header("X-Phone-CSRF", it) }
        if (body != null) request.post(body.toRequestBody("application/json".toMediaType())).header("X-Phone-Remote", "1")
        return client.newCall(request.build()).execute()
    }
    @Test fun unauthorizedWritesAndForeignOriginsAreBlocked() = runBlocking {
        withServer { _, sessions, _, backend, port ->
            request(port, "/").use {
                assertEquals(200, it.code)
                assertTrue(it.header("Content-Security-Policy")!!.contains("connect-src 'self' ws://127.0.0.1:$port;"))
            }
            request(port, "/api/state").use { assertEquals(401, it.code) }
            request(port, "/api/commands", body = "{}", origin = "http://127.0.0.1:$port").use { assertEquals(401, it.code) }
            val grant = sessions.create("test")
            request(port, "/api/commands", grant.cookie, "{}", "http://evil.test", grant.session.csrf).use { assertEquals(403, it.code) }
            request(port, "/api/commands", grant.cookie, "{}", "http://127.0.0.1:$port").use { assertEquals(403, it.code) }
            request(port, "/api/commands", grant.cookie, "{}", "http://127.0.0.1:$port", grant.session.csrf).use { assertEquals(202, it.code) }
            assertEquals(1, backend.writes)
            val rebinding = Request.Builder().url("http://127.0.0.1:$port/api/state").header("Host", "evil.test:$port").header("Cookie", "$PHONE_COOKIE=${grant.cookie}").build()
            client.newCall(rebinding).execute().use { assertEquals(403, it.code) }
        }
    }
    @Test fun realPairingSetsHttpOnlyCookieAndCannotBeReplayed() = runBlocking {
        withServer { _, _, pairing, _, port ->
            val origin = "http://127.0.0.1:$port"
            val token = pairing.issue(origin).url.substringAfter("#pair=")
            val json = JSONObject().put("token", token).put("device", "test browser").toString()
            var cookie = ""
            request(port, "/pair", body = json, origin = origin).use {
                assertEquals(200, it.code); val header = it.header("Set-Cookie")!!
                assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict")); cookie = header.substringBefore(';').substringAfter('=')
            }
            request(port, "/pair", body = json, origin = origin).use { assertEquals(401, it.code) }
            request(port, "/api/state", cookie).use { assertEquals(200, it.code); assertTrue(JSONObject(it.body.string()).getBoolean("actual")) }
        }
    }
    @Test fun numericCodeCreatesSameAuthenticatedSessionAndRejectsReuse() = runBlocking {
        withServer { _, _, pairing, _, port ->
            val origin = "http://127.0.0.1:$port"
            val challenge = pairing.issue(origin)
            val body = JSONObject().put("code", challenge.code).put("device", "No camera browser").toString()
            var cookie = ""
            request(port, "/pair", body = body, origin = "http://evil.test").use { assertEquals(403, it.code) }
            request(port, "/pair", body = body, origin = origin).use {
                assertEquals(200, it.code)
                val header = it.header("Set-Cookie")!!
                assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict"))
                cookie = header.substringBefore(';').substringAfter('=')
            }
            request(port, "/api/state", cookie).use { assertEquals(200, it.code) }
            request(port, "/pair", body = body, origin = origin).use { assertEquals(401, it.code) }
            val qr = JSONObject().put("token", challenge.url.substringAfter("#pair=")).toString()
            request(port, "/pair", body = qr, origin = origin).use { assertEquals(401, it.code) }
        }
    }
    @Test fun stoppingFreesPortAndAllowsRestart() = runBlocking {
        withServer { server, _, _, _, port ->
            server.stop(); ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
            server.start(); request(port, "/").use { assertEquals(200, it.code) }
        }
    }
    @Test fun occupiedPortIsAnIsolatedLaunchFailure() = runBlocking {
        ServerSocket().use { occupied ->
            // macOS permits a specific-address bind alongside a wildcard listener.
            // Occupy the same address the remote actually uses on every platform.
            occupied.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            val server = PhoneRemoteServer(RequestGuard("127.0.0.1", occupied.localPort), SessionManager(MemorySessions()), PairingManager(), TestBackend(), object : WebAssets { override suspend fun read(path: String): ByteArray? = null })
            try { withTimeout(10_000) { server.start() }; fail("Occupied port should fail") } catch (_: Exception) {} finally { server.stop() }
        }
    }
    @Test fun websocketRequiresAuthenticationAndRevocationClosesConnection() = runBlocking {
        withServer { _, sessions, _, _, port ->
            val denied = CompletableDeferred<Int>()
            val deniedSocket = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/api/events").header("Origin", "http://127.0.0.1:$port").header("Sec-WebSocket-Protocol", "phone.v1").build(), object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { denied.complete(response?.code ?: -1) }
            })
            assertEquals(401, withTimeout(5000) { denied.await() }); deniedSocket.cancel()
            val grant = sessions.create("test")
            val connected = CompletableDeferred<Unit>(); val revoked = CompletableDeferred<Int>()
            val socket = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/api/events").header("Origin", "http://127.0.0.1:$port")
                .header("Cookie", "$PHONE_COOKIE=${grant.cookie}").header("Sec-WebSocket-Protocol", "phone.v1, ${grant.session.csrf}").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { connected.complete(Unit) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { revoked.complete(code); webSocket.close(code, reason) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected.completeExceptionally(t); revoked.complete(-1) }
            })
            try { withTimeout(5000) { connected.await() }; sessions.revoke(grant.session.id); assertEquals(1008, withTimeout(5000) { revoked.await() }) } finally { socket.cancel() }
        }
    }
}
