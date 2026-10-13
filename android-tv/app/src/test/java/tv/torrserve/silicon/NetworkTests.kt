package tv.torrserve.silicon

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.server.TorrServerApi
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.search.JackettClient

class NetworkTests {
    @Test fun preservesReverseProxyPathAndEncodesQuery() {
        val u = JackettClient(Http()).endpoint(Settings(jackettUrl="https://example.com/jackett/", jackettKey="a&b"), "search", "Film & Series")
        assertEquals("/jackett/api/v2.0/indexers/all/results/torznab/api", u.encodedPath)
        assertEquals("a&b",u.queryParameter("apikey")); assertEquals("Film & Series",u.queryParameter("q"))
    }
    @Test fun invalidAddressCannotInjectCredentials() {
        assertThrows(IllegalArgumentException::class.java) { JackettClient(Http()).endpoint(Settings(jackettUrl="https://user:secret@example.com",jackettKey="k"),"caps") }
    }
    @Test fun streamPreservesFileIndexAndEncoding() {
        val url = TorrServerApi(Http()) { "http://localhost:8090" }.stream("0".repeat(40),TorrentFile(7,"folder/фильм & title.mkv",10))
        val parsed = okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }
        assertEquals("7",parsed.queryParameter("index")); assertEquals("фильм & title.mkv",parsed.pathSegments.last()); assertTrue(parsed.queryParameterNames.contains("play"))
    }
    @Test fun httpErrorsRedactServerBody() = runBlocking {
        MockWebServer().use { server -> server.enqueue(MockResponse().setResponseCode(401).setBody("apikey=secret")); server.start()
            try { Http().get(server.url("/"),"Jackett"); fail() } catch(e: ServiceFailure) { assertEquals(401,e.status); assertFalse(e.message.orEmpty().contains("secret")) }
        }
    }
    @Test fun responseLimitIsEnforced() = runBlocking {
        MockWebServer().use { server -> server.enqueue(MockResponse().setBody("123456789")); server.start()
            try { Http().bytes(okhttp3.Request.Builder().url(server.url("/")).build(),"Jackett",4); fail() }
            catch(e: ServiceFailure) { assertEquals(-1,e.status) }
        }
    }
    @Test fun cancelsSlowRequests() = runBlocking {
        MockWebServer().use { server -> server.enqueue(MockResponse().setBody("data").setBodyDelay(5,java.util.concurrent.TimeUnit.SECONDS)); server.start()
            val request = async { Http().get(server.url("/"),"Jackett") }
            delay(100); request.cancelAndJoin(); assertTrue(request.isCancelled)
        }
    }
}
