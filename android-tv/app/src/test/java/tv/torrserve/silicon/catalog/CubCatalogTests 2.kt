package tv.torrserve.silicon.catalog

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.features.catalog.data.*
import tv.torrserve.silicon.features.catalog.domain.*
import java.util.concurrent.CopyOnWriteArrayList

class CubCatalogTests {
    private val config = CatalogNetworkSettings(source = CatalogSource.Cub, cubUrl = "https://catalog.example/base/", cubToken = "issued-token")
    private fun item(title: String, tv: Boolean = false) = JSONObject().put("id", 42).put("title", title)
        .put("original_title", title).put("original_language", "en").put("release_date", "2024-01-02")
        .put("poster_path", "/image.jpg").put("overview", "Overview").put("vote_average", 8.0)
        .apply { if (tv) put("media_type", "tv") }
    private fun page(title: String, tv: Boolean = false) = JSONObject().put("page", 1).put("total_pages", 2)
        .put("results", JSONArray().put(item(title, tv)))
    private fun http(requests: MutableList<Request>, respond: (Request) -> Pair<Int, String>) = Http(OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            val (code, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build())

    @Test fun cubSearchCombinesMoviesAndTvWithoutCollidingTheirTmdbIds() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val api = CubCatalogApi(http(requests) { r -> 200 to page(if (r.url.encodedPath.endsWith("/tv")) "Series" else "Movie").toString() }, config) { "test-key" }
        val result = CatalogParser.page(api.search("A & B", "en-US", 2))
        assertEquals(listOf("movie:42", "tv:42"), result.items.map { it.ref.key })
        assertEquals(listOf("Movie", "Series"), result.items.map { it.title })
        assertEquals(2, result.page); assertEquals(2, result.totalPages)
        assertEquals(setOf("/base/search/movie", "/base/search/tv"), requests.map { it.url.encodedPath }.toSet())
        for (r in requests) {
            assertEquals("issued-token", r.header("token"))
            assertEquals("A & B", r.url.queryParameter("query")); assertNull(r.url.queryParameter("language"))
            assertNull(r.url.queryParameter("token")); assertNull(r.url.queryParameter("api_key"))
        }
    }
    @Test fun cubSectionsAndGenresUseCatalogRoutesInsteadOfAccountApi() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val api = CubCatalogApi(http(requests) { 200 to page("Title").toString() }, config) { "test-key" }
        api.section(CatalogSection.NewMovies, "ru-RU", 1)
        api.section(CatalogSection.TrendingTv, "en-US", 3)
        api.discover(CatalogFilter.Comedy, CatalogType.Movie, "ru-RU", 2)
        assertEquals(listOf("latest", "now_playing", "top"), requests.map { it.url.queryParameter("sort") })
        assertEquals(listOf("movie", "tv", "movie"), requests.map { it.url.queryParameter("cat") })
        assertEquals("35", requests.last().url.queryParameter("genre"))
        assertTrue(requests.all { it.url.encodedPath == "/base/" })
    }
    @Test fun detailsAndRelatedUseNormalTmdbRoutingAndNeverForwardTheCubToken() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val api = CubCatalogApi(http(requests) { r -> 200 to if (r.url.encodedPath.endsWith("/similar")) page("Other").toString()
            else item("Movie").put("credits", JSONObject().put("cast", JSONArray().put(JSONObject().put("name", "Actor")))).toString() }, config) { "test-key" }
        val detail = CatalogParser.details(api.details(CatalogRef(42, CatalogType.Movie), "en-US"), CatalogType.Movie)
        api.related(CatalogRef(42, CatalogType.Movie), CatalogRelatedSource.Similar, "en-US", 2)
        assertEquals(listOf("Actor"), detail.cast)
        assertEquals(listOf("/3/movie/42", "/3/movie/42/similar"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.url.host == "api.themoviedb.org" && it.header("token") == null })
        assertEquals("test-key", requests.first().url.queryParameter("api_key"))
    }
    @Test fun malformedAndDeniedResponsesDoNotBecomeEmptySuccessfulCatalogs() = runBlocking {
        for ((code, body) in listOf(403 to "denied", 200 to "<html>login</html>", 200 to "{}", 200 to "{\"secuses\":false}",
            200 to "{\"status_code\":7}", 302 to "redirect")) {
            val requests = CopyOnWriteArrayList<Request>()
            val api = CubCatalogApi(http(requests) { code to body }, config) { "test-key" }
            try { api.section(CatalogSection.PopularMovies, "en-US", 1); fail("Invalid service response accepted") }
            catch (e: ServiceFailure) { assertFalse(e.message.orEmpty().contains("issued-token")) }
            assertEquals(1, requests.size)
        }
    }
    @Test fun switchingSourceAndCredentialsUsesSeparateCachesAndCanReturnToTmdb() = runBlocking {
        var settings = CatalogNetworkSettings()
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { r -> 200 to page(if (r.url.host == "catalog.example") "CUB" else "TMDB").toString() }
        val entries = linkedMapOf<String, CatalogCache.Entry>()
        val cache = object : CatalogCache {
            override suspend fun read(key: String) = entries[key]
            override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) { entries[key] = CatalogCache.Entry(payload, 1, Long.MAX_VALUE) }
            override suspend fun remove(key: String) { entries.remove(key) }
        }
        val repository = ConfiguredCatalogRepository({ settings }, transport, TmdbClient(transport, { "test-key" }), cache, this)
        suspend fun title() = repository.section(CatalogSection.PopularMovies, "en-US").toList().last().data!!.items.single().title
        assertEquals("TMDB", title())
        settings = config; assertEquals("CUB", title())
        settings = config.copy(cubToken = "new-token"); assertEquals("CUB", title())
        settings = CatalogNetworkSettings(); assertEquals("TMDB", title())
        assertEquals(3, requests.size); assertEquals(3, entries.size)
        assertTrue(entries.keys.none { it.contains("issued-token") || it.contains("new-token") })
    }
    @Test fun publicCubSearchIsKeylessAndDoesNotSendTranslationFilters() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val api = CubCatalogApi(http(requests) { 200 to page("Матрица").toString() }, CatalogNetworkSettings(source = CatalogSource.Cub))
        api.search("Матрица", "ru-RU", 1)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "tmdb.cub.best" && it.header("token") == null &&
            it.url.queryParameter("api_key") == null && it.url.queryParameter("language") == null })
    }
    @Test fun automaticFallbackPinsPaginationThenAllowsTmdbRecoveryOnRefresh() = runBlocking { kotlinx.coroutines.supervisorScope {
        var failing = true
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { r ->
            if (r.url.host == "api.themoviedb.org" && failing) 503 to "unavailable"
            else 200 to page(if (r.url.host == "api.themoviedb.org") "TMDB" else "CUB").toString()
        }
        val repository = ConfiguredCatalogRepository({ CatalogNetworkSettings(source = CatalogSource.Auto) },
            transport, TmdbClient(transport, { "test-key" }), emptyCache(), this)
        suspend fun title(page: Int = 1) = repository.section(CatalogSection.PopularMovies, "ru-RU", page, true)
            .toList().last().data!!.items.single().title
        assertEquals("CUB", title())
        assertEquals(listOf("api.themoviedb.org", "tmdb.cub.best"), requests.map { it.url.host })
        failing = false
        assertEquals("CUB", title(2))
        assertEquals("tmdb.cub.best", requests.last().url.host)
        assertEquals("TMDB", title())
        assertEquals("TMDB", title(2))
        assertEquals("api.themoviedb.org", requests.last().url.host)
    } }
    @Test fun automaticModeDoesNotTreatSuccessfulEmptySearchAsAnOutage() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { 200 to "{\"page\":1,\"total_pages\":0,\"results\":[]}" }
        val repository = ConfiguredCatalogRepository({ CatalogNetworkSettings(source = CatalogSource.Auto) },
            transport, TmdbClient(transport, { "test-key" }), emptyCache(), this)
        val result = repository.search("Nothing", "en-US").toList().last()
        assertFalse(result.error); assertTrue(result.data!!.items.isEmpty())
        assertEquals(listOf("api.themoviedb.org"), requests.map { it.url.host })
    }
    @Test fun automaticFailureKeepsCachedTmdbDataIfCubAlsoFails() = runBlocking { kotlinx.coroutines.supervisorScope {
        var failing = false
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { if (failing) 503 to "unavailable" else 200 to page("Saved").toString() }
        val entries = mutableMapOf<String, CatalogCache.Entry>()
        val cache = object : CatalogCache {
            override suspend fun read(key: String) = entries[key]
            override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) { entries[key] = CatalogCache.Entry(payload, 1, Long.MAX_VALUE) }
            override suspend fun remove(key: String) { entries.remove(key) }
        }
        val repository = ConfiguredCatalogRepository({ CatalogNetworkSettings(source = CatalogSource.Auto) },
            transport, TmdbClient(transport, { "test-key" }), cache, this)
        repository.section(CatalogSection.PopularMovies, "en-US").toList()
        failing = true
        val last = repository.section(CatalogSection.PopularMovies, "en-US", force = true).toList().last()
        assertTrue(last.error); assertTrue(last.stale); assertEquals("Saved", last.data!!.items.single().title)
    } }
    private fun emptyCache() = object : CatalogCache {
        override suspend fun read(key: String): CatalogCache.Entry? = null
        override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) = Unit
        override suspend fun remove(key: String) = Unit
    }

}
