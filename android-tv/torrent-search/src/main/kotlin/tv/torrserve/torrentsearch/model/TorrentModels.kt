package tv.torrserve.torrentsearch.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

enum class MediaType { Movie, Series }
data class SearchTarget(val title: String, val originalTitle: String = "", val year: Int? = null, val type: MediaType = MediaType.Movie, val tmdbId: Int? = null) {
    fun queries(mode: SearchTitleMode = SearchTitleMode.Both): List<String> {
        fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(160)
        val original = clean(originalTitle).ifBlank { clean(title) }
        val local = clean(title)
        val dated: (String) -> String = { name -> if (name.isBlank()) "" else if (year != null && !Regex("\\b$year\\b").containsMatchIn(name)) "$name $year" else name }
        val names=when(mode) { SearchTitleMode.Original->listOf(original);SearchTitleMode.Russian->listOf(local.ifBlank { original });SearchTitleMode.Both->listOf(original,local) }
        return (names.map(dated) + if (type == MediaType.Series) names else emptyList())
            .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
    }
}
data class TorrentResult(val providerId: String, val providerName: String, val title: String,
    val seeds: Long? = null, val peers: Long? = null, val sizeBytes: Long? = null, val sizeText: String? = null,
    val magnet: String? = null, val torrentUrl: String? = null, val detailsUrl: String? = null,
    val trackers: Set<String> = setOf(providerName)) {
    val infoHash get() = magnet?.let(TorrentIdentity::hash)
    val key get() = infoHash?.let { "hash:$it" } ?: "$providerId:${detailsUrl ?: torrentUrl ?: title}"
    val quality get() = VideoQuality.from(title)
}
sealed interface TorrentLink {
    data class Magnet(val value: String) : TorrentLink
    data class FileUrl(val value: String) : TorrentLink
}
enum class SourcePhase { Waiting, Searching, Complete, Failed, TimedOut }
data class SourceState(val id: String, val name: String, val phase: SourcePhase = SourcePhase.Waiting, val count: Int = 0)
data class SearchSnapshot(val results: List<TorrentResult> = emptyList(), val sources: List<SourceState> = emptyList()) {
    val searching get() = sources.any { it.phase == SourcePhase.Waiting || it.phase == SourcePhase.Searching }
}
object TorrentIdentity {
    fun hash(magnet: String): String? = runCatching {
        if(!magnet.startsWith("magnet:?",true))return null
        val xt = magnet.substringAfter('?', "").split('&').firstNotNullOfOrNull { part ->
            if (part.substringBefore('=').equals("xt", true)) URLDecoder.decode(part.substringAfter('='), "UTF-8")
                .takeIf { it.startsWith("urn:btih:", true) }?.substring(9) else null
        } ?: return null
        if (Regex("[0-9a-fA-F]{40}").matches(xt)) xt.lowercase(Locale.ROOT)
        else if (Regex("[A-Za-z2-7]{32}").matches(xt)) {
            var bits = 0; var value = 0; val bytes = mutableListOf<Int>()
            xt.uppercase(Locale.ROOT).forEach { c ->
                value = (value shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c); bits += 5
                if (bits >= 8) { bits -= 8; bytes += (value shr bits) and 255; value = value and ((1 shl bits) - 1) }
            }
            bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
        } else null
    }.getOrNull()
    fun magnet(hash: String, title: String = ""): String? {
        val value = "magnet:?xt=urn:btih:$hash"
        return hash(value)?.let { canonical ->
            "magnet:?xt=urn:btih:$canonical&dn=${encode(title)}" +
                listOf("udp://tracker.opentrackr.org:1337/announce","udp://open.demonii.com:1337/announce","udp://tracker.torrent.eu.org:451/announce")
                    .joinToString("") { "&tr=${encode(it)}" }
        }
    }
    fun web(value: String?): String? = value?.takeIf { runCatching { val u = URI(it); u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null }.getOrDefault(false) }
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
object VideoQuality {
    fun from(title: String): String {
        val patterns = listOf("2160p" to "(?:2160p|\\b4k\\b)", "1080p" to "1080p", "720p" to "720p", "480p" to "480p",
            "WEB-DL" to "web[ ._-]?dl", "WEBRip" to "web[ ._-]?rip", "BluRay" to "blu[ ._-]?ray", "BDRip" to "bd[ ._-]?rip",
            "HDTV" to "hdtv", "DVD" to "dvd[ ._-]?(?:rip|scr)", "HDR" to "\\bhdr(?:10)?\\b", "DV" to "\\b(?:dv|dolby[ ._-]?vision)\\b")
        return patterns.filter { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(title) }.joinToString(" · ") { it.first }
    }
}
