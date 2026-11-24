package tv.torrframe.features.torrentsearch.data

import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tv.torrframe.core.network.Http
import tv.torrframe.features.search.TorrentPayload
import tv.torrserve.torrentsearch.model.TorrentIdentity
import tv.torrserve.torrentsearch.provider.SearchTransport

class TorrentSearchNetwork(private val http: Http, private val adaptive: AdaptiveSearchNetwork? = null) : SearchTransport {
    private fun request(url: String) = Request.Builder().url(requireNotNull(url.toHttpUrlOrNull()))
        .header("User-Agent","TorrFrame-TorrentSearch/1.0")
    override suspend fun get(url: String, headers: Map<String,String>): ByteArray = exchange(
        request(url).apply { headers.forEach { (k,v) -> header(k,v) } }.build(), "TorrentSearch", 3 * 1024 * 1024).data
    override suspend fun post(url: String, body: String, contentType: String): ByteArray = exchange(
        request(url).post(body.toRequestBody("$contentType; charset=utf-8".toMediaType())).build(), "TorrentSearch",3 * 1024 * 1024).data

    private suspend fun exchange(request: Request, service: String, limit: Int, manualRedirects: Boolean = false,
        allowLocal: Boolean = false) = adaptive?.exchange(request, service, limit, manualRedirects, allowLocal)
        ?: http.exchange(request, service, limit, manualRedirects)

    /** Download only after selection, with the same bounds/redirect rules as the existing importer. */
    suspend fun download(urlString: String): TorrentPayload {
        var url = requireNotNull(urlString.toHttpUrlOrNull())
        repeat(6) {
            val reply = exchange(request(url.toString()).build(), "TorrentSearch", 16 * 1024 * 1024, manualRedirects = true, allowLocal = true)
            if (reply.status in 200..299) {
                val text = reply.data.take(4096).toByteArray().toString(Charsets.UTF_8).trim()
                if (TorrentIdentity.hash(text) != null && text.startsWith("magnet:?",true)) return TorrentPayload.Magnet(text)
                require(reply.data.size >= 16 && reply.data.first() == 'd'.code.toByte()) { "Invalid torrent file" }
                return TorrentPayload.File(reply.data)
            }
            reply.location?.takeIf { it.startsWith("magnet:?",true) && TorrentIdentity.hash(it) != null }?.let { return TorrentPayload.Magnet(it) }
            val next = requireNotNull(reply.location?.let(url::resolve))
            require(!(url.scheme == "https" && next.scheme == "http"))
            url = next
        }
        error("Too many torrent redirects")
    }
}
