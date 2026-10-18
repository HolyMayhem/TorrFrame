package tv.torrserve.silicon.features.catalog.data

import org.json.JSONObject
import java.time.LocalDate
import tv.torrserve.silicon.core.network.CatalogJsonClient
import tv.torrserve.silicon.features.catalog.domain.*

interface CatalogRemote {
    suspend fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int): JSONObject
    suspend fun section(section: CatalogSection, language: String, page: Int): JSONObject
    suspend fun search(query: String, language: String, page: Int): JSONObject
    suspend fun details(ref: CatalogRef, language: String): JSONObject
    suspend fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int): JSONObject
}
class TmdbCatalogApi(private val client: CatalogJsonClient, private val today: () -> LocalDate = LocalDate::now) : CatalogRemote {
    override suspend fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int) =
        client.get("${ref.type.path}/${ref.id}/${source.path}", mapOf("language" to language, "page" to page.toString()))
    override suspend fun section(section: CatalogSection, language: String, page: Int): JSONObject {
        val params = mutableMapOf("language" to language, "page" to page.toString(), "include_adult" to "false")
        if (section == CatalogSection.NewMovies) {
            val date = today()
            params.putAll(mapOf("primary_release_date.gte" to date.minusMonths(6).toString(), "primary_release_date.lte" to date.toString(),
                "sort_by" to "popularity.desc", "vote_count.gte" to "30", "include_video" to "false"))
        }
        return client.get(section.endpoint, params)
    }
    override suspend fun search(query: String, language: String, page: Int): JSONObject {
        require(query.isNotBlank())
        return client.get("search/multi", mapOf("query" to query, "language" to language, "page" to page.toString(), "include_adult" to "false"))
    }
    override suspend fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int): JSONObject {
        val genres = requireNotNull(filter.genres(type)) { "This genre is not available for this media type" }
        val params = mutableMapOf("language" to language, "page" to page.toString(), "include_adult" to "false",
            "sort_by" to "popularity.desc", "with_genres" to genres)
        if (type == CatalogType.Movie) params["include_video"] = "false"
        return client.get("discover/${type.path}", params)
    }
    override suspend fun details(ref: CatalogRef, language: String): JSONObject {
        val result = client.get("${ref.type.path}/${ref.id}", mapOf("language" to language, "append_to_response" to "credits"))
        val titleField = if (ref.type == CatalogType.Movie) "title" else "name"
        val originalField = if (ref.type == CatalogType.Movie) "original_title" else "original_name"
        // Title equality is a reason to inspect explicit translation metadata, not proof
        // that a translation is absent. Some localized titles intentionally stay identical.
        val missingTitle = result.text(titleField).isBlank() ||
            (result.text(titleField) == result.text(originalField) && result.text("original_language") != language.substringBefore('-'))
        if (missingTitle || result.text("overview").isBlank()) {
            val translations = optionalGet("${ref.type.path}/${ref.id}/translations", emptyMap())?.optJSONArray("translations").objects()
            fun translated(field: String, locale: String): String {
                val iso = locale.substringBefore('-')
                val country = locale.substringAfter('-', "")
                return translations.filter { it.text("iso_639_1") == iso }
                    .sortedByDescending { it.text("iso_3166_1") == country }
                    .firstNotNullOfOrNull { it.optJSONObject("data")?.text(field)?.takeIf(String::isNotBlank) }.orEmpty()
            }
            if (missingTitle) translated(titleField, language).takeIf(String::isNotBlank)?.let { result.put(titleField, it) }
            if (result.text("overview").isBlank()) translated("overview", language).takeIf(String::isNotBlank)?.let { result.put("overview", it) }
            if (result.text("overview").isBlank()) {
                val english = if (language != "en-US") translated("overview", "en-US") else ""
                val original = translated("overview", result.text("original_language"))
                when {
                    english.isNotBlank() -> { result.put("overview", english); result.put("catalog_english_overview", true) }
                    original.isNotBlank() -> { result.put("overview", original); result.put("catalog_english_overview", result.text("original_language") == "en") }
                    translations.isEmpty() && language != "en-US" -> {
                        // If translations could not be retrieved, retain the existing lazy fallback.
                        val fallback = optionalGet("${ref.type.path}/${ref.id}", mapOf("language" to "en-US"))?.text("overview").orEmpty()
                        if (fallback.isNotBlank()) { result.put("overview", fallback); result.put("catalog_english_overview", true) }
                    }
                }
            }
        }
        return result
    }
    private suspend fun optionalGet(path: String, parameters: Map<String, String>): JSONObject? = try { client.get(path, parameters) }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
}
