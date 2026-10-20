package tv.torrframe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.features.catalog.domain.CatalogRef
import tv.torrframe.features.catalog.domain.CatalogType

/** Explicit live test against the movie reported on Mi Box; preserves settings and user lists. */
@RunWith(AndroidJUnit4::class)
class CatalogImagesLiveTest {
    @Test fun avatarThumbnailPosterAndBackdropLoadAndStayCached() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TorrFrameApplication>()
        val graph = app.graph
        graph.ready.await()
        withTimeout(90_000) {
            val detail = graph.catalog.details(CatalogRef(980431, CatalogType.Movie), "en-US").last().data
            assertNotNull("Avatar movie metadata must be available", detail)
            val item = detail!!.item
            val urls = listOf(item.poster, item.poster.replace("/t/p/w342/", "/t/p/w500/"),
                item.backdrop.replace("/t/p/w780/", "/t/p/w1280/"))
            assertTrue(urls.all { it.isNotBlank() })
            val loader = SingletonImageLoader.get(app)
            for ((index, url) in urls.withIndex()) {
                val request = ImageRequest.Builder(app).data(url).size(1280, 1280).build()
                // Force a real transfer even when an earlier run already populated the cache.
                val first = loader.execute(request.newBuilder()
                    .memoryCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
                    .diskCachePolicy(coil3.request.CachePolicy.WRITE_ONLY).build())
                assertTrue("Image $index failed: ${(first as? coil3.request.ErrorResult)?.throwable?.javaClass?.simpleName}", first is SuccessResult)
                assertEquals(coil3.decode.DataSource.NETWORK, (first as SuccessResult).dataSource)
                val second = loader.execute(request)
                assertTrue(second is SuccessResult)
                assertTrue("Image $index should be cached", (second as SuccessResult).dataSource != coil3.decode.DataSource.NETWORK)
                android.util.Log.i("CatalogImagesLive", "image=$index loaded cached=${second.dataSource}")
            }
        }
    }
}
