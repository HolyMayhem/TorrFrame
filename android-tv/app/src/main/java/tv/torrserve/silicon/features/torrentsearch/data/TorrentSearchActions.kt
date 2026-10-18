package tv.torrserve.silicon.features.torrentsearch.data

import kotlinx.coroutines.withTimeout
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.features.player.preferredVideo
import tv.torrserve.silicon.features.search.TorrentPayload
import tv.torrserve.torrentsearch.model.*

interface TorrentSearchActions {
    suspend fun add(row: TorrentResult): String
    suspend fun files(hash: String): List<TorrentFile>
    suspend fun preferred(hash: String, files: List<TorrentFile>): TorrentFile? = files.singleOrNull()
}
class AppTorrentSearchActions(private val graph: AppGraph,
    private val engine: tv.torrserve.torrentsearch.engine.TorrentSearchEngine = graph.torrentSearch) : TorrentSearchActions {
    override suspend fun add(row: TorrentResult): String {
        graph.ready.await()
        val payload = withTimeout(30_000) { when(val link = engine.resolve(row)) {
            is TorrentLink.Magnet -> TorrentPayload.Magnet(link.value)
            is TorrentLink.FileUrl -> graph.torrentSearchNetwork.download(link.value)
        } }
        return graph.library.add(payload,row.title,row.infoHash.orEmpty())
    }
    override suspend fun files(hash: String) = graph.library.files(hash)
    override suspend fun preferred(hash: String, files: List<TorrentFile>) = preferredVideo(graph.store.item(hash), files)
}
