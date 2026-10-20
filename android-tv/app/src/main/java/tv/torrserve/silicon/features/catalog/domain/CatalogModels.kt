package tv.torrserve.silicon.features.catalog.domain

import org.json.JSONObject
import org.json.JSONArray

/** TMDB identities must include media type: movie and TV IDs can overlap. */
enum class CatalogType(val path: String) { Movie("movie"), Tv("tv") }
/** Both lists are ranked by TMDB, never by a local recommendation algorithm. */
enum class CatalogRelatedSource(val path: String) { Similar("similar"), Recommendations("recommendations") }
data class CatalogRef(val id: Int, val type: CatalogType) {
    val key get() = "${type.path}:$id"
}
data class CatalogItem(
    val ref: CatalogRef, val title: String, val originalTitle: String = "",
    val poster: String = "", val backdrop: String = "", val date: String = "",
    val rating: Double = 0.0, val votes: Int = 0,
) { val year get() = date.take(4).toIntOrNull() }
data class CatalogPage(val items: List<CatalogItem>, val page: Int, val totalPages: Int)
data class CatalogDetails(
    val item: CatalogItem, val overview: String, val genres: List<String>, val runtime: Int?,
    val directors: List<String>, val cast: List<String>, val seasons: Int? = null,
    val episodes: Int? = null, val status: String = "", val lastAirDate: String = "",
    val englishOverview: Boolean = false,
)
enum class CatalogSection(val endpoint: String, val type: CatalogType, val ttlHours: Int) {
    TrendingMovies("trending/movie/week", CatalogType.Movie, 12),
    NewMovies("discover/movie", CatalogType.Movie, 12),
    PopularMovies("movie/popular", CatalogType.Movie, 12),
    TopRated("movie/top_rated", CatalogType.Movie, 24),
    PopularTv("tv/popular", CatalogType.Tv, 12),
    TrendingTv("trending/tv/week", CatalogType.Tv, 12),
}
/** Browse shortcuts use TMDB genre IDs; TV combines action/adventure and science fiction/fantasy. */
enum class CatalogFilter(val type: CatalogType? = null, private val movieGenres: String? = null,
    private val tvGenres: String? = null) {
    All, Movies(type = CatalogType.Movie), Series(type = CatalogType.Tv),
    ActionAdventure(movieGenres = "28|12", tvGenres = "10759"),
    Comedy(movieGenres = "35", tvGenres = "35"), Drama(movieGenres = "18", tvGenres = "18"),
    ScienceFictionFantasy(movieGenres = "878|14", tvGenres = "10765"),
    Thriller(movieGenres = "53"), Horror(movieGenres = "27"),
    Animation(movieGenres = "16", tvGenres = "16"), Crime(movieGenres = "80", tvGenres = "80"),
    Mystery(movieGenres = "9648", tvGenres = "9648"), Family(movieGenres = "10751", tvGenres = "10751"),
    Documentary(movieGenres = "99", tvGenres = "99"), Romance(movieGenres = "10749");
    val isGenre get() = movieGenres != null || tvGenres != null
    fun genres(type: CatalogType) = if (type == CatalogType.Movie) movieGenres else tvGenres
    val genreTypes get() = CatalogType.entries.filter { genres(it) != null }
    val sections get() = CatalogSection.entries.filter { type == null || it.type == type }
}
enum class CatalogOrigin { Tmdb, Cub, Unspecified }
enum class CatalogFailureKind { Dns, Timeout, Tls, Denied, RateLimited, Http, InvalidResponse, TooLarge, Connection, Unknown }
/** Safe diagnostic data: never expose request URLs, API keys or exception messages in the UI. */
data class CatalogFailure(val provider: CatalogOrigin, val kind: CatalogFailureKind, val status: Int? = null)
data class CatalogLoad<out T>(val data: T? = null, val loading: Boolean = false, val stale: Boolean = false, val error: Boolean = false,
    val failure: CatalogFailure? = null, val source: CatalogOrigin = CatalogOrigin.Tmdb)

internal fun JSONObject.text(name: String) = optString(name).takeUnless { it == "null" }.orEmpty()
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
object CatalogParser {
    fun image(path: String, size: String) = if (path.startsWith('/') && !path.contains("..")) "https://image.tmdb.org/t/p/$size$path" else ""
    fun item(j: JSONObject, type: CatalogType): CatalogItem? {
        val id = j.optInt("id"); if (id <= 0 || j.optBoolean("adult")) return null
        val movie = type == CatalogType.Movie
        val original = j.text(if (movie) "original_title" else "original_name")
        val title = j.text(if (movie) "title" else "name").ifBlank { original }
        if (title.isBlank()) return null
        return CatalogItem(CatalogRef(id, type), title, original, image(j.text("poster_path"), "w342"),
            image(j.text("backdrop_path"), "w780"), j.text(if (movie) "release_date" else "first_air_date"),
            j.optDouble("vote_average", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 10.0) ?: 0.0, j.optInt("vote_count").coerceAtLeast(0))
    }
    fun page(j: JSONObject, type: CatalogType? = null): CatalogPage = CatalogPage(
        j.optJSONArray("results").objects().mapNotNull { obj ->
            val kind = type ?: when (obj.text("media_type")) { "movie" -> CatalogType.Movie; "tv" -> CatalogType.Tv; else -> null }
            kind?.let { item(obj, it) }
        }.distinctBy { it.ref.key }, j.optInt("page", 1).coerceAtLeast(1), j.optInt("total_pages", 1).coerceIn(0, 500))
    fun details(j: JSONObject, type: CatalogType): CatalogDetails {
        val item = requireNotNull(item(j, type)) { "Invalid TMDB detail response" }
        val credits = j.optJSONObject("credits")
        return CatalogDetails(item, j.text("overview"), j.optJSONArray("genres").objects().mapNotNull { it.text("name").takeIf(String::isNotBlank) },
            (if (type == CatalogType.Movie) j.optInt("runtime") else j.optJSONArray("episode_run_time")?.optInt(0) ?: 0).takeIf { it > 0 },
            credits?.optJSONArray("crew").objects().filter { it.text("job") == "Director" }.map { it.text("name") }.filter(String::isNotBlank).distinct(),
            credits?.optJSONArray("cast").objects().take(8).map { it.text("name") }.filter(String::isNotBlank),
            if (type == CatalogType.Tv) j.optInt("number_of_seasons").takeIf { it > 0 } else null,
            if (type == CatalogType.Tv) j.optInt("number_of_episodes").takeIf { it > 0 } else null,
            j.text("status"), j.text("last_air_date"), j.optBoolean("catalog_english_overview"))
    }
}
