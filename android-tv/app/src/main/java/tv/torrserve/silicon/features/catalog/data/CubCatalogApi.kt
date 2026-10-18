package tv.torrserve.silicon.features.catalog.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.features.catalog.domain.*

/** Public CUB lists/search. Detail endpoints still depend on TMDB, so use its normal route. */
class CubCatalogApi(
    http: Http,
    private val config: CatalogNetworkSettings,
    tmdbKey: () -> String = { tv.torrserve.silicon.features.metadata.BuiltInMetadataKeys.forProvider("TMDB") },
) : CatalogRemote {
    private val slots = Semaphore(2)
    // CUB's catalog service is distinct from its account/bookmark API (/api/).
    private val base = CatalogNetworkSettings.endpoint(config.effectiveCubUrl)
    private val client = Http(http.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(18, java.util.concurrent.TimeUnit.SECONDS).build())
    private suspend fun get(path: String, parameters: Map<String, String>): JSONObject = slots.withPermit {
        require(config.cubConfigured)
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/" + path)
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Accept", "application/json")
            .apply { if (config.cubToken.isNotBlank()) header("token", config.cubToken) }.build()
        val json = try { JSONObject(client.bytes(request, "CUB", limit = 1024 * 1024).toString(Charsets.UTF_8)) }
            catch (e: org.json.JSONException) { throw ServiceFailure("CUB", -2, cause = e) }
        if (json.optBoolean("success", true).not() || json.optBoolean("secuses", true).not() ||
            json.has("error") || json.optInt("status_code") > 0) throw ServiceFailure("CUB", -2)
        json
    }
    private val detailsApi = TmdbCatalogApi(TmdbClient(http, tmdbKey))
    // CUB treats language as a content filter, not a translation locale; ru-RU empties valid searches.
    private fun parameters(page: Int) = mapOf("page" to page.toString())
    private fun normalizePage(json: JSONObject, type: CatalogType): JSONObject {
        val results = json.optJSONArray("results") ?: throw ServiceFailure("CUB", -2)
        // CUB stores TV titles as title/original_title in some responses. Preserve TMDB IDs.
        for (item in results.objects()) {
            item.put("media_type", type.path)
            if (type == CatalogType.Tv) {
                if (item.text("name").isBlank()) item.put("name", item.text("title"))
                if (item.text("original_name").isBlank()) item.put("original_name", item.text("original_title"))
                if (item.text("first_air_date").isBlank()) item.put("first_air_date", item.text("release_date"))
            }
        }
        return json
    }
    override suspend fun section(section: CatalogSection, language: String, page: Int): JSONObject {
        val sort = when (section) {
            CatalogSection.TrendingMovies, CatalogSection.TrendingTv -> "now_playing"
            CatalogSection.NewMovies -> "latest"
            else -> "top"
        }
        val params = parameters(page) + mapOf("cat" to section.type.path, "sort" to sort) +
            (if (section == CatalogSection.TopRated) mapOf("vote" to "8-10") else emptyMap())
        return normalizePage(get("", params), section.type)
    }
    override suspend fun search(query: String, language: String, page: Int): JSONObject = coroutineScope {
        require(query.isNotBlank())
        val parts = CatalogType.entries.map { type -> async {
            normalizePage(get("search/${type.path}", parameters(page) + ("query" to query)), type)
        } }.map { it.await() }
        val results = JSONArray()
        parts.forEach { part -> part.optJSONArray("results").objects().forEach { results.put(it) } }
        JSONObject().put("results", results).put("page", page)
            .put("total_pages", parts.maxOf { it.optInt("total_pages", 0).coerceIn(0, 500) })
    }
    override suspend fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int): JSONObject {
        val genres = requireNotNull(filter.genres(type))
        return normalizePage(get("", parameters(page) + mapOf("cat" to type.path, "sort" to "top", "genre" to genres)), type)
    }
    override suspend fun details(ref: CatalogRef, language: String): JSONObject = detailsApi.details(ref, language)
    override suspend fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int): JSONObject =
        normalizePage(detailsApi.related(ref, source, language, page), ref.type)
}
