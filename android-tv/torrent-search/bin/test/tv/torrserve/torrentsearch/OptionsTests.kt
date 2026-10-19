package tv.torrserve.torrentsearch

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine

@OptIn(ExperimentalCoroutinesApi::class)
class OptionsTests {
    private fun row(id:Int,title:String,seeds:Long?=10,size:Long?=TorrentSearchOptions.GIB)=TorrentResult("p","p",title,seeds,sizeBytes=size,magnet=TorrentIdentity.magnet("%040x".format(id)))
    @Test fun filtersCombineResolutionFormatSizeAndSeedsWithoutGuessingUnknowns() {
        val options=TorrentSearchOptions(resolutions=setOf("1080p","2160p"),formats=setOf("WEB-DL","BluRay"),minBytes=TorrentSearchOptions.GIB,maxBytes=4*TorrentSearchOptions.GIB,minSeeds=5)
        assertTrue(options.accepts(row(1,"Film.1080p.WEB-DL",10)))
        assertTrue(options.accepts(row(2,"Film.2160p.BluRay",5,4*TorrentSearchOptions.GIB)))
        assertFalse(options.accepts(row(3,"Film.720p.WEB-DL")))
        assertFalse(options.accepts(row(4,"Film.1080p.HDTV")))
        assertFalse(options.accepts(row(5,"Film.1080p.WEB-DL",null)))
        assertFalse(options.accepts(row(6,"Film.1080p.WEB-DL",10,null)))
        assertFalse(options.accepts(row(7,"Film.1080p.WEB-DL",10,5*TorrentSearchOptions.GIB)))
    }
    @Test fun zeroSwitchPreservesUnknownButPositiveMinimumExcludesUnknown() {
        val unknown=row(1,"Film",null,null)
        assertTrue(TorrentSearchOptions().accepts(unknown))
        assertTrue(TorrentSearchOptions(hideZeroSeeds=true).accepts(unknown))
        assertFalse(TorrentSearchOptions(hideZeroSeeds=true).accepts(row(2,"Film",0)))
        assertFalse(TorrentSearchOptions(minSeeds=1).accepts(unknown))
    }
    @Test fun textSizesRecognizeBinaryDecimalAndRussianUnits() {
        assertEquals(TorrentSearchOptions.GIB,TorrentSize.bytes("1 GiB"))
        assertEquals(1_500_000_000L,TorrentSize.bytes("1,5 ГБ"))
        assertEquals(2_345_600_000L,TorrentSize.bytes("2 345,6\u00a0МБ"))
        assertTrue(TorrentSearchOptions(maxBytes=2*TorrentSearchOptions.GIB).accepts(row(1,"Film",size=null).copy(sizeText="1.5 GiB")))
        assertNull(TorrentSize.bytes("unknown"));assertNull(TorrentSize.bytes("-1 GB"))
    }
    @Test fun enabledSourcesAndEmptySelectionAreHonoredBeforeNetworkCalls()=runTest {
        val called=mutableListOf<String>()
        fun provider(id:String)=object:SearchProvider {
            override val id=id;override val name=id
            override suspend fun search(query:String,type:MediaType):List<TorrentResult>{called+=id;return listOf(row(1,"Film"))}
        }
        val engine=TorrentSearchEngine(listOf(provider("one"),provider("two")),dispatcher=StandardTestDispatcher(testScheduler))
        val result=engine.search(SearchTarget("Film"),TorrentSearchOptions(enabledProviders=setOf("two"))).last()
        assertEquals(listOf("two"),called);assertEquals(listOf("two"),result.sources.map{it.id})
        called.clear();assertTrue(engine.search(SearchTarget("Film"),TorrentSearchOptions(enabledProviders=emptySet())).last().sources.isEmpty());assertTrue(called.isEmpty())
    }
    @Test fun configuredTimeoutDoesNotBlockHealthySource()=runTest {
        val blocked=object:SearchProvider { override val id="blocked";override val name=id
            override suspend fun search(query:String,type:MediaType):List<TorrentResult> = awaitCancellation() }
        val result=TorrentSearchEngine(listOf(blocked),sourceTimeout=100,dispatcher=StandardTestDispatcher(testScheduler))
            .search(SearchTarget("Film"),TorrentSearchOptions(sourceTimeoutMs=5_000)).last()
        assertEquals(5_000,currentTime);assertEquals(SourcePhase.TimedOut,result.sources.single().phase)
    }
    @Test fun filterAfterHashMergeKeepsLateHighSeedsAndRicherQualitySorted()=runTest {
        var call=0
        val provider=object:SearchProvider{override val id="p";override val name=id
            override suspend fun search(query:String,type:MediaType)=if(call++==0)listOf(row(1,"Film",0,null),row(2,"Film.1080p.WEB-DL",20))
                else listOf(row(1,"Film.1080p.WEB-DL",50,null).copy(sizeText="1 GiB"))}
        val results=TorrentSearchEngine(listOf(provider),dispatcher=StandardTestDispatcher(testScheduler))
            .search(SearchTarget("Русское","Original"),TorrentSearchOptions(minSeeds=5,resolutions=setOf("1080p"),minBytes=TorrentSearchOptions.GIB)).toList()
        assertEquals(listOf(50L,20L),results.last().results.map{it.seeds})
        results.forEach{assertEquals(it.results.sortedWith(TorrentSearchEngine.order),it.results)}
    }
    @Test fun titleModesLimitQueryLanguagesAndPreserveSeriesYearFallback() {
        val target=SearchTarget("Интерстеллар","Interstellar",2014)
        assertEquals(listOf("Interstellar 2014"),target.queries(SearchTitleMode.Original))
        assertEquals(listOf("Интерстеллар 2014"),target.queries(SearchTitleMode.Russian))
        assertEquals(listOf("Interstellar 2014","Интерстеллар 2014"),target.queries(SearchTitleMode.Both))
        assertEquals(listOf("Сериал 2020","Сериал"),SearchTarget("Сериал","Show",2020,MediaType.Series).queries(SearchTitleMode.Russian))
    }
    @Test fun normalizationBoundsInvalidPersistedPolicyAndRetainsDisabledAll() {
        val value=TorrentSearchOptions(enabledProviders=emptySet(),resolutions=setOf("bad","1080p"),minBytes=100,maxBytes=50,sourceTimeoutMs=0,minSeeds=-1).normalized()
        assertEquals(100,value.maxBytes);assertEquals(5_000,value.sourceTimeoutMs);assertEquals(0,value.minSeeds)
        assertEquals(setOf("1080p"),value.resolutions);assertEquals(emptySet<String>(),value.enabledProviders)
    }
}
