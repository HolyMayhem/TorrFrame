package tv.torrserve.silicon

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.Http
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.search.*
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*

class JackettSearchTests {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private fun xml(link: String, seeds: Int = 12) = """<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><item>
        <title>Film.2024.1080p.WEB-DL</title><guid>one</guid><enclosure url="${link.replace("&", "&amp;")}" length="1073741824"/>
        <torznab:attr name="seeders" value="$seeds"/><torznab:attr name="peers" value="3"/>
        <torznab:attr name="category" value="2000"/></item></channel></rss>"""
    @Test fun torznabResultsUseSharedDeduplicationFiltersAndSeedOrdering() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody(xml(magnet))) }
            val provider = JackettSearchProvider(JackettClient(Http())) {
                Settings(jackettUrl=server.url("/").toString(), jackettKey="fixture-key", jackettEnabled=true)
            }
            val result = TorrentSearchEngine(listOf(provider)).search(SearchTarget("Фильм", "Film", 2024),
                TorrentSearchOptions(minSeeds=10, resolutions=setOf("1080p"))).last()
            assertEquals(1, result.results.size); assertEquals(SourcePhase.Complete, result.sources.single().phase)
            assertEquals("a".repeat(40), result.results.single().infoHash)
            assertEquals(1073741824L, result.results.single().sizeBytes)
            val queries = (1..2).map {
                val url = server.takeRequest().requestUrl!!
                assertEquals("2000",url.queryParameter("cat"));assertEquals("fixture-key",url.queryParameter("apikey"))
                url.queryParameter("q")
            }.toSet()
            assertEquals(setOf("Film 2024", "Фильм 2024"), queries)
        }
    }
    @Test fun disabledJackettMakesNoNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider=JackettSearchProvider(JackettClient(Http())) { Settings(jackettUrl=server.url("/").toString(),jackettKey="key",jackettEnabled=false) }
            val result=TorrentSearchEngine(listOf(provider)).search(SearchTarget("Film")).last()
            assertEquals(SourcePhase.Failed,result.sources.single().phase);assertEquals(0,server.requestCount)
        }
    }
    @Test fun torrentDownloadAndMagnetRedirectKeepTheOriginalPrivateLink() = runBlocking {
        MockWebServer().use { server ->
            server.start();val client=JackettClient(Http())
            val url=server.url("/download?apikey=fixture-key").toString()
            server.enqueue(MockResponse().setBody(xml(url)))
            val provider=JackettSearchProvider(client) { Settings(jackettUrl=server.url("/").toString(),jackettKey="fixture-key",jackettEnabled=true) }
            val row=provider.search("Film",MediaType.Movie).single();assertNull(row.magnet);assertEquals(url,row.torrentUrl)
            val bytes="d4:infod4:name8:test.mkvee".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertArrayEquals(bytes,(client.download(row.jackettResult()) as TorrentPayload.File).value)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",magnet))
            assertEquals(magnet,(client.download(row.jackettResult()) as TorrentPayload.Magnet).value)
        }
    }
}
