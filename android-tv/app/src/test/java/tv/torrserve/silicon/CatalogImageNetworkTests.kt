package tv.torrserve.silicon

import coil3.network.NetworkRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.*
import java.util.concurrent.TimeUnit

class CatalogImageNetworkTests {
    private val image = "https://image.tmdb.org/t/p/w500/poster.jpg"
    private fun client(server: MockWebServer) = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath))
            .header("X-Test-Host", chain.request().url.host).build())
    }.build()
    private fun jpeg() = MockResponse().setHeader("Content-Type", "image/jpeg").setBody("jpeg fixture")
    @Test fun bodyTimeoutAdvancesMirrorAndSuccessfulHostIsRememberedWithoutForwardingSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jpeg().setBodyDelay(500, TimeUnit.MILLISECONDS))
            server.enqueue(jpeg()); server.enqueue(jpeg()); server.start()
            var remembered = ""
            val diagnostics = NetworkDiagnostics()
            val routes = CatalogImageRoutes(savePreferred = { remembered = it })
            val network = CatalogImageNetwork(client(server), { CatalogNetworkSettings() }, routes, diagnostics, attemptMillis = 150)
            val headers = coil3.network.NetworkHeaders.Builder().set("Cookie", "private").set("token", "private").build()
            suspend fun fetch() = network.executeRequest(NetworkRequest(image, headers = headers)) {
                val buffer = Buffer(); it.body!!.writeTo(buffer); buffer.readUtf8()
            }
            assertEquals("jpeg fixture", fetch())
            assertEquals("image.tmdb.org", server.takeRequest().getHeader("X-Test-Host"))
            val mirror = server.takeRequest()
            assertEquals("imagetmdb.com", mirror.getHeader("X-Test-Host"))
            assertNull(mirror.getHeader("Cookie")); assertNull(mirror.getHeader("token"))
            assertEquals("imagetmdb.com", remembered); assertTrue(diagnostics.issues.value.isEmpty())
            assertEquals("jpeg fixture", fetch())
            assertEquals("imagetmdb.com", server.takeRequest().getHeader("X-Test-Host"))
        }
    }
    @Test fun htmlSuccessIsRejectedAndCacheConsumerErrorsDoNotRetryNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("login").setHeader("Content-Type", "text/html"))
            server.enqueue(jpeg()); server.start()
            val network = CatalogImageNetwork(client(server), { CatalogNetworkSettings() })
            try {
                network.executeRequest(NetworkRequest(image)) { throw java.io.IOException("disk error") }
            } catch (e: java.io.IOException) { assertEquals("disk error", e.message) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun cancellationStopsCurrentSocketAndDoesNotAdvanceAnotherMirror() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val diagnostics = NetworkDiagnostics()
            val network = CatalogImageNetwork(client(server), { CatalogNetworkSettings() }, diagnostics = diagnostics)
            val task = launch(Dispatchers.IO) { network.executeRequest(NetworkRequest(image)) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            task.cancelAndJoin()
            assertEquals(1, server.requestCount); assertTrue(diagnostics.issues.value.isEmpty())
        }
    }
    @Test fun routeHealthHasBoundedCooldownAndMissingPreferredHostsAreIgnored() {
        var now = 0L
        val routes = CatalogImageRoutes(initialPreferred = "de.imagetmdb.com", now = { now })
        val config = CatalogNetworkSettings()
        val url = image.toHttpUrl()
        assertEquals("de.imagetmdb.com", routes.candidates(url, config).first().host)
        routes.failed("de.imagetmdb.com")
        assertFalse(routes.candidates(url, config).any { it.host == "de.imagetmdb.com" })
        now = 60_000
        assertEquals("de.imagetmdb.com", routes.candidates(url, config).first().host)
        assertFalse(routes.candidates(url, config.copy(route = CatalogRoute.Proxy)).any { it.host == "image.tmdb.org" })
    }
}
