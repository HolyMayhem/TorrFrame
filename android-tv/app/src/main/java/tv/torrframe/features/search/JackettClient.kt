package tv.torrframe.features.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tv.torrframe.core.network.Http
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface TorrentPayload {
    data class Magnet(val value: String) : TorrentPayload
    data class File(val value: ByteArray) : TorrentPayload
}
class JackettClient(private val http: Http) {
    fun endpoint(s: Settings, type: String, query: String? = null, category: String? = null): HttpUrl {
        val base = s.jackettUrl.trim().toHttpUrlOrNull()
        require(base != null && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            "Enter a valid HTTP or HTTPS Jackett address without credentials or query parameters."
        }
        require(s.jackettKey.isNotBlank()) { "Enter the Jackett API key." }
        return base.newBuilder().apply {
            if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSegments.lastIndex)
            addPathSegments("api/v2.0/indexers/all/results/torznab/api")
            addQueryParameter("apikey", s.jackettKey.trim()); addQueryParameter("t", type)
            query?.let { addQueryParameter("q", it); addQueryParameter("limit", "100") }
            category?.let { addQueryParameter("cat", it) }
        }.build()
    }
    suspend fun test(s: Settings) = withContext(Dispatchers.Default) { TorznabParser.caps(http.get(endpoint(s, "caps"), "Jackett").toString(Charsets.UTF_8)) }
    suspend fun search(s: Settings, query: String, filter: String): SearchPage = withContext(Dispatchers.Default) {
        require(s.jackettEnabled) { "Jackett is disabled." }
        val category = when(filter) { "Movies" -> "2000"; "TV Shows" -> "5000"; else -> null }
        TorznabParser.results(http.get(endpoint(s, "search", query, category), "Jackett").toString(Charsets.UTF_8))
    }
    suspend fun download(result: SearchResult): TorrentPayload = withContext(Dispatchers.Default) {
        if (result.link.startsWith("magnet:?", true)) return@withContext TorrentPayload.Magnet(result.link)
        var url = result.link.toHttpUrlOrNull() ?: throw ServiceFailure("Jackett", -2)
        var downloaded: ByteArray? = null
        for (attempt in 0..5) {
            val response = http.exchange(Request.Builder().url(url).build(), "Jackett", 16 * 1024 * 1024, manualRedirects = true)
            val location = response.location
            if(response.status in 200..299) { downloaded = response.data; break }
            if(location?.startsWith("magnet:?", true) == true) return@withContext TorrentPayload.Magnet(location)
            val next = location?.let { url.resolve(it) } ?: throw ServiceFailure("Jackett", -2)
            if(url.scheme == "https" && next.scheme == "http") throw ServiceFailure("Jackett", -2)
            url = next
        }
        val body = downloaded ?: throw ServiceFailure("Jackett", -2)
        val value = body.take(4096).toByteArray().toString(Charsets.UTF_8).trim()
        if (value.startsWith("magnet:?", true)) return@withContext TorrentPayload.Magnet(value)
        if (body.size < 16 || body.first() != 'd'.code.toByte()) throw ServiceFailure("Jackett", -2)
        TorrentPayload.File(body)
    }
}
