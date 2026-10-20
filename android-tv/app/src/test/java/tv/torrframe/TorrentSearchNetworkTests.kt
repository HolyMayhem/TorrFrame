package tv.torrframe

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.core.network.Http
import tv.torrframe.features.search.TorrentPayload
import tv.torrframe.features.torrentsearch.data.TorrentSearchNetwork

class TorrentSearchNetworkTests {
    @Test fun resolvesRelativeDownloadRedirectAndPreservesTorrentBytes() = runBlocking {
        MockWebServer().use { server ->
            val bytes="d4:infod4:name4:teste e".toByteArray()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/download"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            server.start()
            val payload=TorrentSearchNetwork(Http()).download(server.url("/page").toString()) as TorrentPayload.File
            assertArrayEquals(bytes,payload.value)
            assertEquals("/page",server.takeRequest().path);assertEquals("/download",server.takeRequest().path)
        }
    }
    @Test fun acceptsMagnetRedirectWithoutRequestingMagnetProtocol() = runBlocking {
        MockWebServer().use { server ->
            val magnet="magnet:?xt=urn:btih:"+"a".repeat(40)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",magnet));server.start()
            assertEquals(magnet,(TorrentSearchNetwork(Http()).download(server.url("/").toString()) as TorrentPayload.Magnet).value)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun rejectsHtmlAndRedirectLoops() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>Cloudflare challenge</html>"))
            repeat(6){server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/loop"))};server.start()
            val network=TorrentSearchNetwork(Http())
            try{network.download(server.url("/").toString());fail("HTML accepted")}catch(_:IllegalArgumentException){}
            try{network.download(server.url("/loop").toString());fail("Loop accepted")}catch(_:IllegalStateException){}
            assertEquals(7,server.requestCount)
        }
    }
}
