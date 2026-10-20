package tv.torrserve.silicon

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.silicon.phoneRemote.api.RemoteJson
import tv.torrserve.silicon.phoneRemote.api.RemoteSettings
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class CatalogNetworkTests {
    private val api = "https://api.themoviedb.org/3/search/multi?query=a%20b&api_key=app-key"
    private val image = "https://image.tmdb.org/t/p/w342/poster.jpg"
    private val config = CatalogNetworkSettings(CatalogRoute.Auto, "https://gateway.example/tmdb/3/",
        listOf("https://mirror-one.example/img/", "https://mirror-two.example/"))
    private fun response(request: Request, status: Int = 200, body: String = "{}") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()

    @Test fun defaultsAreTmdbAndPublicCubNeedsNoCredentials() {
        assertEquals(CatalogRoute.Direct, CatalogNetworkSettings().route)
        assertEquals(listOf(api.toHttpUrl()), CatalogRouteInterceptor.destinations(api.toHttpUrl(), CatalogNetworkSettings()))
        assertEquals(CatalogSource.Tmdb, CatalogNetworkSettings().source)
        assertEquals(CatalogNetworkSettings.PUBLIC_CUB_URL, CatalogNetworkSettings(source = CatalogSource.Cub).validated().effectiveCubUrl)
        for (value in listOf(config.copy(route = CatalogRoute.Proxy, apiProxy = ""),
            config.copy(route = CatalogRoute.Proxy, imageMirrors = emptyList()))) {
            try { value.validated(); fail("Incomplete service activated") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun endpointsRequireHttpsAndDoNotAcceptEmbeddedSecretsOrUnlimitedMirrors() {
        for (url in listOf("http://host/", "https://user:password@host/", "https://host/?token=secret", "https://host/#token", "bad")) {
            try { config.copy(apiProxy = url).validated(); fail("Unsafe endpoint accepted") } catch (_: IllegalArgumentException) { }
        }
        try { config.copy(imageMirrors = List(4) { "https://m$it.example/" }).validated(); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun routesPreserveEncodedQueriesAndBasePathsAndNeverRewriteLocalServers() {
        val urls = CatalogRouteInterceptor.destinations(api.toHttpUrl(), config)
        assertEquals(api, urls[0].toString())
        assertEquals("https://gateway.example/tmdb/3/search/multi?query=a%20b&api_key=app-key", urls[1].toString())
        assertEquals(listOf(image, "https://mirror-one.example/img/t/p/w342/poster.jpg", "https://mirror-two.example/t/p/w342/poster.jpg") + CatalogNetworkSettings.PUBLIC_IMAGE_MIRRORS.map { it + "t/p/w342/poster.jpg" },
            CatalogRouteInterceptor.destinations(image.toHttpUrl(), config).map { it.toString() })
        val local = "http://192.168.1.10:8090/stream".toHttpUrl()
        assertEquals(listOf(local), CatalogRouteInterceptor.destinations(local, config))
    }
    @Test fun healthyDirectRouteNeverContactsProxy() {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ config }))
            .addInterceptor { chain -> requests += chain.request(); response(chain.request()) }.build()
        client.newCall(Request.Builder().url(api).build()).execute().close()
        assertEquals(listOf("api.themoviedb.org"), requests.map { it.url.host })
    }
    @Test fun failuresAdvanceMirrorsAndFailedHostsHaveABoundedCooldown() {
        var time = 0L
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ config }, { time }))
            .addInterceptor { chain ->
                requests += chain.request()
                if (chain.request().url.host == "image.tmdb.org") throw IOException("network")
                response(chain.request(), if (chain.request().url.host == "mirror-one.example") 503 else 200)
            }.build()
        fun fetch() = client.newCall(Request.Builder().url(image).build()).execute().use { assertEquals(200, it.code) }
        fetch(); fetch()
        assertEquals(listOf("image.tmdb.org", "mirror-one.example", "mirror-two.example", "mirror-two.example"), requests.map { it.url.host })
        time = 30_000; fetch()
        assertEquals(7, requests.size)
    }
    @Test fun authenticationRateLimitAndMissingContentDoNotTriggerFallback() {
        for (code in listOf(401, 404, 429)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ config }))
                .addInterceptor { chain -> calls++; response(chain.request(), code) }.build()
            client.newCall(Request.Builder().url(api).build()).execute().use { assertEquals(code, it.code) }
            assertEquals(1, calls)
        }
    }
    @Test fun proxyOnlyDoesNotContactOriginAndDropsCrossHostCredentials() {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ config.copy(route = CatalogRoute.Proxy) }))
            .addInterceptor { chain -> requests += chain.request(); response(chain.request()) }.build()
        client.newCall(Request.Builder().url(api).header("Authorization", "Bearer private").header("Cookie", "session=private")
            .header("token", "private").build()).execute().close()
        assertEquals("gateway.example", requests.single().url.host)
        for (header in listOf("Authorization", "Cookie", "token")) assertNull(requests.single().header(header))
        assertEquals("app-key", requests.single().url.queryParameter("api_key"))
    }
    @Test fun cancelledCallDoesNotStartAnotherRoute() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ config }))
            .addInterceptor { chain -> calls++; chain.call().cancel(); throw IOException("cancelled") }.build()
        try { client.newCall(Request.Builder().url(api).build()).execute(); fail() } catch (_: IOException) { }
        assertEquals(1, calls)
    }
    @Test fun phoneAndStorageNeverReturnTheCubTokenAndPartialUpdatesKeepIt() {
        val base = Settings(catalogNetwork = config.copy(cubUrl = "https://catalog.example/", cubToken = "private-access"))
        assertFalse(base.catalogNetwork.encode().contains("private-access"))
        val snapshot = RemoteJson.settings(base, 1).getJSONObject("catalogNetwork")
        assertTrue(snapshot.getBoolean("cubTokenConfigured")); assertFalse(snapshot.has("cubToken"))
        assertFalse(snapshot.toString().contains("private-access"))
        val changed = RemoteSettings.edit(base, JSONObject().put("catalogNetwork", JSONObject().put("source", "Cub")))
        assertEquals("private-access", changed.catalogNetwork.cubToken)
        assertEquals(CatalogSource.Cub, changed.catalogNetwork.source)
        val cleared = RemoteSettings.edit(changed, JSONObject().put("catalogNetwork", JSONObject().put("source", "Tmdb").put("cubToken", "")))
        assertEquals("", cleared.catalogNetwork.cubToken)
        assertEquals("private-access", CatalogNetworkSettings.decode(base.catalogNetwork.encode(), "private-access").cubToken)
    }
    @Test fun phoneRejectsMalformedNetworkSettingsRatherThanSilentlyResettingThem() {
        for (value in listOf(JSONObject().put("unknown", true), JSONObject().put("cubToken", "secret\nheader"),
            JSONObject().put("showFavorites", "false"), JSONObject().put("route", "Invalid"),
            JSONObject().put("imageMirrors", JSONArray(listOf("http://insecure/"))))) {
            try { RemoteSettings.edit(Settings(), JSONObject().put("catalogNetwork", value)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun tvRebaseRetainsPhoneNetworkChangesWithoutOverwritingUnsavedEdits() {
        val base = Settings()
        val draft = base.copy(catalogNetwork = base.catalogNetwork.copy(apiProxy = config.apiProxy))
        val current = base.copy(catalogNetwork = base.catalogNetwork.copy(imageMirrors = config.imageMirrors), uiAccent = "white")
        val merged = SettingsMerge.rebase(draft, base, current)
        assertEquals(config.apiProxy, merged.catalogNetwork.apiProxy)
        assertEquals(config.imageMirrors, merged.catalogNetwork.imageMirrors)
        assertEquals("white", merged.uiAccent)
    }
    @Test fun homeRowsRoundTripAndConcurrentPhoneAndTvEditsRemainIndependent() {
        val base = Settings()
        val draft = base.copy(catalogNetwork = base.catalogNetwork.copy(showFavorites = false))
        val current = RemoteSettings.edit(base, JSONObject().put("catalogNetwork", JSONObject().put("source", "Auto").put("showWatchLater", false)))
        val merged = SettingsMerge.rebase(draft, base, current).catalogNetwork
        assertEquals(CatalogSource.Auto, merged.source)
        assertFalse(merged.showFavorites); assertFalse(merged.showWatchLater)
        assertEquals(merged, CatalogNetworkSettings.decode(merged.encode()))
        assertEquals(base.catalogNetwork.connection(), draft.catalogNetwork.connection())
        val snapshot = RemoteJson.settings(Settings(catalogNetwork = merged), 1).getJSONObject("catalogNetwork")
        assertFalse(snapshot.getBoolean("showFavorites")); assertFalse(snapshot.getBoolean("showWatchLater"))
    }
    @Test fun posterFallbackIsIndependentOfCatalogSource() {
        for (source in CatalogSource.entries) {
            val urls = CatalogRouteInterceptor.destinations(image.toHttpUrl(), CatalogNetworkSettings(source = source))
            assertEquals(listOf(image) + CatalogNetworkSettings.PUBLIC_IMAGE_MIRRORS.map { it + "t/p/w342/poster.jpg" }, urls.map { it.toString() })
        }
        assertEquals(CatalogSource.Tmdb, CatalogNetworkSettings().source)
    }

    @Test fun blockedPublicPostersTryTheMirrorWithoutForwardingCredentials() {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({ CatalogNetworkSettings(source = CatalogSource.Auto) }))
            .addInterceptor { chain ->
                requests += chain.request()
                response(chain.request(), if (chain.request().url.host == "image.tmdb.org") 403 else 200)
            }.build()
        client.newCall(Request.Builder().url(image).header("Cookie", "private").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(listOf("image.tmdb.org", "imagetmdb.com"), requests.map { it.url.host })
        assertNull(requests.last().header("Cookie"))
    }


    @Test fun deniedOrHtmlApiOriginFallsBackToConfiguredProxy() {
        for(code in listOf(403,200)) {
            val requests=CopyOnWriteArrayList<Request>()
            val client=OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({config})).addInterceptor { chain ->
                val r=chain.request();requests+=r
                if(r.url.host=="api.themoviedb.org")response(r,code,"<html>blocked</html>") else response(r,200,"{\"id\":42}")
            }.build()
            client.newCall(Request.Builder().url(api).build()).execute().use { assertEquals("{\"id\":42}",it.body.string()) }
            assertEquals(listOf("api.themoviedb.org","gateway.example"),requests.map {it.url.host})
        }
    }
    @Test fun stalledApiBodyLeavesTimeForProxyAndDoesNotWaitForGlobalTimeout() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"id\":1}").setBodyDelay(600,java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"id\":42}"))
            val client=OkHttpClient.Builder().addInterceptor(CatalogRouteInterceptor({config},apiReadTimeoutMs=150))
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
            client.newCall(Request.Builder().url(api).build()).execute().use { assertEquals("{\"id\":42}",it.body.string()) }
            assertEquals(2,server.requestCount)
        }
    }

}
