package tv.torrframe.phoneRemote.api

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.torrframe.AppGraph
import tv.torrframe.features.catalog.domain.*

internal class RemoteCatalog(private val repository: CatalogRepository, private val userLists: CatalogUserLists,
    private val uiLanguage: () -> String) {
    constructor(graph: AppGraph) : this(graph.catalog, graph.catalogUserLists, { graph.settings.state.value.uiLanguage })
    private fun ref(args: JSONObject) = CatalogRef(args.getInt("id").also { require(it > 0) }, CatalogType.valueOf(args.getString("mediaType")))
    private fun language(args: JSONObject) = args.optString("language", if (uiLanguage() == "ru") "ru-RU" else "en-US")
        .also { require(it in listOf("ru-RU", "en-US")) }
    suspend fun execute(type: String, args: JSONObject): JSONObject {
        val language = language(args)
        val page = args.optInt("page", 1).also { require(it in 1..500) }
        suspend fun detailLoad() = repository.details(ref(args), language).first { !it.loading }
        suspend fun details() = requireNotNull(detailLoad().data)
        return when (type) {
            "catalog.browse" -> {
                val query = args.optString("query").trim().also { require(it.length <= 250) }
                val filter = CatalogFilter.valueOf(args.optString("filter", "All"))
                val flow = when {
                    query.isNotBlank() -> repository.search(query, language, page)
                    filter.isGenre -> {
                        val kind = CatalogType.valueOf(args.optString("mediaType", filter.genreTypes.first().name))
                        require(kind in filter.genreTypes)
                        repository.discover(filter, kind, language, page)
                    }
                    else -> repository.section(CatalogSection.valueOf(args.optString("section", filter.sections.first().name)), language, page)
                }
                val load = flow.first { !it.loading }
                val data = load.data
                check(data != null)
                JSONObject().put("items", JSONArray(data.items.map(::item))).put("page", data.page).put("totalPages", data.totalPages)
                    .put("stale", load.stale).put("partial", load.error)
            }
            "catalog.details" -> detailLoad().let { load -> val value = requireNotNull(load.data); item(value.item).put("partial", load.error).put("stale", load.stale).put("overview", value.overview)
                .put("genres", JSONArray(value.genres)).put("runtime", value.runtime ?: JSONObject.NULL)
                .put("cast", JSONArray(value.cast)).put("directors", JSONArray(value.directors))
                .put("seasons", value.seasons ?: JSONObject.NULL).put("episodes", value.episodes ?: JSONObject.NULL).put("status", value.status) }
            "catalog.related" -> {
                val load = repository.related(ref(args), CatalogRelatedSource.valueOf(args.optString("source", "Similar")), language, page).first { !it.loading }
                val data = requireNotNull(load.data)
                JSONObject().put("items", JSONArray(data.items.map(::item))).put("page", data.page).put("totalPages", data.totalPages)
                    .put("stale", load.stale).put("partial", load.error)
            }
            "catalog.toggle" -> {
                userLists.load()
                val value = userLists.entries.value.firstOrNull { it.ref == ref(args) }?.item(language) ?: details().item
                userLists.toggle(value, language, CatalogUserList.valueOf(args.getString("list")))
                lists(language)
            }
            "catalog.lists" -> lists(language)
            else -> error("Unsupported catalog command")
        }
    }
    suspend fun lists(language: String): JSONObject {
        userLists.load()
        return JSONObject().put("items", JSONArray(userLists.entries.value.map {
            item(it.item(language)).put("favorite", it.favorite).put("watchLater", it.watchLater)
        }))
    }
    companion object {
        fun item(value: CatalogItem) = JSONObject().put("id", value.ref.id).put("mediaType", value.ref.type.name)
            .put("key", value.ref.key).put("title", value.title).put("originalTitle", value.originalTitle)
            .put("poster", value.poster).put("backdrop", value.backdrop).put("year", value.year ?: JSONObject.NULL).put("rating", value.rating)
    }
}
