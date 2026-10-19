package tv.torrserve.silicon.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import tv.torrserve.silicon.core.network.Http
import tv.torrserve.silicon.core.network.TmdbClient
import tv.torrserve.silicon.features.catalog.data.*
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRelatedTests {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private val movie = CatalogRef(157336, CatalogType.Movie)

    @Test fun apiUsesTypedEndpointsLanguageAndPageWithoutExtraDetailRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TmdbCatalogApi(TmdbClient(Http(), { "fixture-key" }, server.url("/3/").toString()))
            for (type in CatalogType.entries) for (source in CatalogRelatedSource.entries) for (language in listOf("ru-RU", "en-US")) {
                server.enqueue(MockResponse().setBody("{\"page\":2,\"results\":[]}"))
                api.related(CatalogRef(123, type), source, language, 2)
                val url = server.takeRequest().requestUrl!!
                assertEquals("/3/${type.path}/123/${source.path}", url.encodedPath)
                assertEquals(language, url.queryParameter("language")); assertEquals("2", url.queryParameter("page"))
                assertNull(url.queryParameter("sort_by"))
            }
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun cacheIsolatedByTitleTypeSourceLanguageAndPageAndSurvivesOffline() = runBlocking {
        val remote = TestRemote(); val cache = MemoryCatalogCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CatalogRepositoryImpl(remote, cache, scope, { cache.clock })
            for (type in CatalogType.entries) for (source in CatalogRelatedSource.entries) for (language in listOf("ru-RU", "en-US", "ru-RU")) {
                val page = repo.related(CatalogRef(movie.id, type), source, language).toList().last().data!!
                assertEquals(type, page.items.single().ref.type)
            }
            assertEquals(8, remote.calls)
            repo.related(movie, CatalogRelatedSource.Similar, "ru-RU", 2).toList()
            repo.related(movie.copy(id = 42), CatalogRelatedSource.Similar, "ru-RU").toList()
            assertEquals(10, remote.calls); assertEquals(10, cache.entries.size)
            assertTrue(cache.entries.keys.contains("related:similar:movie:157336:ru-RU:1"))
            val entry = cache.entries.getValue("related:similar:movie:157336:ru-RU:1")
            assertEquals(CatalogRepositoryImpl.DAY, entry.expiresAt - entry.fetchedAt)
            cache.clock += 25 * CatalogRepositoryImpl.HOUR; remote.fail = true
            val offline = repo.related(movie, CatalogRelatedSource.Similar, "ru-RU").toList().last()
            assertTrue(offline.error); assertTrue(offline.stale); assertNotNull(offline.data)
        } finally { scope.cancel() }
    }

    @Test fun concurrentRelatedRequestsShareTheExistingNetworkCoalescing() = runBlocking {
        val remote = TestRemote().apply { wait = 60 }; val cache = MemoryCatalogCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CatalogRepositoryImpl(remote, cache, scope, { cache.clock })
            awaitAll(async { repo.related(movie, CatalogRelatedSource.Similar, "ru-RU").toList() },
                async { repo.related(movie, CatalogRelatedSource.Similar, "ru-RU").toList() })
            assertEquals(1, remote.calls)
        } finally { scope.cancel() }
    }

    private data class Call(val ref: CatalogRef, val source: CatalogRelatedSource, val language: String, val page: Int)
    private class Repository : CatalogRepository {
        val calls = mutableListOf<Call>()
        val details = mutableListOf<Pair<CatalogRef, String>>()
        var similarEmpty = false; var allEmpty = false; var failPage = 0; var wait = 0L; var cancellations = 0
        override fun section(section: CatalogSection, language: String, page: Int, force: Boolean) = emptyFlow<CatalogLoad<CatalogPage>>()
        override fun search(query: String, language: String, page: Int, force: Boolean) = emptyFlow<CatalogLoad<CatalogPage>>()
        override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean) = emptyFlow<CatalogLoad<CatalogPage>>()
        override fun details(ref: CatalogRef, language: String, force: Boolean) = flow {
            details += ref to language
            emit(CatalogLoad(CatalogDetails(CatalogItem(ref, "$language ${ref.key}"), "Overview $language", emptyList(), null, emptyList(), emptyList())))
        }
        override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) = flow {
            calls += Call(ref, source, language, page)
            try { delay(wait) } catch (e: CancellationException) { cancellations++; throw e }
            if (page == failPage) { emit(CatalogLoad<CatalogPage>(error = true)); return@flow }
            val ids = if (allEmpty || (similarEmpty && source == CatalogRelatedSource.Similar)) emptyList()
                else if (page == 1) listOf(90, 40, 70) else listOf(70, 30, 20)
            emit(CatalogLoad(CatalogPage(ids.map { CatalogItem(CatalogRef(it, ref.type), "$language $it") }, page, 2)))
        }
    }
    private fun model(repo: Repository, languages: MutableStateFlow<String> = MutableStateFlow("ru-RU"), saved: SavedStateHandle = SavedStateHandle()) =
        CatalogViewModel(repo, languages, saved).also { models.put("catalog", it) }

    @Test fun detailsLoadIndependentlyAndNonemptySimilarKeepsTmdbOrder() = runTest {
        val repo = Repository().apply { wait = 1000 }; val vm = model(repo); runCurrent()
        vm.open(movie); runCurrent()
        assertNotNull(vm.state.value.details.data); assertTrue(vm.state.value.related.loading)
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf(90, 40, 70), vm.state.value.related.items.map { it.ref.id })
        assertEquals(listOf(CatalogRelatedSource.Similar), repo.calls.map { it.source })
    }

    @Test fun emptySimilarFallsBackOnceAndPaginationStaysOnRecommendations() = runTest {
        val repo = Repository().apply { similarEmpty = true }; val vm = model(repo); runCurrent()
        vm.open(movie.copy(type = CatalogType.Tv)); runCurrent()
        assertEquals(CatalogRelatedSource.Recommendations, vm.state.value.relatedSource)
        assertEquals(listOf(CatalogRelatedSource.Similar, CatalogRelatedSource.Recommendations), repo.calls.map { it.source })
        repo.failPage = 2; vm.loadRelated(more = true); runCurrent()
        assertEquals(3, vm.state.value.related.items.size); assertEquals(2, vm.state.value.related.failedPage)
        repo.failPage = 0; vm.retryRelated(); runCurrent()
        assertEquals(listOf(90, 40, 70, 30, 20), vm.state.value.related.items.map { it.ref.id })
        assertTrue(vm.state.value.related.items.all { it.ref.type == CatalogType.Tv })
        assertEquals(1, repo.calls.count { it.source == CatalogRelatedSource.Similar })
        vm.loadRelated(more = true); runCurrent(); assertEquals(4, repo.calls.size)
    }

    @Test fun failedSimilarDoesNotFallbackOrBreakDetailsAndCanRetry() = runTest {
        val repo = Repository().apply { failPage = 1 }; val vm = model(repo); runCurrent()
        vm.open(movie); runCurrent()
        assertTrue(vm.state.value.related.error); assertFalse(vm.state.value.details.error)
        assertNotNull(vm.state.value.details.data); assertEquals(1, repo.calls.size)
        repo.failPage = 0; vm.retryRelated(); runCurrent()
        assertEquals(3, vm.state.value.related.items.size); assertFalse(vm.state.value.related.error)
    }

    @Test fun bothEmptySourcesProduceNoItemsAndStopLoading() = runTest {
        val repo = Repository().apply { allEmpty = true }; val vm = model(repo); runCurrent()
        vm.open(movie); runCurrent()
        assertEquals(2, repo.calls.size); assertTrue(vm.state.value.related.items.isEmpty())
        assertFalse(vm.state.value.related.loading); assertFalse(vm.state.value.related.error)
    }

    @Test fun languageChangesCancelOldRequestsAndNeverShowTheWrongLanguage() = runTest {
        val repo = Repository().apply { wait = 1000 }; val languages = MutableStateFlow("ru-RU")
        val vm = model(repo, languages); runCurrent(); vm.open(movie); runCurrent()
        languages.value = "en-US"; runCurrent()
        assertTrue(vm.state.value.related.items.isEmpty()); assertEquals(1, repo.cancellations)
        advanceTimeBy(1001); runCurrent()
        assertTrue(vm.state.value.related.items.all { it.title.startsWith("en-US") })
        languages.value = "ru-RU"; advanceTimeBy(1001); runCurrent()
        assertTrue(vm.state.value.related.items.all { it.title.startsWith("ru-RU") })
        assertEquals(listOf("ru-RU", "en-US", "ru-RU"), repo.calls.map { it.language })
    }

    @Test fun backRestoresParentPagesWithoutRefetchAndDistinctVisitsSupportCycles() = runTest {
        val repo = Repository(); val vm = model(repo); runCurrent(); vm.open(movie); runCurrent()
        vm.loadRelated(more = true); runCurrent()
        val parent = vm.state.value
        val child = CatalogRef(90, CatalogType.Movie)
        vm.openRelated(child); runCurrent(); vm.openRelated(movie); runCurrent()
        assertNotEquals(parent.detailEntry, vm.state.value.detailEntry)
        vm.close(); runCurrent(); assertEquals(child, vm.state.value.selected)
        val count = repo.calls.size
        vm.close(); runCurrent()
        assertEquals(parent.detailEntry, vm.state.value.detailEntry)
        assertEquals(parent.details, vm.state.value.details); assertEquals(parent.related, vm.state.value.related)
        assertEquals(count, repo.calls.size)
        vm.close(); runCurrent(); assertNull(vm.state.value.selected)
    }

    @Test fun savedHistoryRestoresTypedParentsAndReloadsThemInTheCurrentLanguage() = runTest {
        val repo = Repository(); val saved = SavedStateHandle(); val language = MutableStateFlow("ru-RU")
        val vm = model(repo, language, saved); runCurrent(); vm.open(movie.copy(type = CatalogType.Tv)); runCurrent()
        vm.openRelated(CatalogRef(90, CatalogType.Tv)); runCurrent()
        val restored = model(repo, MutableStateFlow("en-US"), saved); runCurrent()
        restored.close(); runCurrent()
        assertEquals(CatalogRef(movie.id, CatalogType.Tv), restored.state.value.selected)
        assertTrue(restored.state.value.related.items.all { it.title.startsWith("en-US") })
        assertEquals("en-US", repo.details.last().second)
    }
}
