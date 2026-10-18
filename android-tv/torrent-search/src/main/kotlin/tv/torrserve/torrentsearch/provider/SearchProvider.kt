package tv.torrserve.torrentsearch.provider

import tv.torrserve.torrentsearch.model.*

/** Network implementation is injected by the host app, without any server dependency. */
interface SearchTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray
    suspend fun post(url: String, body: String, contentType: String): ByteArray
}
interface SearchProvider {
    val id: String
    val name: String
    val types: Set<MediaType> get() = MediaType.entries.toSet()
    suspend fun search(query: String, type: MediaType): List<TorrentResult>
    suspend fun resolve(result: TorrentResult): TorrentLink {
        result.magnet?.takeIf { TorrentIdentity.hash(it) != null }?.let { return TorrentLink.Magnet(it) }
        TorrentIdentity.web(result.torrentUrl)?.let { return TorrentLink.FileUrl(it) }
        error("No downloadable torrent link")
    }
}
