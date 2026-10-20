package tv.torrserve.silicon

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            android.util.Log.i("CubPublicLive", "movie list loaded: ${movies.items.size}")
            val tv = CatalogParser.page(api.section(CatalogSection.TrendingTv, "ru-RU", 1), CatalogType.Tv)
            android.util.Log.i("CubPublicLive", "TV list loaded: ${tv.items.size}")
            assertTrue(movies.items.isNotEmpty()); assertTrue(tv.items.isNotEmpty())
            val found = CatalogParser.page(api.search("Матрица", "ru-RU", 1))
            android.util.Log.i("CubPublicLive", "search loaded: ${found.items.size}")
            assertTrue(found.items.any { it.ref == CatalogRef(603, CatalogType.Movie) })
            val poster = movies.items.first { it.poster.isNotBlank() }.poster
            // Exercise the same bounded mirror recovery as the app, without a warm cache hiding failures.
            val app=ApplicationProvider.getApplicationContext<TorrFrameApplication>()
            val image=coil3.SingletonImageLoader.get(app).execute(coil3.request.ImageRequest.Builder(app)
                .data(poster).size(342,513).memoryCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
                .diskCachePolicy(coil3.request.CachePolicy.WRITE_ONLY).build())
            assertTrue("Production poster routing failed",image is coil3.request.SuccessResult)
            assertEquals(coil3.decode.DataSource.NETWORK,(image as coil3.request.SuccessResult).dataSource)
            android.util.Log.i("CubPublicLive", "movies=${movies.items.size}, tv=${tv.items.size}, search=${found.items.size}, poster_network=${image.dataSource}")
            Unit
        }
    }
}
