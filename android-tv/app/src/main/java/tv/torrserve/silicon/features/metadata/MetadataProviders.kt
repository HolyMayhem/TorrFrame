package tv.torrserve.silicon.features.metadata

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.storage.Settings

data class MetadataMatch(val value: Metadata, val popularity: Double = 0.0, val aliases: List<String> = emptyList())
private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" || it == "N/A" }.orEmpty()
private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { optString(it) }
class MetadataProviders(private val http: Http) {
    private suspend fun get(url: String, parameters: Map<String, String>, s: Settings): JSONObject {
        val builder = url.toHttpUrl().newBuilder(); parameters.forEach { (k,v) -> builder.addQueryParameter(k,v) }
        when(s.metadataProvider) { "TMDB" -> builder.addQueryParameter("api_key", s.metadataKey); "OMDb" -> builder.addQueryParameter("apikey", s.metadataKey) }
        val request = Request.Builder().url(builder.build()).apply {
            if (s.metadataProvider == "Kinopoisk") header("X-API-KEY", s.metadataKey)
        }.build()
        return JSONObject(http.bytes(request, s.metadataProvider).toString(Charsets.UTF_8))
    }
    suspend fun search(p: ParsedName, s: Settings): List<MetadataMatch> = when(s.metadataProvider) {
        "TMDB" -> {
            val kind = if (p.kind == MediaKind.Movie) "movie" else "tv"
            val params = mutableMapOf("query" to p.title, "language" to s.language, "include_adult" to "false")
            p.year?.let { params[if (p.kind == MediaKind.Movie) "primary_release_year" else "first_air_date_year"] = it.toString() }
            get("https://api.themoviedb.org/3/search/$kind", params, s).optJSONArray("results").objects().map { tmdb(it, p.kind) }
        }
        "OMDb" -> {
            val params = mutableMapOf("s" to p.title, "type" to if(p.kind == MediaKind.Movie) "movie" else "series")
            p.year?.let { params["y"] = it.toString() }
            val j = get("https://www.omdbapi.com/", params, s)
            if (j.text("Response") == "False" && !j.text("Error").contains("not found", true)) throw ServiceFailure("OMDb", -2)
            j.optJSONArray("Search").objects().map { MetadataMatch(omdb(it)) }
        }
        "Kinopoisk" -> {
            val params = mutableMapOf("keyword" to p.title, "page" to "1")
            p.year?.let { params["yearFrom"] = it.toString(); params["yearTo"] = it.toString() }
            get("https://kinopoiskapiunofficial.tech/api/v2.2/films", params, s).optJSONArray("items").objects().map { MetadataMatch(kinopoisk(it, s)) }
        }
        "AniList" -> {
            val fields = "id title { romaji english native } synonyms format description(asHtml:false) startDate { year } duration coverImage { large } bannerImage genres averageScore popularity"
            val query = "query(\$search:String!){Page(page:1,perPage:10){media(search:\$search,type:ANIME,isAdult:false){$fields}}}"
            val j = graphql(query, JSONObject().put("search", p.title))
            j.getJSONObject("data").getJSONObject("Page").optJSONArray("media").objects().map { anilist(it, p.title, s) }
        }
        else -> emptyList()
    }
    suspend fun details(match: MetadataMatch, s: Settings): Metadata {
        val m = match.value
        return when(s.metadataProvider) {
            "TMDB" -> tmdb(get("https://api.themoviedb.org/3/${if(m.kind == MediaKind.Movie) "movie" else "tv"}/${m.id}", mapOf("language" to s.language), s), m.kind).value
            "OMDb" -> {
                val j = get("https://www.omdbapi.com/", mapOf("i" to m.id, "plot" to "full"), s)
                if (j.text("Response") == "False") throw ServiceFailure("OMDb", -2)
                omdb(j)
            }
            "Kinopoisk" -> kinopoisk(get("https://kinopoiskapiunofficial.tech/api/v2.2/films/${m.id}", emptyMap(), s), s)
            else -> m
        }
    }
    private fun tmdb(j: JSONObject, kind: MediaKind): MetadataMatch {
        val movie = kind == MediaKind.Movie
        fun image(key: String, size: String) = j.text(key).let { if (it.isBlank()) "" else "https://image.tmdb.org/t/p/$size$it" }
        return MetadataMatch(Metadata("TMDB", j.get("id").toString(), j.text(if(movie) "title" else "name"),
            j.text(if(movie) "original_title" else "original_name"), j.text(if(movie) "release_date" else "first_air_date").take(4).toIntOrNull(), kind,
            j.text("overview"), image("poster_path", "w342"), image("backdrop_path", "w780"), j.optDouble("vote_average", 0.0),
            j.optJSONArray("genres").objects().joinToString(", ") { it.text("name") }, j.optInt("runtime")), j.optDouble("popularity", 0.0))
    }
    private fun omdb(j: JSONObject) = Metadata("OMDb", j.text("imdbID"), j.text("Title"), j.text("Title"), j.text("Year").take(4).toIntOrNull(),
        if (j.text("Type") == "series" || j.text("Type") == "episode") MediaKind.Tv else MediaKind.Movie,
        j.text("Plot"), j.text("Poster"), rating = j.text("imdbRating").toDoubleOrNull() ?: 0.0,
        genres = j.text("Genre"), runtime = Regex("\\d+").find(j.text("Runtime"))?.value?.toIntOrNull() ?: 0)
    private fun kinopoisk(j: JSONObject, s: Settings): Metadata {
        val ru = j.text("nameRu"); val en = j.text("nameOriginal").ifBlank { j.text("nameEn") }
        return Metadata("Kinopoisk", j.get("kinopoiskId").toString(), if(s.language.startsWith("ru")) ru.ifBlank { en } else en.ifBlank { ru }, en,
            j.optInt("year").takeIf { it > 0 }, if (j.text("type") in setOf("TV_SERIES", "MINI_SERIES")) MediaKind.Tv else MediaKind.Movie,
            j.text("description"), j.text("posterUrlPreview").ifBlank { j.text("posterUrl") }, j.text("coverUrl"),
            j.optDouble("ratingKinopoisk", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            j.optJSONArray("genres").objects().joinToString(", ") { it.text("genre") }, j.optInt("filmLength"))
    }
    private suspend fun graphql(query: String, variables: JSONObject): JSONObject {
        val j = JSONObject(http.post("https://graphql.anilist.co".toHttpUrl(), JSONObject().put("query", query).put("variables", variables), "AniList").toString(Charsets.UTF_8))
        if (!j.has("data") || j.isNull("data") || j.has("errors")) throw ServiceFailure("AniList", -2)
        return j
    }
    private fun anilist(j: JSONObject, requested: String, s: Settings): MetadataMatch {
        val titles = j.getJSONObject("title"); val aliases = listOf(titles.text("english"), titles.text("romaji"), titles.text("native")) + j.optJSONArray("synonyms").strings()
        val title = titles.text("english").ifBlank { titles.text("romaji") }.ifBlank { titles.text("native") }
        return MetadataMatch(Metadata("AniList", j.get("id").toString(), title, titles.text("romaji"),
            j.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }, if (j.text("format") == "MOVIE") MediaKind.Movie else MediaKind.Tv,
            j.text("description").replace(Regex("<[^>]+>"), " "), j.optJSONObject("coverImage")?.text("large").orEmpty(), j.text("bannerImage"),
            j.optDouble("averageScore", 0.0) / 10, j.optJSONArray("genres").strings().joinToString(", "), j.optInt("duration")),
            j.optDouble("popularity", 0.0), aliases)
    }
}
