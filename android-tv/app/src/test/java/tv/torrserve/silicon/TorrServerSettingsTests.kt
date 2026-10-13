package tv.torrserve.silicon

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.Settings
import java.util.concurrent.TimeUnit

class TorrServerSettingsTests {
    @Test fun validatesAndNormalizesAddresses() {
        assertEquals("http://localhost:8090", ServerAddress.normalize(" http://localhost:8090/ "))
        assertEquals("https://example.com/proxy/settings", ServerAddress.endpoint("https://example.com/proxy/", "settings").toString())
        assertTrue(ServerAddress.isLocal("http://127.0.0.1:8090", 8090))
        assertFalse(ServerAddress.isLocal("https://localhost:8090", 8090))
        assertFalse(ServerAddress.isLocal("http://localhost:8090/proxy", 8090))
        for (invalid in listOf("", "localhost:8090", "ftp://host", "http://", "http://host:99999",
            "https://user:pass@host", "http://host/?token=key", "http://host/#fragment", "http://ho st")) {
            assertThrows(invalid, IllegalArgumentException::class.java) { ServerAddress.parse(invalid) }
        }
    }
    @Test fun preservesUneditedSettingsAndConvertsUnits() {
        val original = JSONObject("""{"CacheSize":67108864,"PreloadCache":50,"EnableDLNA":true,"Unknown":{"flag":7},"DisableTCP":false,"DisableUTP":false}""")
        val updated = EngineSettings.merge(original, mapOf("CacheSize" to "128", "PreloadCache" to "75"))
        assertEquals(134217728L, updated.getLong("CacheSize"))
        assertEquals(75, updated.getInt("PreloadCache"))
        assertTrue(updated.getBoolean("EnableDLNA"))
        assertEquals(7, updated.getJSONObject("Unknown").getInt("flag"))
        assertEquals(67108864L, original.getLong("CacheSize"))
        EngineSettings.verify(updated, JSONObject(updated.toString()), setOf("CacheSize", "PreloadCache"))
        assertThrows(IllegalStateException::class.java) { EngineSettings.verify(updated, original, setOf("CacheSize")) }
    }
    @Test fun rejectsUnsafeNumbersAndUnsupportedFields() {
        val current = JSONObject("""{"ReaderReadAHead":95,"DisableTCP":false,"DisableUTP":false}""")
        assertThrows(IllegalArgumentException::class.java) { EngineSettings.merge(current, mapOf("ReaderReadAHead" to "101")) }
        assertThrows(ArithmeticException::class.java) { EngineSettings.merge(current, mapOf("ReaderReadAHead" to "5.5")) }
        assertThrows(IllegalStateException::class.java) { EngineSettings.merge(current, mapOf("ReaderReadAHead" to "NaN")) }
        assertThrows(IllegalStateException::class.java) { EngineSettings.merge(current, mapOf("CacheSize" to "64")) }
        assertThrows(IllegalArgumentException::class.java) { EngineSettings.merge(current, mapOf("DisableTCP" to "true", "DisableUTP" to "true")) }
    }
    @Test fun actualSettingsProtocolAndReverseProxyPath() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"CacheSize":67108864}"""))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            val api = TorrServerApi(Http()) { server.url("/torrserver/").toString() }
            val settings = api.settings()
            api.setSettings(settings); api.resetSettings()
            val get = server.takeRequest()
            assertEquals("/torrserver/settings", get.path)
            assertEquals("get", JSONObject(get.body.readUtf8()).getString("action"))
            val set = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("set", set.getString("action")); assertEquals(67108864L, set.getJSONObject("sets").getLong("CacheSize"))
            assertEquals("def", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        }
    }
    @Test fun diagnosisMeasuresActualTransfer() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("MatriX.146.1").setBodyDelay(30, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(8 * 1024 * 1024))))
            val address = server.url("/").toString()
            val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            val result = connection.diagnose(address)
            assertEquals("MatriX.146.1", result.version); assertTrue(result.latencyMs >= 20)
            assertEquals(8 * 1024 * 1024, result.bytes); assertTrue(result.megabitsPerSecond!! > 0)
            assertEquals("/echo", server.takeRequest().path)
            val download = server.takeRequest()
            assertEquals("/download/8", download.path); assertEquals("identity", download.getHeader("Accept-Encoding"))
        }
    }
    @Test fun missingDownloadDoesNotInventSpeed() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("MatriX.146.1")); server.enqueue(MockResponse().setResponseCode(404))
            val address = server.url("/").toString()
            val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            val result = connection.diagnose(address)
            assertNull(result.megabitsPerSecond); assertNull(result.bytes); assertEquals(404, result.bandwidthFailure)
            assertTrue(result.latencyMs > 0)
        }
    }
    @Test fun rejectsWrongServerAndDistinguishesAccessErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("<html>proxy login</html>")); server.enqueue(MockResponse().setResponseCode(403))
            val address = server.url("/").toString()
            val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            assertEquals(ConnectionStatus.Error, connection.check().status)
            assertEquals(403, connection.check().failure)
        }
    }
    @Test fun timeoutIsBoundedAndReported() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("MatriX.146.1").setBodyDelay(1, TimeUnit.SECONDS))
            val address = server.url("/").toString()
            val http = Http(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
            val connection = ServerConnection(TorrServerApi(http) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            val result = connection.check()
            assertEquals(ConnectionStatus.Unavailable, result.status); assertEquals(408, result.failure)
        }
    }
    @Test fun truncatedPayloadDoesNotBecomeBandwidthResult() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("MatriX.146.1")); server.enqueue(MockResponse().setBody("short payload"))
            val address = server.url("/").toString()
            val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            val result = connection.diagnose(address)
            assertNull(result.megabitsPerSecond); assertEquals(-2, result.bandwidthFailure)
        }
    }
    @Test fun staleProbeCannotOverwriteNewServerStatus() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setBody("MatriX.145").setBodyDelay(200, TimeUnit.MILLISECONDS))
            second.enqueue(MockResponse().setBody("MatriX.146.1"))
            val settings = MutableStateFlow(Settings(torrServerUrl = first.url("/").toString()))
            val connection = ServerConnection(TorrServerApi(Http()) { settings.value.torrServerUrl }, settings, this)
            val oldProbe = async(start = CoroutineStart.UNDISPATCHED) { connection.check() }
            assertNotNull(first.takeRequest(2, TimeUnit.SECONDS))
            settings.value = settings.value.copy(torrServerUrl = second.url("/").toString())
            connection.check(); oldProbe.await()
            assertEquals("MatriX.146.1", connection.state.value.version)
        } }
    }
    @Test fun olderProbeForSameAddressCannotOverwriteNewerProbe() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("MatriX.145").setBodyDelay(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("MatriX.146.1"))
            val address = server.url("/").toString()
            val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
            val older = async(start = CoroutineStart.UNDISPATCHED) { connection.check() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            connection.check(); older.await()
            assertEquals("MatriX.146.1", connection.state.value.version)
        }
    }
    @Test fun unavailableServerIsReportedWithoutLeakingAddress() = runBlocking {
        val server = MockWebServer(); server.start()
        val address = server.url("/").toString(); server.shutdown()
        val connection = ServerConnection(TorrServerApi(Http()) { address }, MutableStateFlow(Settings(torrServerUrl = address)), this)
        val result = connection.check()
        assertEquals(ConnectionStatus.Unavailable, result.status); assertEquals(0, result.failure)
    }
    @Test fun switchingServerChangesTorrentAndStreamTargets() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start(); first.enqueue(MockResponse().setBody("MatriX.146.1")); second.enqueue(MockResponse().setBody("MatriX.146.1"))
            var address = first.url("/first/").toString()
            val api = TorrServerApi(Http()) { address }
            api.version(); address = second.url("/second/").toString(); api.version()
            assertEquals("/first/echo", first.takeRequest().path); assertEquals("/second/echo", second.takeRequest().path)
            assertTrue(api.stream("a".repeat(40), tv.torrserve.silicon.core.models.TorrentFile(1,"film.mkv",1)).startsWith(second.url("/second/stream/").toString()))
        } }
    }
}
