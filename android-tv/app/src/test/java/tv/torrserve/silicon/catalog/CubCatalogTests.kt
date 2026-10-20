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
    @Test fun publicCubClosesEachTransportWhileCustomCatalogAndTmdbKeepTheirDefaults() = runBlocking {
        val requests=CopyOnWriteArrayList<Request>()
        val transport=Http(OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(page("Title").toString().toResponseBody()).build()
        }.build())
        val public=CubCatalogApi(transport,CatalogNetworkSettings(source=CatalogSource.Cub)) { "test-key" }
        for(section in CatalogSection.entries) public.section(section,"en-US",1)
        public.search("Title","en-US",1)
        assertEquals(8,requests.size)
        assertTrue(requests.all { it.header("Connection")=="close" })
        CubCatalogApi(transport,config).section(CatalogSection.PopularMovies,"en-US",1)
        public.related(CatalogRef(42,CatalogType.Movie),CatalogRelatedSource.Similar,"en-US",1)
        assertTrue(requests.takeLast(2).all { it.header("Connection")==null })
        assertTrue(Protocol.HTTP_2 in transport.client.protocols)
    }
    @Test fun cubRetriesTransientFailuresOnceButDoesNotRetryAccessDenialOrRateLimit() = runBlocking {
        for (status in listOf(408,502,503,504,403,429)) {
            val requests=CopyOnWriteArrayList<Request>()
            val api=CubCatalogApi(http(requests) { if (requests.size == 1) status to "failure" else 200 to page("Recovered").toString() },config)
            if (status in setOf(403,429)) {
                try { api.section(CatalogSection.PopularMovies,"en-US",1);fail() } catch(_:ServiceFailure) { }
                assertEquals(1,requests.size)
            } else {
                assertEquals("Recovered",CatalogParser.page(api.section(CatalogSection.PopularMovies,"en-US",1),CatalogType.Movie).items.single().title)
                assertEquals(2,requests.size)
            }
        }
        val requests=CopyOnWriteArrayList<Request>()
        val api=CubCatalogApi(http(requests) { 503 to "still unavailable" },config)
        try { api.section(CatalogSection.PopularMovies,"en-US",1);fail() } catch(_:ServiceFailure) { }
        assertEquals(2,requests.size)
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
        assertEquals(CatalogOrigin.Tmdb, last.source)
        assertEquals(CatalogOrigin.Cub, last.failure!!.provider)
    } }
    @Test fun cubBrowsePreservesBasicCardDuringTmdbOutageAndFullDetailsRecover() = runBlocking { kotlinx.coroutines.supervisorScope {
        var failing = true
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { r ->
            when {
                r.url.host == "catalog.example" -> 200 to page("Saved CUB title").toString()
                failing -> 503 to "unavailable"
                else -> 200 to item("Full TMDB title").put("credits", JSONObject()
                    .put("cast", JSONArray().put(JSONObject().put("name", "Actor")))).toString()
            }
        }
        val repository = ConfiguredCatalogRepository({ config }, transport, TmdbClient(transport, { "test-key" }), emptyCache(), this)
        val selected = repository.section(CatalogSection.PopularMovies, "en-US").toList().last().data!!.items.single()
        val loads = repository.details(selected.ref, "en-US").toList()
        assertTrue(loads.first().loading); assertEquals(selected, loads.first().data!!.item)
        val failed = loads.last()
        assertTrue(failed.error); assertTrue(failed.stale); assertEquals(selected, failed.data!!.item)
        assertEquals(CatalogOrigin.Cub, failed.source)
        assertEquals(CatalogOrigin.Tmdb, failed.failure!!.provider)
        assertEquals("", failed.data.overview); assertTrue(failed.data.cast.isEmpty())
        // Never label a saved English item as Russian data.
        assertNull(repository.details(selected.ref, "ru-RU").toList().last().data)
        failing = false
        val recovered = repository.details(selected.ref, "en-US", force = true).toList().last()
        assertFalse(recovered.error); assertFalse(recovered.stale)
        assertEquals(CatalogOrigin.Cub, recovered.source)
        assertEquals("Full TMDB title", recovered.data!!.item.title)
        assertEquals("Overview", recovered.data.overview); assertEquals(listOf("Actor"), recovered.data.cast)
    } }
    @Test fun configuredProxyServesFullDetailsAndRelatedForCubWithoutLeakingCubToken() = runBlocking { kotlinx.coroutines.supervisorScope {
        val requests = CopyOnWriteArrayList<Request>()
        val settings = config.copy(route = CatalogRoute.Auto, apiProxy = "https://gateway.example/3/")
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ settings })).addInterceptor { chain ->
            val request = chain.request(); requests += request
            if (request.url.host == "api.themoviedb.org") throw java.io.IOException("Direct route blocked")
            val body = if (request.url.encodedPath.endsWith("/similar")) page("Related").apply { getJSONArray("results").getJSONObject(0).put("id",43) }.toString()
                else item("Full details").put("credits", JSONObject().put("cast", JSONArray().put(JSONObject().put("name", "Actor")))).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val transport = Http(client)
        val repository = ConfiguredCatalogRepository({ settings }, transport, TmdbClient(transport, { "test-key" }), emptyCache(), this)
        val ref = CatalogRef(42, CatalogType.Movie)
        val details = repository.details(ref, "en-US").toList().last()
        val related = repository.related(ref, CatalogRelatedSource.Similar, "en-US").toList().last()
        assertFalse(details.error); assertEquals(listOf("Actor"), details.data!!.cast)
        assertFalse(related.error); assertEquals("Related", related.data!!.items.single().title)
        assertTrue(requests.any { it.url.host == "gateway.example" })
        assertTrue(requests.all { it.header("token") == null && it.url.queryParameter("api_key") == "test-key" })
    } }
    private fun emptyCache() = object : CatalogCache {
        override suspend fun read(key: String): CatalogCache.Entry? = null
        override suspend fun write(key: String, payload: String, ttl: Long, retention: Long) = Unit
        override suspend fun remove(key: String) = Unit
    }

    @Test fun cubFailuresKeepTheirActualReasonAndProvider() = runBlocking { kotlinx.coroutines.supervisorScope {
        val cases = listOf(
            java.net.UnknownHostException("secret.example?token=hidden") to CatalogFailureKind.Dns,
            java.net.SocketTimeoutException("hidden") to CatalogFailureKind.Timeout,
            javax.net.ssl.SSLHandshakeException("hidden") to CatalogFailureKind.Tls,
            ServiceFailure("CUB", 403) to CatalogFailureKind.Denied,
            ServiceFailure("CUB", 429) to CatalogFailureKind.RateLimited,
            ServiceFailure("CUB", 503) to CatalogFailureKind.Http,
            ServiceFailure("CUB", -2) to CatalogFailureKind.InvalidResponse,
        )
        for ((error, kind) in cases) {
            val transport = Http(OkHttpClient.Builder().addInterceptor { throw error }.build())
            val repository = ConfiguredCatalogRepository({ config }, transport, TmdbClient(transport, { "test" }), emptyCache(), this)
            val load = repository.section(CatalogSection.PopularMovies, "ru-RU").toList().last()
            assertTrue(load.error); assertEquals(CatalogOrigin.Cub, load.source)
            assertEquals(CatalogOrigin.Cub, load.failure!!.provider); assertEquals(kind, load.failure.kind)
            if (error is ServiceFailure && error.status > 0) assertEquals(error.status, load.failure.status)
            assertFalse(load.failure.toString().contains("hidden"))
        }
    } }

    @Test fun automaticFallbackRemembersCubOriginForDetailsEvenWhenTmdbRecovers() = runBlocking { kotlinx.coroutines.supervisorScope {
        val requests = CopyOnWriteArrayList<Request>()
        val transport = http(requests) { r -> when {
            r.url.encodedPath == "/3/movie/popular" -> 503 to "offline"
            r.url.host == "tmdb.cub.best" -> 200 to page("CUB title").toString()
            else -> 200 to item("TMDB details").toString()
        } }
        val repository = ConfiguredCatalogRepository({ CatalogNetworkSettings(source = CatalogSource.Auto) }, transport,
            TmdbClient(transport, { "test" }), emptyCache(), this)
        val page = repository.section(CatalogSection.PopularMovies, "en-US").toList().last()
        assertEquals(CatalogOrigin.Cub, page.source)
        val detail = repository.details(page.data!!.items.single().ref, "en-US").toList().last()
        assertEquals(CatalogOrigin.Cub, detail.source); assertFalse(detail.error)
        assertEquals("TMDB details", detail.data!!.item.title)
    } }

}
