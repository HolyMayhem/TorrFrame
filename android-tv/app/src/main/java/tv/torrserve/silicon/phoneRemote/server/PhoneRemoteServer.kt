package tv.torrserve.silicon.phoneRemote.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.io.readByteArray
import org.json.JSONObject
import tv.torrserve.silicon.core.storage.SettingsConflict
import tv.torrserve.silicon.phoneRemote.security.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val SessionKey = AttributeKey<RemoteSession>("phone-session")
const val PHONE_COOKIE = "ts_phone_session"
class PhoneRemoteServer(val guard: RequestGuard, private val sessions: SessionManager,
    private val pairing: PairingManager, private val backend: RemoteBackend, private val assets: WebAssets) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val calls = kotlinx.coroutines.sync.Semaphore(16)
    private val sockets = AtomicInteger()
    private val rateLimit = PairingRateLimit()
    suspend fun start() = withContext(Dispatchers.IO) {
        check(server == null)
        val created = embeddedServer(CIO, configure = { connectionIdleTimeoutSeconds = 15; connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 2; connector { host = guard.address; port = guard.port } }) { routes() }
        server = created
        try { created.start(wait = false); withTimeout(8_000) { created.engine.resolvedConnectors() }; Unit }
        catch (e: Exception) { created.stop(0, 1000); server = null; throw e }
    }
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        val old = server; server = null
        try { old?.stop(100, 1000) } finally { backend.close() }
    }
    private suspend fun ApplicationCall.json(status: HttpStatusCode, value: JSONObject) = respondText(value.toString(), ContentType.Application.Json, status)
    private suspend fun ApplicationCall.failure(status: HttpStatusCode, code: String) = json(status, JSONObject().put("error", code))
    private suspend fun ApplicationCall.body(): JSONObject {
        require(request.headers[HttpHeaders.ContentType]?.substringBefore(';') == "application/json")
        val announced = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(announced == null || announced in 0..16_384)
        val packet = withTimeout(5000) { receiveChannel().readBuffer(16_385L) }
        val bytes = packet.readByteArray()
        require(bytes.size <= 16_384)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun Application.routes() {
        install(WebSockets) { pingPeriod = 15.seconds; timeout = 30.seconds; maxFrameSize = 1024; masking = false }
        intercept(ApplicationCallPipeline.Setup) {
            if (!calls.tryAcquire()) { call.failure(HttpStatusCode.ServiceUnavailable, "busy"); finish(); return@intercept }
            try { proceed() } finally { calls.release() }
        }
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Safari does not consistently include ws: in connect-src 'self'.
            call.response.headers.append("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: http: data:; connect-src 'self' ws://${guard.authority}; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            val path = call.request.path()
            if (!path.matches(Regex("/[A-Za-z0-9_./-]*")) || path.contains("//") || path.contains("..")) {
                call.failure(HttpStatusCode.BadRequest, "invalid_path"); finish(); return@intercept
            }
            val websocket = path == "/api/events"
            val write = call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head
            if (!guard.peer(call.request.local.remoteAddress) || !guard.host(call.request.headers[HttpHeaders.Host]) || !guard.origin(call.request.headers[HttpHeaders.Origin], write || websocket)) {
                call.failure(HttpStatusCode.Forbidden, "origin_rejected"); finish(); return@intercept
            }
            if (path.startsWith("/api/")) {
                val session = sessions.authenticate(call.request.cookies[PHONE_COOKIE])
                if (session == null) { call.failure(HttpStatusCode.Unauthorized, "pair_required"); finish(); return@intercept }
                val csrf = if (websocket) call.request.headers[HttpHeaders.SecWebSocketProtocol]?.split(',')?.map { it.trim() }?.getOrNull(1)
                    else call.request.headers["X-Phone-CSRF"]
                if ((write || websocket) && !sessions.csrf(session, csrf)) {
                    call.failure(HttpStatusCode.Forbidden, "csrf_rejected"); finish(); return@intercept
                }
                call.attributes.put(SessionKey, session)
            }
        }
        routing {
            post("/pair") {
                if (!rateLimit.allow(call.request.local.remoteAddress)) { call.failure(HttpStatusCode.TooManyRequests, "pair_rate_limit"); return@post }
                try {
                    val request = call.body()
                    if (call.request.headers["X-Phone-Remote"] != "1" || !pairing.consume(request.getString("token"), guard.origin)) {
                        call.failure(HttpStatusCode.Unauthorized, "pair_expired"); return@post
                    }
                    val grant = sessions.create(request.optString("device", "Телефон"))
                    call.response.headers.append(HttpHeaders.SetCookie, "$PHONE_COOKIE=${grant.cookie}; Path=/api; HttpOnly; SameSite=Strict; Max-Age=604800")
                    call.json(HttpStatusCode.OK, JSONObject().put("paired", true))
                } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; call.failure(HttpStatusCode.BadRequest, "pair_failed") }
            }
            get("/api/state") {
                try { call.json(HttpStatusCode.OK, backend.snapshot(call.attributes[SessionKey])) }
                catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; call.failure(HttpStatusCode.ServiceUnavailable, "state_unavailable") }
            }
            post("/api/commands") {
                try { call.json(HttpStatusCode.Accepted, backend.command(call.attributes[SessionKey], call.body())) }
                catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    call.failure(if (e is SettingsConflict) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, "command_rejected")
                }
            }
            get("/api/operations/{id}") {
                val result = backend.operation(call.attributes[SessionKey], call.parameters["id"].orEmpty())
                if (result == null) call.failure(HttpStatusCode.NotFound, "operation_missing") else call.json(HttpStatusCode.OK, result)
            }
            post("/api/logout") {
                sessions.revoke(call.attributes[SessionKey].id)
                call.response.headers.append(HttpHeaders.SetCookie, "$PHONE_COOKIE=; Path=/api; HttpOnly; SameSite=Strict; Max-Age=0")
                call.json(HttpStatusCode.OK, JSONObject().put("loggedOut", true))
            }
            webSocket("/api/events", protocol = "phone.v1") {
                val session = call.attributes[SessionKey]
                if (sockets.incrementAndGet() > 8) { sockets.decrementAndGet(); close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "limit")); return@webSocket }
                val events = launch(start = CoroutineStart.UNDISPATCHED) { backend.events.collect { send(Frame.Text(it)) } }
                val revoked = launch { sessions.sessions.collect { list ->
                    if (list.none { it.id == session.id }) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                } }
                val expiry = launch { delay((session.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "expired")) }
                try {
                    send(Frame.Text(JSONObject().put("v", 1).put("type", "sync.ready").put("epoch", backend.epoch).put("sequence", backend.sequence).toString()))
                    for (frame in incoming) if (frame is Frame.Text || frame is Frame.Binary) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "commands_use_http")); break
                    }
                } finally { events.cancel(); revoked.cancel(); expiry.cancel(); sockets.decrementAndGet() }
            }
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifBlank { "index.html" }
                val bytes = assets.read(path)
                if (bytes == null) call.failure(HttpStatusCode.NotFound, "not_found")
                else call.respondBytes(bytes, ContentType.fromFilePath(path).firstOrNull() ?: ContentType.Application.OctetStream)
            }
        }
    }
}
