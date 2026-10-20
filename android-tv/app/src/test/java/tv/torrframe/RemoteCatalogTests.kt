package tv.torrframe

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.features.catalog.domain.*
import tv.torrframe.phoneRemote.api.RemoteCatalog

class RemoteCatalogTests {
    private class Repository : CatalogRepository {
        val item = CatalogItem(CatalogRef(1, CatalogType.Movie), "Title", "Original", date = "2024-01-01")
        var locale = ""; var requestedPage = 0; var route = ""
        private fun page(language: String, page: Int, path: String) = flowOf(CatalogLoad(CatalogPage(listOf(item), page, 3))).also {
            locale = language; requestedPage = page; route = path
        }
        override fun section(section: CatalogSection, language: String, page: Int, force: Boolean) = page(language, page, section.name)
        override fun search(query: String, language: String, page: Int, force: Boolean) = page(language, page, query)
        override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean) = page(language, page, filter.name)
        override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) = page(language, page, source.name)
        override fun details(ref: CatalogRef, language: String, force: Boolean) = flowOf(CatalogLoad(CatalogDetails(item, "Overview", listOf("Drama"), 90, emptyList(), emptyList())))
    }
    @Test fun browseSearchGenresAndRecommendationsUseSharedRepositoryAndPagination() = runBlocking {
        val repo = Repository(); val remote = RemoteCatalog(repo, MemoryCatalogUserLists()) { "en" }
        val response = remote.execute("catalog.browse", JSONObject().put("filter", "Comedy").put("page", 2).put("language", "ru-RU"))
        assertEquals("Comedy", repo.route); assertEquals("ru-RU", repo.locale); assertEquals(2, repo.requestedPage)
        assertEquals("movie:1", response.getJSONArray("items").getJSONObject(0).getString("key"))
        remote.execute("catalog.browse", JSONObject().put("query", "query")); assertEquals("query", repo.route)
        remote.execute("catalog.related", JSONObject().put("id", 1).put("mediaType", "Movie").put("source", "Recommendations"))
        assertEquals("Recommendations", repo.route)
    }
    @Test fun favoriteAndWatchLaterChangesUseTheSameListStore() = runBlocking {
        val lists = MemoryCatalogUserLists(); val remote = RemoteCatalog(Repository(), lists) { "en" }
        val args = JSONObject().put("id", 1).put("mediaType", "Movie").put("list", "Favorites")
        val result = remote.execute("catalog.toggle", args)
        assertTrue(result.getJSONArray("items").getJSONObject(0).getBoolean("favorite"))
        assertTrue(lists.entries.value.single().favorite)
        remote.execute("catalog.toggle", args.put("list", "WatchLater")); assertTrue(lists.entries.value.single().watchLater)
        remote.execute("catalog.toggle", args.put("list", "Favorites")); assertFalse(lists.entries.value.single().favorite)
    }
    @Test fun partialDetailsRemainUsableAndReportTheirNetworkFailure() = runBlocking {
        val base = Repository()
        val repo = object : CatalogRepository by base {
            override fun details(ref: CatalogRef, language: String, force: Boolean) = flowOf(
                CatalogLoad(CatalogDetails(base.item, "", emptyList(), null, emptyList(), emptyList()), error = true, stale = true))
        }
        val remote = RemoteCatalog(repo, MemoryCatalogUserLists()) { "en" }
        val result = remote.execute("catalog.details", JSONObject().put("id", 1).put("mediaType", "Movie"))
        assertEquals("Title", result.getString("title"))
        assertTrue(result.getBoolean("partial")); assertTrue(result.getBoolean("stale"))
        assertEquals(0, result.getJSONArray("cast").length())
        assertTrue(result.isNull("runtime"))
    }
    @Test fun invalidPagingAndUnsupportedGenreTypesAreRejected() = runBlocking {
        val remote = RemoteCatalog(Repository(), MemoryCatalogUserLists()) { "en" }
        for (args in listOf(JSONObject().put("page", 501), JSONObject().put("filter", "Thriller").put("mediaType", "Tv"))) {
            try { remote.execute("catalog.browse", args); fail("Invalid catalog request accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
