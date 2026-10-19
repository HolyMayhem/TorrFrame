package tv.torrserve.torrentsearch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.*

class ProviderTests {
    private val hash="0123456789abcdef0123456789abcdef01234567"
    private val magnet="magnet:?xt=urn:btih:$hash"
    private class Network(var body:String="") : SearchTransport {
        var requested="";var payload="";var headers=emptyMap<String,String>()
        override suspend fun get(url:String,headers:Map<String,String>):ByteArray { requested=url;this.headers=headers;return body.toByteArray() }
        override suspend fun post(url:String,body:String,contentType:String):ByteArray { requested=url;payload=body;return this.body.toByteArray() }
    }
    @Test fun csvMapsKnownAndMissingCountsAndSize() {
        val json="""{"torrents":[{"id":1,"name":"Film 1080p","infohash":"$hash","size_bytes":1234,"seeders":12,"leechers":3},{"id":2,"name":"Unknown","infohash":"$hash"}]}"""
        val rows=TorrentsCsvProvider(Network()).parse(json.toByteArray())
        assertEquals(12L,rows[0].seeds);assertEquals(3L,rows[0].peers);assertEquals(1234L,rows[0].sizeBytes)
        assertNull(rows[1].seeds);assertEquals(hash,rows[0].infoHash)
    }
    @Test fun pirateBayHandlesSentinelAndVideoQueryEncoding()=runBlocking {
        val net=Network("""[{"name":"No results returned"},{"id":"1","name":"Film","info_hash":"$hash","seeders":"0","leechers":"7","size":"4096"}]""")
        val rows=PirateBayProvider(net).search("Film & Name 2024",MediaType.Movie)
        assertEquals(1,rows.size);assertEquals(0L,rows.single().seeds)
        assertTrue(net.requested.contains("Film%20%26%20Name%202024"));assertTrue(net.requested.endsWith("cat=200"))
    }
    @Test fun knabenUsesUpstreamCategoryAndSeedsSortAndNullableCounts()=runBlocking {
        val net=Network("""{"hits":[{"title":"Show","magnetUrl":"$magnet","seeders":-1,"peers":10,"bytes":2000}]}""")
        val rows=KnabenProvider(net).search("Show 2020",MediaType.Series)
        val j=org.json.JSONObject(net.payload)
        assertEquals("seeders",j.getString("order_by"));assertEquals(2000000,j.getJSONArray("categories").getInt(0))
        assertNull(rows.single().seeds);assertEquals(10L,rows.single().peers)
    }
    @Test fun ytsExpandsQualityVariantsAndAcceptsEmptyMovies() {
        val json="""{"status":"ok","data":{"movies":[{"id":1,"title_long":"Film (2024)","torrents":[{"hash":"$hash","quality":"1080p","type":"bluray","video_codec":"x264","seeds":50,"peers":2,"size_bytes":1024}]}]}}"""
        val row=YtsProvider(Network()).parse(json.toByteArray()).single()
        assertEquals("1080p · BluRay",row.quality);assertEquals(50L,row.seeds)
        assertTrue(YtsProvider(Network()).parse("""{"data":{"movie_count":0}}""".toByteArray()).isEmpty())
    }
    @Test fun rutorUsesItsRealSelectorsAndKeepsUnknownPeersUnknown() {
        val data="""<div id="index"><table><tr><th>Header</th></tr><tr><td>date</td><td><a href="/file.torrent">file</a><a href="$magnet">magnet</a><a href="/torrent/1">Фильм 1080p WEB-DL</a></td><td>2.1 GB</td><td><span>55</span><span> / </span><span>?</span></td></tr></table></div>"""
        val row=RutorProvider(Network()).parse(data.toByteArray(),"https://rutor.info/search/test").single()
        assertEquals("Фильм 1080p WEB-DL",row.title);assertEquals(55L,row.seeds);assertNull(row.peers)
        assertEquals("https://rutor.info/file.torrent",row.torrentUrl)
    }
    @Test fun nyaaDropsCommentLinkAndReadsStats() {
        val data="""<table class="torrent-list"><tbody><tr><td>Anime</td><td><a class="comments">comments</a><a href="/view/1">Anime 2160p</a></td><td><a href="/download/1.torrent">file</a><a href="$magnet">magnet</a></td><td>1 GiB</td><td>date</td><td>20</td><td>4</td></tr></tbody></table>"""
        val row=NyaaProvider(Network()).parse(data.toByteArray(),"https://nyaa.si/").single()
        assertEquals("Anime 2160p",row.title);assertEquals(20L,row.seeds);assertEquals(4L,row.peers)
    }
    @Test fun eztvRequiresMagnetLayoutCookieAndDoesNotInventPeerCount()=runBlocking {
        val data="""<table><tr><td>h1</td></tr><tr><td>h2</td></tr><tr><td>date</td><td><a class="epinfo" href="/ep/1">Show 1080p</a></td><td><a class="magnet" href="$magnet">magnet</a><a href="/file.torrent">file</a></td><td>1 GB</td><td>other</td><td>7</td></tr></table>"""
        val net=Network(data);val row=EztvProvider(net).search("Show",MediaType.Series).single()
        assertEquals("layout=def_wlinks",net.headers["Cookie"]);assertEquals(7L,row.seeds);assertNull(row.peers)
    }
    @Test fun x1337ResolvesMagnetOnlyAfterSelection()=runBlocking {
        val net=Network("""<table class="table-list"><tbody><tr><td class="name"><a>cat</a><a href="/torrent/1">Film</a></td><td class="seeds">123</td><td class="leeches">4</td><td class="size">2 GB<span>uploader</span></td></tr></tbody></table>""")
        val provider=X1337Provider(net)
        val row=provider.search("Film",MediaType.Movie).single();assertNull(row.magnet)
        net.body="""<a href="$magnet">Magnet</a>"""
        assertEquals(TorrentLink.Magnet(magnet),provider.resolve(row))
        assertEquals("https://1337x.to/torrent/1",net.requested)
    }
    @Test fun noNameClubFormAndSelectorsKeepTitleStatsAndLazyMagnet()=runBlocking {
        val net=Network("""<table class="forumline tablesorter"><tbody><tr><td><a href="tracker.php?f=216">cat</a></td><td><a href="viewtopic.php?t=1"><b>Фильм</b></a><a href="download.php?id=1">file</a></td><td></td><td></td><td></td><td><u>12345</u></td><td class="seedmed"><b>33</b></td><td class="leechmed"><b>5</b></td></tr></tbody></table>""")
        val provider=NoNameClubProvider(net);val row=provider.search("Фильм 2024",MediaType.Movie).single()
        assertTrue(net.payload.contains("f%5B%5D=216"));assertEquals(33L,row.seeds);assertEquals(12345L,row.sizeBytes)
        net.body="""<a href="$magnet">magnet</a>"""
        assertEquals(TorrentLink.Magnet(magnet),provider.resolve(row))
    }
    @Test fun corruptJsonAndSourceChallengeAreFailuresRatherThanEmptySuccess() {
        assertThrows(Exception::class.java) { TorrentsCsvProvider(Network()).parse("<html>bad</html>".toByteArray()) }
        assertThrows(Exception::class.java) { NyaaProvider(Network()).parse("<title>Just a moment...</title>".toByteArray(),"https://nyaa.si/") }
    }
}
