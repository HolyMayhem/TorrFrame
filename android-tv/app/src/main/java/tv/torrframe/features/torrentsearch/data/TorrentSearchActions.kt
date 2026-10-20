package tv.torrframe.features.torrentsearch.data

import kotlinx.coroutines.withTimeout
import tv.torrframe.AppGraph
import tv.torrframe.core.models.TorrentFile
import tv.torrframe.features.player.preferredVideo
import tv.torrframe.features.search.TorrentPayload
import tv.torrframe.features.search.JackettSearchProvider
import tv.torrframe.features.search.jackettResult
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
        val payload = withTimeout(30_000) {
            if (row.providerId == JackettSearchProvider.ID) {
                require(graph.settings.state.value.jackettEnabled) { "Jackett is disabled." }
                graph.jackett.download(row.jackettResult())
            } else when(val link = engine.resolve(row)) {
            is TorrentLink.Magnet -> TorrentPayload.Magnet(link.value)
            is TorrentLink.FileUrl -> graph.torrentSearchNetwork.download(link.value)
        } }
        return graph.library.add(payload,row.title,row.infoHash.orEmpty())
    }
    override suspend fun files(hash: String) = graph.library.files(hash)
    override suspend fun preferred(hash: String, files: List<TorrentFile>) = preferredVideo(graph.store.item(hash), files)
}
