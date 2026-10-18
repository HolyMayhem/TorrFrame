package tv.torrserve.silicon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.features.torrentsearch.data.TorrentSearchActions
import tv.torrserve.silicon.features.torrentsearch.presentation.*
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSearchViewModelTests {
    private val dispatcher=StandardTestDispatcher();private val models=ViewModelStore()
    @Before fun main(){Dispatchers.setMain(dispatcher)}
    @After fun clean(){models.clear();Dispatchers.resetMain()}
    private val row=TorrentResult("test","Test","Film",10,magnet=TorrentIdentity.magnet("1".repeat(40)))
    private class Actions:TorrentSearchActions {
        var adds=0;var fileCalls=0;var failAdd=false;var failFiles=false;var delayMs=0L
        var videos=listOf(TorrentFile(1,"film.mp4",1));var resume:TorrentFile?=null
        override suspend fun add(row:TorrentResult):String{adds++;delay(delayMs);if(failAdd)error("add");return "hash"}
        override suspend fun files(hash:String):List<TorrentFile>{fileCalls++;if(failFiles)error("files");return videos}
        override suspend fun preferred(hash:String,files:List<TorrentFile>)=resume ?: files.singleOrNull()
    }
    private fun vm(actions:Actions,saved:SavedStateHandle=SavedStateHandle(),delayed:Boolean=false):TorrentSearchViewModel {
        val p=object:SearchProvider{
            override val id="test";override val name="Test"
            override suspend fun search(query:String,type:MediaType):List<TorrentResult>{if(delayed)delay(50);return listOf(row)}
        }
        return TorrentSearchViewModel(TorrentSearchEngine(listOf(p),dispatcher=dispatcher),actions,saved).also{models.put("vm",it)}
    }
    @Test fun watchImportsAndRequestsExistingPlaybackForSingleVideo()=runTest {
        val actions=Actions();val vm=vm(actions);vm.open(SearchTarget("Film"));runCurrent();vm.act(row,true);runCurrent()
        assertEquals(1,actions.adds);assertEquals(1,actions.fileCalls);assertEquals(TorrentPlayback("hash",actions.videos.single()),vm.state.value.play)
        assertTrue(row.key in vm.state.value.added);vm.consumePlay();assertNull(vm.state.value.play)
    }
    @Test fun addOnlyDoesNotFetchFilesOrStartPlayback()=runTest {
        val actions=Actions();val vm=vm(actions);vm.open(SearchTarget("Film"));runCurrent();vm.act(row,false);runCurrent()
        assertEquals(1,actions.adds);assertEquals(0,actions.fileCalls);assertNull(vm.state.value.play)
    }
    @Test fun multipleVideosUseChooserAndResumeSelectionUsesExistingPreference()=runTest {
        val actions=Actions().apply{videos=listOf(TorrentFile(1,"one.mp4",1),TorrentFile(2,"two.mp4",2))}
        val vm=vm(actions);vm.open(SearchTarget("Show",type=MediaType.Series));runCurrent();vm.act(row,true);runCurrent()
        assertNull(vm.state.value.play);assertEquals(2,vm.state.value.files.size)
        vm.chooseFile(actions.videos[1]);assertEquals(2,vm.state.value.play!!.file.id);assertTrue(vm.state.value.files.isEmpty())
        vm.consumePlay();actions.resume=actions.videos[0];vm.act(row,true);runCurrent()
        assertEquals(1,vm.state.value.play!!.file.id);assertTrue(vm.state.value.files.isEmpty())
    }
    @Test fun importAndMetadataFailuresReleaseBusyStateWithoutPretendingPlaybackSucceeded()=runTest {
        val actions=Actions().apply{failAdd=true};val vm=vm(actions);vm.open(SearchTarget("Film"));runCurrent()
        vm.act(row,true);runCurrent();assertEquals(TorrentActionError.Add,vm.state.value.actionError);assertTrue(vm.state.value.added.isEmpty());assertNull(vm.state.value.adding)
        actions.failAdd=false;actions.failFiles=true;vm.act(row,true);runCurrent()
        assertEquals(TorrentActionError.Files,vm.state.value.actionError);assertTrue(row.key in vm.state.value.added);assertNull(vm.state.value.play)
    }
    @Test fun closingCancelsPendingWatchAndCannotLaunchAnOldTitleAfterAnotherSearch()=runTest {
        val actions=Actions().apply{delayMs=100};val vm=vm(actions);vm.open(SearchTarget("Old"));runCurrent();vm.act(row,true);runCurrent()
        vm.close();vm.open(SearchTarget("New"));advanceUntilIdle()
        assertEquals("New",vm.state.value.target!!.title);assertNull(vm.state.value.play);assertTrue(vm.state.value.added.isEmpty())
    }
    @Test fun savedTargetRestoresSearchAndCloseClearsOnlyTorrentRoute()=runTest {
        val saved=SavedStateHandle(mapOf("torrentTitle" to "Фильм","torrentOriginal" to "Film","torrentYear" to 2024,"torrentType" to "Movie"))
        val vm=vm(Actions(),saved);runCurrent();assertEquals("Film 2024",vm.state.value.target!!.queries().first())
        assertEquals(1,vm.state.value.snapshot.results.size);vm.close();assertNull(saved.get<String>("torrentTitle"));assertNull(vm.state.value.target)
    }
    @Test fun retryReadsNewPreferencesAndPreparesOnlyEnabledQueries()=runTest {
        var policy=TorrentSearchOptions(titleMode=SearchTitleMode.Original)
        val queries=mutableListOf<String>();var prepared=0
        val provider=object:SearchProvider {
            override val id="test";override val name="Test"
            override suspend fun search(query:String,type:MediaType):List<TorrentResult>{queries+=query;return listOf(row)}
        }
        val vm=TorrentSearchViewModel(TorrentSearchEngine(listOf(provider),dispatcher=dispatcher),Actions(),SavedStateHandle(),
            options={policy},prepare={target,_ -> prepared++;target.copy(title="Фильм")}).also{models.put("vm",it)}
        vm.open(SearchTarget("Film","Original",2024,tmdbId=42));runCurrent()
        assertEquals(listOf("Original 2024"),queries)
        policy=policy.copy(titleMode=SearchTitleMode.Russian,minSeeds=20);queries.clear();vm.retry();runCurrent()
        assertEquals(listOf("Фильм 2024"),queries);assertTrue(vm.state.value.snapshot.results.isEmpty())
        assertEquals(listOf("Фильм 2024"),vm.state.value.queries)
        val previous=prepared;policy=policy.copy(enabledProviders=emptySet());queries.clear();vm.retry();runCurrent()
        assertTrue(queries.isEmpty());assertEquals(previous,prepared);assertFalse(vm.state.value.searching)
    }

}
