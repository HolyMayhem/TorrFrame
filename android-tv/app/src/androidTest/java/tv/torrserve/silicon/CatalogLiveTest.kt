package tv.torrserve.silicon

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import tv.torrserve.silicon.features.catalog.domain.*

/** Explicit integration suite; requires an internet connection to TMDB. No keys in fixtures. */
@RunWith(AndroidJUnit4::class)
class CatalogLiveTest {
    @Test fun realGenreIdsAndMovieTvCollectionsAreAvailable() = runBlocking {
        val graph=ApplicationProvider.getApplicationContext<TorrServeApplication>().graph;graph.ready.await()
        withTimeout(45_000) {
            for(type in CatalogType.entries) {
                val genres=graph.tmdb.get("genre/${type.path}/list",mapOf("language" to "ru-RU")).getJSONArray("genres")
                val ids=(0 until genres.length()).map { genres.getJSONObject(it).getInt("id") }.toSet()
                for(filter in CatalogFilter.entries.filter { it.isGenre }) filter.genres(type)?.split('|')?.forEach {
                    assertTrue("${filter.name} has a TMDB ${type.path} genre",it.toInt() in ids)
                }
            }
            for(filter in listOf(CatalogFilter.Comedy,CatalogFilter.ScienceFictionFantasy,CatalogFilter.Horror)) {
                for(type in filter.genreTypes) {
                    val result=graph.catalog.discover(filter,type,"ru-RU",force=true).last()
                    assertFalse("${filter.name}/${type.path} network error",result.error)
                    val page=requireNotNull(result.data)
                    assertTrue(page.items.isNotEmpty());assertTrue(page.items.all { it.ref.type==type })
                }
            }
        }
    }
    @Test fun realShelvesRussianEnglishSearchAndMovieTvDetails() = runBlocking {
        val graph=ApplicationProvider.getApplicationContext<TorrServeApplication>().graph
        graph.ready.await()
        val initialServer = graph.server.state.value
        val initialSettings = graph.settings.state.value
        withTimeout(90_000) {
            for(section in CatalogSection.entries) {
                val page=graph.catalog.section(section,"ru-RU",force=true).last()
                assertFalse("${section.name} network error",page.error)
                assertTrue("${section.name} real titles",page.data!!.items.isNotEmpty())
                if(section==CatalogSection.NewMovies) assertTrue(page.data!!.items.all { it.date.isNotBlank() && it.date<=LocalDate.now().toString() })
            }
            for(query in listOf("Интерстеллар","Interstellar")) {
                val page=graph.catalog.search(query,"ru-RU",force=true).last()
                assertFalse(page.error);assertTrue(page.data!!.items.any { it.ref==CatalogRef(157336,CatalogType.Movie) })
            }
            for(ref in listOf(CatalogRef(157336,CatalogType.Movie),CatalogRef(1396,CatalogType.Tv))) {
                val detail=graph.catalog.details(ref,"ru-RU",true).last()
                assertFalse(detail.error);val d=detail.data!!
                assertTrue(d.item.title.isNotBlank());assertTrue(d.overview.isNotBlank());assertTrue(d.item.poster.contains("w342"))
                assertTrue(d.item.rating>0);assertTrue(d.cast.isNotEmpty())
                if(ref.type==CatalogType.Tv) assertTrue((d.seasons ?:0)>0)
            }
        }
        assertEquals(initialServer, graph.server.state.value)
        assertTrue(initialSettings.jackettUrl == graph.settings.state.value.jackettUrl)
        assertTrue(initialSettings.jackettKey == graph.settings.state.value.jackettKey)
    }
    @Test fun realRussianAndEnglishTranslationsAreAvailableForMoviesAndSeries() = runBlocking {
        val graph=ApplicationProvider.getApplicationContext<TorrServeApplication>().graph;graph.ready.await()
        withTimeout(45_000) {
            for ((ref,ruTitle,enTitle) in listOf(Triple(CatalogRef(157336,CatalogType.Movie),"Интерстеллар","Interstellar"),
                Triple(CatalogRef(1396,CatalogType.Tv),"Во все тяжкие","Breaking Bad"))) {
                val ru=graph.catalog.details(ref,"ru-RU").last().data!!
                val en=graph.catalog.details(ref,"en-US").last().data!!
                assertEquals(ruTitle,ru.item.title);assertEquals(enTitle,en.item.title)
                assertTrue(ru.overview.isNotBlank());assertTrue(en.overview.isNotBlank());assertNotEquals(ru.overview,en.overview)
                assertFalse(ru.englishOverview)
                val again=graph.catalog.details(ref,"ru-RU").last().data!!;assertEquals(ru,again)
                for (language in listOf("ru-RU","en-US")) {
                    val page=graph.catalog.search(enTitle,language).last().data!!
                    assertEquals(if(language=="ru-RU")ruTitle else enTitle,page.items.first { it.ref==ref }.title)
                }
            }
        }
    }

}
