package tv.torrserve.torrentsearch.provider

import kotlinx.coroutines.flow.flow
import tv.torrserve.torrentsearch.model.*

/** Network implementation is injected by the host app, without any server dependency. */
interface SearchTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray
    suspend fun post(url: String, body: String, contentType: String): ByteArray
}
interface SearchProvider {
    val id: String
    val name: String
    val enabledByDefault: Boolean get() = true
    val configured: Boolean get() = true
    val types: Set<MediaType> get() = MediaType.entries.toSet()
    fun isEnabled(options: TorrentSearchOptions): Boolean = options.enabledProviders?.contains(id) ?: enabledByDefault
    suspend fun search(query: String, type: MediaType): List<TorrentResult>
    fun searchBatches(target: SearchTarget, mode: SearchTitleMode, timeoutMs: Long) = flow {
        for (query in target.queries(mode)) emit(search(query, target.type))
    }
    suspend fun resolve(result: TorrentResult): TorrentLink {
        result.magnet?.takeIf { TorrentIdentity.hash(it) != null }?.let { return TorrentLink.Magnet(it) }
        TorrentIdentity.web(result.torrentUrl)?.let { return TorrentLink.FileUrl(it) }
        error("No downloadable torrent link")
    }
}
