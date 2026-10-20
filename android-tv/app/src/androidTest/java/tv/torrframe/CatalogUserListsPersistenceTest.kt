package tv.torrframe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.features.catalog.data.SqliteCatalogUserLists
import tv.torrframe.features.catalog.data.SqliteCatalogCache
import tv.torrframe.features.catalog.domain.*

@RunWith(AndroidJUnit4::class)
class CatalogUserListsPersistenceTest {
    @Test fun reopenRetainsBothListsTypesAndLocalesAndRemovingOneDoesNotRemoveTheOther() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="user-lists-test-${System.nanoTime()}.sqlite"
        val movie=CatalogItem(CatalogRef(1,CatalogType.Movie),"Фильм","Original",poster="poster",date="2024-01-01",rating=8.5,votes=10)
        val tv=movie.copy(ref=CatalogRef(1,CatalogType.Tv),title="Сериал")
        try {
            SqliteCatalogUserLists(context,name).use {
                it.load();it.toggle(movie,"ru-RU",CatalogUserList.Favorites)
                it.toggle(movie,"ru-RU",CatalogUserList.WatchLater);it.toggle(tv,"ru-RU",CatalogUserList.Favorites)
                it.localize(movie.copy(title="Movie"),"en-US")
            }
            SqliteCatalogUserLists(context,name).use {
                it.load();assertEquals(2,it.entries.value.size)
                val saved=it.entries.value.first { entry -> entry.ref==movie.ref }
                assertTrue(saved.favorite);assertTrue(saved.watchLater)
                assertEquals("Фильм",saved.item("ru-RU").title);assertEquals("Movie",saved.item("en-US").title)
                it.toggle(movie,"ru-RU",CatalogUserList.Favorites)
            }
            SqliteCatalogUserLists(context,name).use {
                it.load();val saved=it.entries.value.first { entry -> entry.ref==movie.ref }
                assertFalse(saved.favorite);assertTrue(saved.watchLater)
                it.toggle(movie,"ru-RU",CatalogUserList.WatchLater)
                it.localize(movie,"ru-RU")
                assertEquals(listOf(tv.ref),it.entries.value.map { entry -> entry.ref })
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun expiringAndRemovingTmdbCacheCannotEraseUserLists() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="lists-independent-${System.nanoTime()}.sqlite";val cacheName="list-cache-${System.nanoTime()}.sqlite"
        val item=CatalogItem(CatalogRef(100,CatalogType.Movie),"Saved film")
        var now=100L
        try {
            SqliteCatalogUserLists(context,name).use { it.toggle(item,"en-US",CatalogUserList.Favorites) }
            SqliteCatalogCache(context,cacheName){now}.use {
                it.write("film","{}",10,20);now+=30;assertNull(it.read("film"));it.remove("film")
            }
            context.deleteDatabase(cacheName)
            SqliteCatalogUserLists(context,name).use { it.load();assertEquals(item,it.entries.value.single().item("en-US")) }
        } finally { context.deleteDatabase(name);context.deleteDatabase(cacheName) }
    }
}
