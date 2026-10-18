package tv.torrserve.silicon.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.features.catalog.data.*
import tv.torrserve.silicon.features.catalog.domain.*

class MemoryCatalogCache(var clock: Long = 10_000) : CatalogCache {
    val entries = mutableMapOf<String, CatalogCache.Entry>()
    val retention = mutableMapOf<String, Long>()
    override suspend fun read(key: String) = entries[key]
    override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) {
        entries[key] = CatalogCache.Entry(payload, clock, clock + ttl); this.retention[key] = retention
    }
    override suspend fun remove(key: String) { entries.remove(key) }
}
open class TestRemote : CatalogRemote {
    override suspend fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int) = response(language, page)
    var calls = 0
    var fail = false
    var wait = 0L
    var cancelled = false
    val languages = mutableListOf<String>()
    suspend fun response(language: String, page: Int): JSONObject {
        calls++; languages += language
        try { delay(wait) } catch (e: CancellationException) { cancelled = true; throw e }
        if (fail) throw java.io.IOException("offline")
        return JSONObject("""{"page":$page,"total_pages":2,"results":[{"id":$page,"title":"Movie $page","name":"Series $page","media_type":"movie"}]}""")
    }
    override suspend fun section(section: CatalogSection, language: String, page: Int) = response(language, page)
    override suspend fun search(query: String, language: String, page: Int) = response(language, page)
    override suspend fun details(ref: CatalogRef, language: String) = JSONObject("""{"id":${ref.id},"title":"Movie","name":"Series"}""")
    override suspend fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int) = response(language, page)
}
class CatalogDataTests {
    @Test fun genreDiscoveryUsesTypedTmdbIdsLanguagePageAndNoDetailFanout() = runBlocking {
        MockWebServer().use { server -> server.start()
            val api = TmdbCatalogApi(TmdbClient(Http(), { "test-key" }, server.url("/3/").toString()))
            for (filter in CatalogFilter.entries.filter { it.isGenre }) for (type in filter.genreTypes) {
                server.enqueue(MockResponse().setBody("{\"results\":[]}"))
                api.discover(filter, type, "ru-RU", 2)
                val url = server.takeRequest().requestUrl!!
                assertEquals("/3/discover/${type.path}", url.encodedPath)
                assertEquals(filter.genres(type), url.queryParameter("with_genres"))
                assertEquals("ru-RU", url.queryParameter("language")); assertEquals("2", url.queryParameter("page"))
                assertEquals("false", url.queryParameter("include_adult")); assertEquals("popularity.desc", url.queryParameter("sort_by"))
            }
            assertEquals(CatalogFilter.entries.sumOf { it.genreTypes.size }, server.requestCount)
            assertEquals(listOf(CatalogType.Movie), CatalogFilter.Horror.genreTypes)
        }
    }
    @Test fun genreCacheSeparatesGenreTypeLanguageAndPageAndReusesEachSelection() = runBlocking {
        val api = TestRemote(); val cache = MemoryCatalogCache(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CatalogRepositoryImpl(api, cache, scope, { cache.clock })
            for (language in listOf("ru-RU", "en-US", "ru-RU")) for (type in CatalogType.entries) {
                val data = repo.discover(CatalogFilter.Comedy, type, language).toList().last().data!!
                assertEquals(type, data.items.single().ref.type)
            }
            assertEquals(4, api.calls)
            repo.discover(CatalogFilter.Drama, CatalogType.Movie, "ru-RU").toList()
            repo.discover(CatalogFilter.Comedy, CatalogType.Movie, "ru-RU", page = 2).toList()
            assertEquals(6, api.calls); assertEquals(6, cache.entries.size)
            api.fail = true; cache.clock += 13 * CatalogRepositoryImpl.HOUR
            val offline = repo.discover(CatalogFilter.Comedy, CatalogType.Movie, "ru-RU").toList().last()
            assertTrue(offline.error); assertNotNull(offline.data)
        } finally { scope.cancel() }
    }
    @Test fun multiSearchFiltersPeopleAdultAndMalformedRecordsAndKeepsTypedIds() {
        val page = CatalogParser.page(JSONObject("""{"page":2,"total_pages":999,"results":[{"id":1,"media_type":"movie","title":"Фильм"},{"id":1,"media_type":"tv","name":"Series"},{"id":2,"media_type":"person","name":"Actor"},{"id":3,"media_type":"movie","title":"Adult","adult":true},{"id":4,"media_type":"movie"},{"media_type":"tv","name":"No ID"}]}"""))
        assertEquals(listOf("movie:1", "tv:1"), page.items.map { it.ref.key }); assertEquals(500, page.totalPages)
        assertEquals(2, page.page); assertEquals("", page.items[0].poster)
    }
    @Test fun optionalDetailsAreSafeAndCreditsAndTvFieldsAreParsed() {
        val d = CatalogParser.details(JSONObject("""{"id":12,"name":"Сериал","original_name":"Series","number_of_seasons":3,"number_of_episodes":20,"episode_run_time":[42],"last_air_date":"2026-10-01","credits":{"cast":[{"name":"Actor"}],"crew":[{"name":"Director","job":"Director"}]}}"""), CatalogType.Tv)
        assertEquals(3, d.seasons); assertEquals(20, d.episodes); assertEquals(42,d.runtime); assertEquals(listOf("Director"), d.directors)
        assertEquals("",d.overview); assertTrue(d.genres.isEmpty()); assertEquals(listOf("Actor"),d.cast)
    }
    @Test fun discoverExcludesFutureReleasesAndUsesVotesAndPopularity() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"results\":[]}"))
            val api = TmdbCatalogApi(TmdbClient(Http(), { "test-key" }, server.url("/3/").toString()), { LocalDate.of(2026,10,14) })
            api.section(CatalogSection.NewMovies,"ru-RU",2)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/3/discover/movie",url.encodedPath); assertEquals("2026-10-14",url.queryParameter("primary_release_date.lte"))
            assertEquals("2026-04-14",url.queryParameter("primary_release_date.gte")); assertEquals("30",url.queryParameter("vote_count.gte"))
            assertEquals("popularity.desc",url.queryParameter("sort_by")); assertEquals("ru-RU",url.queryParameter("language")); assertEquals("2",url.queryParameter("page"))
        }
    }
    @Test fun detailsLazilyFetchEnglishSynopsisOnlyWhenMissing() = runBlocking {
        MockWebServer().use { s -> s.start()
            s.enqueue(MockResponse().setBody("""{"id":1,"title":"Русский фильм","overview":"","credits":{"cast":[{"name":"Actor"}]}}"""))
            s.enqueue(MockResponse().setBody("""{"translations":[{"iso_639_1":"en","iso_3166_1":"US","data":{"overview":"English synopsis"}}]}"""))
            val api=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString()))
            val d=CatalogParser.details(api.details(CatalogRef(1,CatalogType.Movie),"ru-RU"),CatalogType.Movie)
            assertEquals("Русский фильм",d.item.title); assertEquals("English synopsis",d.overview); assertTrue(d.englishOverview)
            assertEquals("credits",s.takeRequest().requestUrl!!.queryParameter("append_to_response"))
            assertEquals("/3/movie/1/translations",s.takeRequest().requestUrl!!.encodedPath)
            s.enqueue(MockResponse().setBody("""{"id":2,"title":"Фильм","overview":"Есть описание"}"""))
            api.details(CatalogRef(2,CatalogType.Movie),"ru-RU"); assertEquals(3,s.requestCount)
        }
    }
    @Test fun englishFallbackFailureDoesNotDiscardLocalizedDetails() = runBlocking {
        MockWebServer().use { s -> s.start(); s.enqueue(MockResponse().setBody("""{"id":1,"title":"Фильм"}"""));repeat(2) { s.enqueue(MockResponse().setResponseCode(503)) }
            val d=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString())).details(CatalogRef(1,CatalogType.Movie),"ru-RU")
            assertEquals("Фильм",d.getString("title"))
        }
    }
    @Test fun translationsRecoverMissingRussianMovieAndTvFields() = runBlocking {
        for (type in CatalogType.entries) MockWebServer().use { server -> server.start()
            val field=if(type==CatalogType.Movie)"title" else "name"
            val original=if(type==CatalogType.Movie)"original_title" else "original_name"
            server.enqueue(MockResponse().setBody(JSONObject().put("id",1).put(field,"Original").put(original,"Original").put("original_language","en").put("overview","").toString()))
            server.enqueue(MockResponse().setBody("""{"translations":[{"iso_639_1":"ru","iso_3166_1":"RU","data":{"$field":"Русское название","overview":"Русское описание"}},{"iso_639_1":"en","iso_3166_1":"US","data":{"$field":"English","overview":"English overview"}}]}"""))
            val api=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},server.url("/3/").toString()))
            val d=CatalogParser.details(api.details(CatalogRef(1,type),"ru-RU"),type)
            assertEquals("Русское название",d.item.title);assertEquals("Русское описание",d.overview);assertFalse(d.englishOverview)
            assertEquals("ru-RU",server.takeRequest().requestUrl!!.queryParameter("language"))
            assertEquals("/3/${type.path}/1/translations",server.takeRequest().requestUrl!!.encodedPath);assertEquals(2,server.requestCount)
        }
    }
    @Test fun localizedLatinTitleIsPreservedWithoutScriptHeuristicsOrExtraRequests() = runBlocking {
        MockWebServer().use { s -> s.start()
            s.enqueue(MockResponse().setBody("""{"id":1,"title":"BBC","original_title":"BBC","original_language":"ru","overview":"Русское описание"}"""))
            val api=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString()))
            val d=api.details(CatalogRef(1,CatalogType.Movie),"ru-RU")
            assertEquals("BBC",d.getString("title"));assertEquals(1,s.requestCount)
        }
    }
    @Test fun existingLocalizedFieldsAreNeverOverwrittenAndCountryFallbackUsesSameLanguage() = runBlocking {
        MockWebServer().use { s -> s.start()
            s.enqueue(MockResponse().setBody("""{"id":1,"title":"Локализованное название","original_title":"Original","overview":""}"""))
            s.enqueue(MockResponse().setBody("""{"translations":[{"iso_639_1":"ru","iso_3166_1":"RU","data":{"title":"Другое название","overview":""}},{"iso_639_1":"ru","iso_3166_1":"SU","data":{"overview":"Русский текст"}},{"iso_639_1":"en","iso_3166_1":"US","data":{"overview":"English text"}}]}"""))
            val d=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString())).details(CatalogRef(1,CatalogType.Movie),"ru-RU")
            assertEquals("Локализованное название",d.getString("title"));assertEquals("Русский текст",d.getString("overview"));assertEquals(2,s.requestCount)
        }
    }
    @Test fun trulyMissingTranslationUsesOriginalAndAvailableOverview() = runBlocking {
        MockWebServer().use { s -> s.start()
            s.enqueue(MockResponse().setBody("""{"id":1,"name":"","original_name":"Original series","original_language":"ja"}"""))
            s.enqueue(MockResponse().setBody("""{"translations":[{"iso_639_1":"ja","iso_3166_1":"JP","data":{"overview":"Original synopsis"}}]}"""))
            val d=CatalogParser.details(TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString())).details(CatalogRef(1,CatalogType.Tv),"ru-RU"),CatalogType.Tv)
            assertEquals("Original series",d.item.title);assertEquals("Original synopsis",d.overview);assertEquals(2,s.requestCount)
        }
    }
    @Test fun allShelfAndSearchRequestsIncludeRequestedLocaleWithoutTranslationFanout() = runBlocking {
        MockWebServer().use { s -> s.start()
            val api=TmdbCatalogApi(TmdbClient(Http(),{"test-key"},s.url("/3/").toString()))
            for (language in listOf("ru-RU","en-US")) {
                for(section in CatalogSection.entries) {
                    s.enqueue(MockResponse().setBody("{\"results\":[]}"));api.section(section,language,1)
                    assertEquals(language,s.takeRequest().requestUrl!!.queryParameter("language"))
                }
                s.enqueue(MockResponse().setBody("{\"results\":[]}"));api.search("Title",language,1)
                assertEquals(language,s.takeRequest().requestUrl!!.queryParameter("language"))
            }
            assertEquals(14,s.requestCount)
        }
    }
    @Test fun switchingBackToRussianReusesLanguageCacheWithoutClearingOtherLocales() = runBlocking {
        val api=TestRemote();val cache=MemoryCatalogCache();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=CatalogRepositoryImpl(api,cache,scope,{cache.clock})
            for(language in listOf("ru-RU","en-US","ru-RU")) {
                repo.section(CatalogSection.PopularMovies,language).toList()
                repo.search("Title",language).toList()
                repo.details(CatalogRef(1,CatalogType.Movie),language).toList()
            }
            assertEquals(4,api.calls);assertEquals(6,cache.entries.size)
            assertTrue(cache.entries.keys.any { it.endsWith("ru-RU") });assertTrue(cache.entries.keys.any { it.endsWith("en-US") })
        } finally { scope.cancel() }
    }
    @Test fun rateLimitHonorsRetryAfterAndBoundedBackoff() = runBlocking {
        MockWebServer().use { s -> s.start(); repeat(3) { s.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","2")) }
            var clock=0L; val waits=mutableListOf<Long>()
            val client=TmdbClient(Http(),{"test-key"},s.url("/3/").toString(),{clock},{ waits+=it;clock+=it },{0})
            try { client.get("movie/popular", emptyMap()); fail("Must stop after bounded retries") } catch(e:ServiceFailure) { assertEquals(429,e.status) }
            assertEquals(3,s.requestCount); assertEquals(listOf(2000L,2000L),waits)
            s.enqueue(MockResponse().setBody("{}"));client.get("tv/popular",emptyMap()); assertEquals(4000L,waits.last())
        }
    }
    @Test fun longRetryAfterPreventsImmediateRetryAcrossOperations() = runBlocking {
        MockWebServer().use { s -> s.start(); s.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
            val client=TmdbClient(Http(),{"test-key"},s.url("/3/").toString())
            repeat(2) { try { client.get("movie/popular", emptyMap());fail() } catch(e:ServiceFailure) { assertEquals(429,e.status) } }
            assertEquals(1,s.requestCount)
        }
    }
    @Test fun retryAfterAcceptsHttpDates() {
        assertEquals(10_000L,retryAfterMillis("Thu, 01 Jan 1970 00:00:10 GMT",0)); assertEquals(0L,retryAfterMillis("-1"));assertNull(retryAfterMillis("bad"))
    }
    @Test fun cacheIsShownFirstFreshCacheMakesNoRequestAndStaleOfflineSurvives() = runBlocking {
        val cache=MemoryCatalogCache();val api=TestRemote();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=CatalogRepositoryImpl(api,cache,scope,{cache.clock})
            val first=repo.section(CatalogSection.TrendingMovies,"ru-RU").toList();assertTrue(first.first().loading);assertEquals(1,api.calls)
            val second=repo.section(CatalogSection.TrendingMovies,"ru-RU").toList();assertEquals(1,second.size);assertEquals(1,api.calls)
            cache.clock+=13*CatalogRepositoryImpl.HOUR;api.fail=true
            val stale=repo.section(CatalogSection.TrendingMovies,"ru-RU").toList()
            assertTrue(stale.first().stale);assertTrue(stale.first().loading);assertEquals(stale.first().data,stale.last().data);assertTrue(stale.last().error)
        } finally { scope.cancel() }
    }
    @Test fun concurrentRequestsCoalesceAndLanguageHasItsOwnCache() = runBlocking {
        val api=TestRemote().apply { wait=50 };val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=CatalogRepositoryImpl(api,MemoryCatalogCache(),scope)
            val a=async { repo.search("Interstellar","ru-RU").toList() };val b=async { repo.search("Interstellar","ru-RU").toList() }
            a.await();b.await();assertEquals(1,api.calls)
            repo.search("Interstellar","en-US").toList();assertEquals(2,api.calls)
        } finally { scope.cancel() }
    }
    @Test fun cancellingLastCollectorCancelsNetworkWork() = runBlocking {
        val api=TestRemote().apply { wait=60_000 };val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=CatalogRepositoryImpl(api,MemoryCatalogCache(),scope)
            val job=launch { repo.search("Old query","en-US").toList() }
            while(api.calls==0) delay(5);job.cancelAndJoin()
            withTimeout(1000) { while(!api.cancelled) delay(5) };assertTrue(api.cancelled)
        } finally { scope.cancel() }
    }
    @Test fun diskFailureStillDisplaysSuccessfulNetworkData() = runBlocking {
        val cache = object : CatalogCache {
            override suspend fun read(key: String): CatalogCache.Entry? = throw IllegalStateException("disk unavailable")
            override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) { throw IllegalStateException("disk full") }
            override suspend fun remove(key: String) {}
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val result=CatalogRepositoryImpl(TestRemote(),cache,scope).section(CatalogSection.PopularMovies,"en-US").toList().last()
            assertFalse(result.error);assertEquals(1,result.data!!.items.size)
        } finally { scope.cancel() }
    }
    @Test fun malformedCacheIsDiscardedAndTtlRetentionAreBounded() = runBlocking {
        val cache=MemoryCatalogCache();val api=TestRemote();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=CatalogRepositoryImpl(api,cache,scope,{cache.clock})
            repo.details(CatalogRef(1,CatalogType.Movie),"en-US").toList()
            val key="details:v2:movie:1:en-US";assertEquals(3*CatalogRepositoryImpl.DAY,cache.entries.getValue(key).expiresAt-cache.clock)
            assertEquals(7*CatalogRepositoryImpl.DAY,cache.retention[key])
            cache.entries[key]=CatalogCache.Entry("bad json",cache.clock,Long.MAX_VALUE)
            val result=repo.details(CatalogRef(1,CatalogType.Movie),"en-US").toList();assertNotNull(result.last().data)
        } finally { scope.cancel() }
    }
}
