package tv.torrserve.torrentsearch

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTests {
    private fun row(id:Int,seeds:Long?,provider:String="p")=TorrentResult(provider,provider,"Film $id 1080p WEB-DL",seeds,
        magnet=TorrentIdentity.magnet("%040x".format(id)))
    private fun provider(name:String,search:suspend () -> List<TorrentResult>)=object:SearchProvider {
        override val id=name;override val name=name
        override suspend fun search(query:String,type:MediaType)=search()
    }
    @Test fun parallelBatchesRemainSeedSortedAndHashDuplicatesMergeWithoutInflatingCounts()=runTest {
        val snapshots=mutableListOf<SearchSnapshot>()
        val fast=provider("fast") { delay(10);listOf(row(1,1,"fast"),row(2,0,"fast"),row(3,null,"fast")) }
        val slow=provider("slow") { delay(50);listOf(row(4,100,"slow"),row(1,20,"slow")) }
        val engine=TorrentSearchEngine(listOf(fast,slow),dispatcher=StandardTestDispatcher(testScheduler))
        val job=launch { engine.search(SearchTarget("Film",year=2024)).toList(snapshots) }
        runCurrent();advanceTimeBy(11);runCurrent()
        assertEquals(listOf(1L,0L,null),snapshots.last().results.map { it.seeds });assertTrue(snapshots.last().searching)
        advanceUntilIdle();job.join()
        val final=snapshots.last();assertFalse(final.searching)
        assertEquals(listOf(100L,20L,0L,null),final.results.map { it.seeds })
        assertEquals(4,final.results.size);assertEquals(setOf("fast","slow"),final.results[1].trackers)
        snapshots.forEach { assertEquals(it.results.sortedWith(TorrentSearchEngine.order),it.results) }
    }
    @Test fun failedAndTimedOutSourcesDoNotDiscardHealthyOrPartialResults()=runTest {
        var calls=0
        val partial=provider("partial") { if(calls++==0)listOf(row(1,5,"partial"))else error("broken second query") }
        val blocked=provider("blocked") { awaitCancellation() }
        val healthy=provider("healthy") { listOf(row(2,9,"healthy")) }
        val engine=TorrentSearchEngine(listOf(partial,blocked,healthy),sourceTimeout=100,dispatcher=StandardTestDispatcher(testScheduler))
        val final=engine.search(SearchTarget("Локальный","Original",2024)).last()
        assertEquals(listOf(9L,5L),final.results.map { it.seeds })
        assertEquals(SourcePhase.Failed,final.sources.first { it.id=="partial" }.phase)
        assertEquals(SourcePhase.TimedOut,final.sources.first { it.id=="blocked" }.phase)
        assertFalse(final.searching)
    }
    @Test fun cancellingTheSearchCancelsProviderWork()=runTest {
        var cancelled=false
        val slow=provider("slow") { try { awaitCancellation() }finally { cancelled=true } }
        val engine=TorrentSearchEngine(listOf(slow),dispatcher=StandardTestDispatcher(testScheduler))
        val job=launch { engine.search(SearchTarget("Film")).collect() };runCurrent();job.cancelAndJoin()
        assertTrue(cancelled)
    }
    @Test fun sourcesWithUnsupportedMediaTypeAreNotRequested()=runTest {
        var called=false
        val movie=object:SearchProvider {
            override val id="movie";override val name="movie";override val types=setOf(MediaType.Movie)
            override suspend fun search(query:String,type:MediaType):List<TorrentResult> { called=true;return emptyList() }
        }
        val final=TorrentSearchEngine(listOf(movie),dispatcher=StandardTestDispatcher(testScheduler)).search(SearchTarget("Show",type=MediaType.Series)).last()
        assertFalse(called);assertFalse(final.searching);assertTrue(final.sources.isEmpty())
    }
    @Test fun boundedResultsRetainHighestSeededTorrents()=runTest {
        val source=provider("p") { (1..100).map { row(it,it.toLong()) } }
        val final=TorrentSearchEngine(listOf(source),maxResults=5,dispatcher=StandardTestDispatcher(testScheduler)).search(SearchTarget("Film")).last()
        assertEquals(listOf(100L,99L,98L,97L,96L),final.results.map { it.seeds })
    }
    @Test fun hashIdentityAcceptsEncodedAndBase32BtihButRejectsOtherSchemes() {
        val hash="0000000000000000000000000000000000000000"
        assertEquals(hash,TorrentIdentity.hash("magnet:?dn=test&xt=urn%3Abtih%3A$hash"))
        assertEquals(hash,TorrentIdentity.hash("magnet:?xt=urn:btih:"+"A".repeat(32)))
        assertNull(TorrentIdentity.hash("https://site/?xt=urn:btih:$hash"))
        assertNull(TorrentIdentity.hash("magnet:?xt=urn:btih:bad"))
        assertNull(TorrentIdentity.web("file:///etc/passwd"));assertNull(TorrentIdentity.web("https://user:pass@site/path"))
    }
    @Test fun queriesUseOriginalLocalYearAndSeriesFallbackWithoutDuplicates() {
        assertEquals(listOf("Interstellar 2014","Интерстеллар 2014"),SearchTarget("Интерстеллар","Interstellar",2014).queries())
        assertEquals(listOf("Show 2020","Show"),SearchTarget("Show","Show",2020,MediaType.Series).queries())
        assertEquals(listOf("Film 2024"),SearchTarget("Film 2024","Film 2024",2024).queries())
        assertTrue(SearchTarget("",year=2024).queries().isEmpty())
    }
    @Test fun qualityIsDerivedWithoutGuessingUnknownNames() {
        assertEquals("2160p · WEB-DL · HDR",VideoQuality.from("Film.2160p.WEB-DL.HDR10.x265"))
        assertEquals("1080p · BluRay",VideoQuality.from("Film 1080p Blu-Ray"))
        assertEquals("",VideoQuality.from("Film title"))
    }
}
