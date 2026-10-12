package tv.torrserve.silicon.core.models

import org.json.JSONArray
import org.json.JSONObject

enum class MediaKind { Movie, Tv }
data class TorrentFile(val id: Int, val path: String, val size: Long) {
    val isVideo get() = path.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "webm", "mpg", "mpeg")
    fun json() = JSONObject().put("id", id).put("path", path).put("length", size)
}
data class Torrent(val hash: String, val title: String, val files: List<TorrentFile>)
data class Metadata(
    val provider: String, val id: String, val title: String, val originalTitle: String,
    val year: Int?, val kind: MediaKind, val overview: String = "", val poster: String = "",
    val backdrop: String = "", val rating: Double = 0.0, val genres: String = "", val runtime: Int = 0
) {
    fun json() = JSONObject().put("provider", provider).put("id", id).put("title", title)
        .put("originalTitle", originalTitle).put("year", year).put("kind", kind.name)
        .put("overview", overview).put("poster", poster).put("backdrop", backdrop)
        .put("rating", rating).put("genres", genres).put("runtime", runtime)
    companion object {
        fun from(j: JSONObject) = Metadata(j.getString("provider"), j.getString("id"),
            j.getString("title"), j.optString("originalTitle"), j.optInt("year").takeIf { it > 0 },
            MediaKind.valueOf(j.getString("kind")), j.optString("overview"), j.optString("poster"),
            j.optString("backdrop"), j.optDouble("rating", 0.0), j.optString("genres"), j.optInt("runtime"))
    }
}
data class LibraryItem(val hash: String, val sourceTitle: String, val addedAt: Long,
    val metadata: Metadata? = null, val files: List<TorrentFile> = emptyList(),
    val position: Long = 0, val duration: Long = 0, val lastFileId: Int = 0) {
    val title get() = metadata?.title ?: sourceTitle
    val inProgress get() = position > 30_000 && (duration <= 0 || position < duration * 0.95)
}
fun JSONObject.torrent(): Torrent {
    val hash = getString("hash").lowercase()
    require(hash.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) { "Invalid torrent identity" }
    return Torrent(hash, optString("title").ifBlank { optString("name", "Torrent") }, optJSONArray("file_stats").files())
}
fun JSONArray?.files(): List<TorrentFile> = if (this == null) emptyList() else
    (0 until length()).map { getJSONObject(it).let { j -> TorrentFile(j.getInt("id"), j.getString("path"), j.optLong("length")) } }
