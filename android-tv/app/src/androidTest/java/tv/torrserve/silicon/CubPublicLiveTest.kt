package tv.torrserve.silicon

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.network.CatalogNetworkSettings
import tv.torrserve.silicon.core.network.CatalogSource
import tv.torrserve.silicon.features.catalog.data.CubCatalogApi
import tv.torrserve.silicon.features.catalog.domain.*

/** Explicit live check, run separately; no settings, keys or user lists are modified. */
@RunWith(AndroidJUnit4::class)
class CubPublicLiveTest {
    @Test fun publicCatalogSearchAndPosterWorkFromAndroidWithoutCubCredentials() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<TorrFrameApplication>().graph
        graph.ready.await()
        withTimeout(45_000) {
            val api = CubCatalogApi(graph.http, CatalogNetworkSettings(source = CatalogSource.Cub))
            val movies = CatalogParser.page(api.section(CatalogSection.TrendingMovies, "ru-RU", 1), CatalogType.Movie)
            val tv = CatalogParser.page(api.section(CatalogSection.TrendingTv, "ru-RU", 1), CatalogType.Tv)
            assertTrue(movies.items.isNotEmpty()); assertTrue(tv.items.isNotEmpty())
            val found = CatalogParser.page(api.search("Матрица", "ru-RU", 1))
            assertTrue(found.items.any { it.ref == CatalogRef(603, CatalogType.Movie) })
            val poster = movies.items.first { it.poster.isNotBlank() }.poster
                .replace("https://image.tmdb.org/", CatalogNetworkSettings.PUBLIC_IMAGE_MIRROR)
            val image = graph.http.bytes(Request.Builder().url(poster).build(), "CUB poster", 1024 * 1024)
            assertTrue(image.size > 1000)
            assertTrue(image[0] == 0xff.toByte() && image[1] == 0xd8.toByte())
            android.util.Log.i("CubPublicLive", "movies=${movies.items.size}, tv=${tv.items.size}, search=${found.items.size}, poster_bytes=${image.size}")
            Unit
        }
    }
}
