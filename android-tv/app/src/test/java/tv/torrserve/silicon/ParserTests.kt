package tv.torrserve.silicon

import org.junit.Test
import org.junit.Assert.*
import org.kxml2.io.KXmlParser
import tv.torrserve.silicon.features.metadata.MediaNameParser
import tv.torrserve.silicon.features.search.TorznabParser
import tv.torrserve.silicon.core.models.MediaKind
import tv.torrserve.silicon.core.network.ServiceFailure

class ParserTests {
    @Test fun movieRelease() {
        val p = MediaNameParser.parse("Interstellar.2014.2160p.BluRay.REMUX.mkv")
        assertEquals("Interstellar", p.title); assertEquals(2014, p.year); assertEquals(MediaKind.Movie, p.kind)
    }
    @Test fun episodeRelease() {
        val p = MediaNameParser.parse("Breaking.Bad.S02E05.1080p.WEB-DL.mkv")
        assertEquals("Breaking Bad", p.title); assertEquals(2, p.season); assertEquals(5, p.episode); assertEquals(MediaKind.Tv, p.kind)
    }
    @Test fun alternativeEpisodeNotation() {
        val p = MediaNameParser.parse("The.Show.2x05.720p.mp4")
        assertEquals("The Show", p.title); assertEquals(2, p.season); assertEquals(5, p.episode)
    }
    @Test fun numericTitleKeepsFirstYear() {
        val p = MediaNameParser.parse("1917.2019.1080p.mkv")
        assertEquals("1917", p.title); assertEquals(2019, p.year)
        assertEquals("1917", MediaNameParser.parse("1917").title)
    }
    @Test fun bilingualAliasesExcludeDirector() {
        val values = MediaNameParser.candidates(listOf("Интерстеллар / Interstellar (Кристофер Нолан) [2014, BDRip]"), "TMDB", "ru-RU")
        assertTrue(values.any { it.parsed.title == "Интерстеллар" }); assertTrue(values.any { it.parsed.title == "Interstellar" })
        assertFalse(values.any { it.parsed.title == "Кристофер Нолан" }); assertTrue(values.size <= 5)
    }
    @Test fun titlePunctuationAndDiacritics() {
        assertEquals(3.0, MediaNameParser.similarity("Amélie", "Amelie"), .001)
        assertTrue(MediaNameParser.similarity("Alien", "Interstellar") < .65)
    }
    private fun parse(xml: String) = TorznabParser.results(xml) { KXmlParser() }
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    @Test fun namespacesAndTorrentUrls() {
        val xml = """<rss xmlns:t="http://torznab.com/schemas/2015/feed"><channel><item><title>Film &amp; Series</title><guid>a</guid><enclosure url="https://example.com/file?apikey=secret" length="123456"/><t:attr name="seeders" value="14"/><t:attr name="peers" value="3"/><t:attr name="category" value="2000"/></item></channel></rss>"""
        val result = parse(xml).results.single()
        assertEquals("Film & Series", result.title); assertEquals(14, result.seeds); assertEquals(123456L, result.size)
        assertEquals("Movies", result.kind); assertTrue(result.link.startsWith("https://"))
    }
    @Test fun deduplicatesByHashAndKeepsDifferentReleases() {
        fun item(guid: String, h: String, seed: Int) = """<item><title>Same Title</title><guid>$guid</guid><link>magnet:?xt=urn:btih:$h</link><t:attr name="seeders" value="$seed"/></item>"""
        val xml = "<rss xmlns:t=\"http://torznab.com/schemas/2015/feed\"><channel>" + item("a",hash,3) + item("b",hash.uppercase(),8) + item("c","1".repeat(40),2) + "</channel></rss>"
        val items = parse(xml).results; assertEquals(2, items.size); assertEquals(8, items.first().seeds)
    }
    @Test fun capsUsesActualApiDocument() {
        val caps = TorznabParser.caps("<caps><searching><search available=\"yes\"/><movie-search available=\"no\"/><tv-search available=\"yes\"/></searching></caps>") { KXmlParser() }
        assertTrue(caps.search); assertFalse(caps.movie); assertTrue(caps.tv)
    }
    @Test fun apiErrorDoesNotExposeDescriptionOrKey() {
        try { parse("<error code=\"100\" description=\"bad apikey=secret\"/>"); fail() }
        catch(e: ServiceFailure) { assertEquals(401,e.status); assertFalse(e.message.orEmpty().contains("secret")) }
    }
    @Test fun rejectsExternalEntities() {
        try { parse("<!DOCTYPE rss [<!ENTITY token SYSTEM 'file:///secrets'>]><rss/>"); fail() }
        catch(e: ServiceFailure) { assertEquals(-2,e.status) }
    }
    @Test fun retainsCompletedItemsOnMalformedTail() {
        val page = parse("<rss><channel><item><title>One</title><link>magnet:?xt=urn:btih:$hash</link></item><item>")
        assertEquals(1,page.results.size); assertTrue(page.partial)
    }
    @Test fun normalizesBase32Hashes() {
        assertEquals("0".repeat(40), TorznabParser.magnetHash("magnet:?xt=urn:btih:" + "A".repeat(32)))
    }
    @Test fun malformedHashCannotCollapseUnrelatedReleases() {
        fun item(tracker: String) = """<item><title>Same Title</title><guid>0</guid><jackettindexer>$tracker</jackettindexer><link>https://example.com/$tracker.torrent</link><attr name="infohash" value="undefined"/></item>"""
        assertEquals(2,parse("<rss><channel>"+item("A")+item("B")+"</channel></rss>").results.size)
    }
}
