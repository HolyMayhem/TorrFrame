package tv.torrserve.silicon.catalog

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTests {
    private val dispatcher=StandardTestDispatcher()
    private val store=ViewModelStore()
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun clean() { store.clear();Dispatchers.resetMain() }
    private class Repository : CatalogRepository {
        override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) =
            flowOf(CatalogLoad(data = CatalogPage(emptyList(), page, 1)))
        val queries=mutableListOf<String>(); val pages=mutableListOf<Int>(); var delayMs=0L;var failPage=0
        var searchCancelled=false;val sections=mutableListOf<CatalogSection>();val detailLanguages=mutableListOf<String>()
        val discoveries=mutableListOf<Triple<CatalogFilter,CatalogType,String>>();val discoverPages=mutableListOf<Int>();var genreDelay=0L
        fun page(page: Int)=CatalogPage(listOf(CatalogItem(CatalogRef(page,CatalogType.Movie),"Title $page")),page,3)
        override fun section(section: CatalogSection, language: String, page: Int, force: Boolean)=flow {
            sections+=section;emit(CatalogLoad(data=page(page)))
        }
        override fun search(query: String, language: String, page: Int, force: Boolean)=flow {
            queries+=query;pages+=page
            try { delay(delayMs) } catch(e:CancellationException) { searchCancelled=true;throw e }
            if(page==failPage) emit(CatalogLoad<CatalogPage>(error=true)) else emit(CatalogLoad(data=page(page)))
        }
        override fun details(ref: CatalogRef, language: String, force: Boolean)=flow {
            detailLanguages+=language;emit(CatalogLoad(data=CatalogDetails(CatalogItem(ref,"Detail"),language,emptyList(),null,emptyList(),emptyList())))
        }
        override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean)=flow {
            discoveries+=Triple(filter,type,language);discoverPages+=page;delay(genreDelay)
            if(page==failPage) emit(CatalogLoad<CatalogPage>(error=true)) else emit(CatalogLoad(data=CatalogPage(
                listOf(CatalogItem(CatalogRef(page,type),"${filter.name} $language $page")),page,3)))
        }
    }
    private fun vm(repo:Repository, lang:MutableStateFlow<String> = MutableStateFlow("ru-RU"), saved:SavedStateHandle=SavedStateHandle())=
        CatalogViewModel(repo,lang,saved).also { store.put("catalog",it) }
    @Test fun listsAreIndependentAndOnlyOneQueuedActionIsAccepted()=runTest {
        val lists=MemoryCatalogUserLists();val vm=CatalogViewModel(Repository(),MutableStateFlow("ru-RU"),SavedStateHandle(),userLists=lists)
        store.put("lists",vm);runCurrent()
        val item=CatalogItem(CatalogRef(1,CatalogType.Movie),"Фильм")
        var completed=0
        vm.toggleList(item,CatalogUserList.Favorites){completed++}
        vm.toggleList(item,CatalogUserList.Favorites){completed++}
        runCurrent();assertEquals(1,completed);assertTrue(vm.state.value.userEntries.single().favorite)
        vm.toggleList(item,CatalogUserList.WatchLater){completed++};runCurrent()
        vm.toggleList(item,CatalogUserList.Favorites){completed++};runCurrent()
        assertFalse(vm.state.value.userEntries.single().favorite);assertTrue(vm.state.value.userEntries.single().watchLater)
        assertFalse(vm.state.value.listBusy);assertFalse(vm.state.value.listError)
    }
    @Test fun listWriteFailureDoesNotReportSuccessOrLoseExistingItems()=runTest {
        val memory=MemoryCatalogUserLists()
        val item=CatalogItem(CatalogRef(1,CatalogType.Movie),"Saved")
        memory.toggle(item,"en-US",CatalogUserList.Favorites)
        val failing=object:CatalogUserLists by memory {
            override suspend fun toggle(item:CatalogItem,language:String,list:CatalogUserList) { error("Disk write failure") }
        }
        val vm=CatalogViewModel(Repository(),MutableStateFlow("en-US"),SavedStateHandle(),userLists=failing)
        store.put("lists",vm);runCurrent();var completed=false
        vm.toggleList(item,CatalogUserList.Favorites){completed=true};runCurrent()
        assertFalse(completed);assertTrue(vm.state.value.listError);assertFalse(vm.state.value.listBusy)
        assertTrue(vm.state.value.userEntries.single().favorite)
    }
    @Test fun savedItemsLocalizeOnlyWhenNeededAndKeepIndependentLanguageSnapshots()=runTest {
        val lists=MemoryCatalogUserLists();val lang=MutableStateFlow("en-US");val repo=Repository()
        val item=CatalogItem(CatalogRef(1,CatalogType.Movie),"Фильм","Original")
        lists.toggle(item,"ru-RU",CatalogUserList.Favorites)
        val vm=CatalogViewModel(repo,lang,SavedStateHandle(),userLists=lists);store.put("lists",vm);runCurrent()
        vm.localizeListItem(item.ref);vm.localizeListItem(item.ref);runCurrent()
        assertEquals(listOf("en-US"),repo.detailLanguages)
        assertEquals("Detail",vm.state.value.userEntries.single().item("en-US").title)
        lang.value="ru-RU";runCurrent();vm.localizeListItem(item.ref);runCurrent()
        assertEquals("Фильм",vm.state.value.userEntries.single().item("ru-RU").title)
        assertEquals(1,repo.detailLanguages.size)
    }
    @Test fun anInFlightListWriteFinishesAfterModelClear()=runTest {
        val memory=MemoryCatalogUserLists();val finish=CompletableDeferred<Unit>()
        val slow=object:CatalogUserLists by memory {
            override suspend fun toggle(item:CatalogItem,language:String,list:CatalogUserList) { finish.await();memory.toggle(item,language,list) }
        }
        val vm=CatalogViewModel(Repository(),MutableStateFlow("en-US"),SavedStateHandle(),userLists=slow)
        store.put("lists",vm);runCurrent()
        vm.toggleList(CatalogItem(CatalogRef(1,CatalogType.Movie),"Saved"),CatalogUserList.Favorites){};runCurrent()
        store.clear();finish.complete(Unit);runCurrent()
        assertTrue(memory.entries.value.single().favorite)
    }
    @Test fun debounceOnlySearchesLatestQueryAndEmptyQueryMakesNoCall()=runTest {
        val repo=Repository();val vm=vm(repo);runCurrent()
        vm.query("И");advanceTimeBy(200);vm.query("Интерстеллар");advanceTimeBy(449);runCurrent();assertTrue(repo.queries.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf("Интерстеллар"),repo.queries);assertFalse(vm.state.value.results.loading)
        vm.query("  ");advanceTimeBy(1000);runCurrent();assertEquals(1,repo.queries.size);assertTrue(vm.state.value.results.items.isEmpty())
    }
    @Test fun newInputImmediatelyRemovesOldResultsAndCancelsInflightSearch()=runTest {
        val repo=Repository().apply{delayMs=2000};val vm=vm(repo);runCurrent()
        vm.query("Old");advanceTimeBy(450);runCurrent();assertEquals(listOf("Old"),repo.queries)
        vm.query("New");runCurrent();assertTrue(repo.searchCancelled);assertTrue(vm.state.value.results.items.isEmpty())
        advanceTimeBy(2451);runCurrent();assertEquals("New",vm.state.value.query);assertEquals(listOf("Old","New"),repo.queries)
        assertEquals(1,vm.state.value.results.items.size)
    }
    @Test fun paginationDeduplicatesAndFailedNextPageKeepsResultsAndCanRetry()=runTest {
        val repo=Repository();val vm=vm(repo);runCurrent();vm.query("Interstellar");advanceTimeBy(451);runCurrent()
        repo.failPage=2;vm.moreSearch();runCurrent();assertTrue(vm.state.value.results.error);assertEquals(1,vm.state.value.results.items.size)
        assertEquals(1,vm.state.value.results.page);repo.failPage=0;vm.retryResults();runCurrent();assertEquals(2,vm.state.value.results.items.size)
        assertEquals(listOf(1,2,2),repo.pages)
        vm.moreSearch();runCurrent();vm.moreSearch();runCurrent();assertEquals(3,vm.state.value.results.items.size);assertEquals(4,repo.queries.size)
    }
    @Test fun detailsPreserveQueryAndFeedsAndLanguageChangeReloadsLocalizedData()=runTest {
        val repo=Repository();val lang=MutableStateFlow("ru-RU");val saved=SavedStateHandle();val vm=vm(repo,lang,saved);runCurrent()
        vm.query("Breaking Bad");advanceTimeBy(451);runCurrent();val sections=repo.sections.size
        val ref=CatalogRef(1396,CatalogType.Tv);vm.open(ref);runCurrent();assertEquals(ref,vm.state.value.selected)
        vm.close();runCurrent();assertEquals("Breaking Bad",vm.state.value.query);assertEquals(sections,repo.sections.size)
        vm.open(ref);runCurrent();lang.value="en-US";runCurrent();assertEquals("en-US",vm.state.value.details.data!!.overview)
        assertEquals(ref.key,saved.get<String>("catalogSelected"));assertEquals("Breaking Bad",saved.get<String>("catalogQuery"))
        assertEquals(12,repo.sections.size)
    }
    @Test fun visibleScreenDoesNotRefetchFreshFeedsButRechecksExpiredData()=runTest {
        val repo=Repository();var clock=1000L
        val vm=CatalogViewModel(repo,MutableStateFlow("ru-RU"),SavedStateHandle(),now={clock});store.put("catalog",vm);runCurrent()
        vm.query("Title");advanceTimeBy(451);runCurrent();vm.onVisible();runCurrent()
        assertEquals(6,repo.sections.size);assertEquals(1,repo.queries.size)
        clock+=13*3_600_000L;vm.onVisible();advanceTimeBy(451);runCurrent()
        assertEquals(11,repo.sections.size);assertEquals(2,repo.queries.size)
    }
    @Test fun savedSearchAndTypedSelectionAreRestored()=runTest {
        val saved=SavedStateHandle(mapOf("catalogQuery" to "Series","catalogSelected" to "tv:1396"));val vm=vm(Repository(),saved=saved)
        advanceTimeBy(451);runCurrent();assertEquals("Series",vm.state.value.query);assertEquals(CatalogType.Tv,vm.state.value.details.data!!.item.ref.type)
    }
    @Test fun genreSwitchCancelsOldDataPreservesTypeAndDoesNotFilterTextSearch()=runTest {
        val repo=Repository().apply{genreDelay=1000};val vm=vm(repo);runCurrent()
        vm.filter(CatalogFilter.Comedy);runCurrent();vm.filter(CatalogFilter.Horror);runCurrent()
        assertTrue(vm.state.value.genreFeeds.values.all { it.items.isEmpty() })
        advanceTimeBy(1001);runCurrent()
        assertEquals("Horror ru-RU 1",vm.state.value.genreFeeds.getValue(CatalogType.Movie).items.single().title)
        assertTrue(vm.state.value.genreFeeds.getValue(CatalogType.Tv).items.isEmpty())
        vm.query("Interstellar");advanceTimeBy(451);runCurrent();assertEquals(listOf("Interstellar"),repo.queries)
        vm.filter(CatalogFilter.Movies);runCurrent();assertEquals("",vm.state.value.query)
        assertEquals(CatalogFilter.Movies,vm.state.value.filter);assertEquals(6,repo.sections.size)
    }
    @Test fun genrePagingRetryDetailsAndLanguageChangesKeepSelectedCollection()=runTest {
        val repo=Repository();val languages=MutableStateFlow("ru-RU");val saved=SavedStateHandle();val vm=vm(repo,languages,saved);runCurrent()
        vm.filter(CatalogFilter.Comedy);runCurrent();assertEquals(2,repo.discoveries.size)
        vm.filter(CatalogFilter.Comedy);runCurrent();vm.onVisible();runCurrent();assertEquals(2,repo.discoveries.size)
        repo.failPage=2;vm.loadGenre(CatalogType.Movie,more=true);runCurrent()
        assertEquals(2,vm.state.value.genreFeeds.getValue(CatalogType.Movie).failedPage)
        repo.failPage=0;vm.retryGenre(CatalogType.Movie);runCurrent();assertEquals(2,vm.state.value.genreFeeds.getValue(CatalogType.Movie).items.size)
        val count=repo.discoveries.size;vm.open(CatalogRef(1,CatalogType.Movie));runCurrent();vm.close();runCurrent()
        assertEquals(count,repo.discoveries.size);assertEquals(CatalogFilter.Comedy,vm.state.value.filter)
        languages.value="en-US";runCurrent()
        assertTrue(vm.state.value.genreFeeds.getValue(CatalogType.Movie).items.single().title.contains("en-US"))
        assertEquals("Comedy",saved.get<String>("catalogFilter"));assertEquals(CatalogFilter.Comedy,vm.state.value.filter)
    }
    @Test fun savedGenreLoadsOnlyThatCollectionAndSavedCategoryLoadsOnlyItsSections()=runTest {
        val repo=Repository();val saved=SavedStateHandle(mapOf("catalogFilter" to "Drama"));val vm=vm(repo,saved=saved);runCurrent()
        assertEquals(CatalogFilter.Drama,vm.state.value.filter);assertEquals(2,repo.discoveries.size);assertTrue(repo.sections.isEmpty())
        vm.filter(CatalogFilter.Series);runCurrent();assertEquals(listOf(CatalogSection.PopularTv,CatalogSection.TrendingTv),repo.sections)
        vm.filter(CatalogFilter.All);runCurrent();assertEquals(6,repo.sections.size)
    }
    @Test fun developerRefreshReloadsCurrentGenreOncePerRequestAndPreservesSelection()=runTest {
        val repo=Repository();val vm=vm(repo);runCurrent()
        vm.filter(CatalogFilter.Comedy);runCurrent();vm.open(CatalogRef(1,CatalogType.Movie));runCurrent()
        val count=repo.discoveries.size;val details=repo.detailLanguages.size;val sections=repo.sections.size
        vm.refresh(1);runCurrent()
        assertEquals(count+2,repo.discoveries.size);assertEquals(details+1,repo.detailLanguages.size)
        assertEquals(sections,repo.sections.size);assertEquals(CatalogFilter.Comedy,vm.state.value.filter)
        assertEquals(CatalogRef(1,CatalogType.Movie),vm.state.value.selected)
        vm.refresh(1);runCurrent();assertEquals(count+2,repo.discoveries.size)
        vm.refresh(2);runCurrent();assertEquals(count+4,repo.discoveries.size)
    }
    @Test fun developerRefreshCancelsOldTextSearchAndKeepsQuery()=runTest {
        val repo=Repository().apply{delayMs=1000};val vm=vm(repo);runCurrent()
        vm.query("Interstellar");advanceTimeBy(451);runCurrent()
        vm.refresh(1);runCurrent();assertTrue(repo.searchCancelled)
        assertEquals(listOf("Interstellar","Interstellar"),repo.queries)
        advanceTimeBy(1001);runCurrent();assertEquals("Interstellar",vm.state.value.query)
        assertEquals(1,vm.state.value.results.items.size)
    }

}
