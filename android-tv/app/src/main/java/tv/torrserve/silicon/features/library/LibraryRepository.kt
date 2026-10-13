package tv.torrserve.silicon.features.library

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.features.search.*
import tv.torrserve.silicon.features.metadata.MetadataResolver
import tv.torrserve.silicon.core.performance.Diagnostics

class LibraryRepository(val store: LibraryStore, private val api: TorrServerApi,
    private val server: ServerController, private val jackett: JackettClient,
    private val metadata: MetadataResolver, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val adding = Mutex()
    suspend fun sync() {
        server.requireRunning(); Diagnostics.timed("library_load") { api.list().forEach { store.put(it) } }
    }
    suspend fun add(result: SearchResult): String = adding.withLock {
        server.requireRunning()
        val existing = result.hash.takeIf { it.isNotBlank() }?.let { hash -> store.item(hash) }
        // A local row can survive a server failure before TorrServer persists its metadata.
        // Re-add the search payload when the server no longer has that hash.
        if (existing != null && api.list().any { it.hash == existing.hash }) return@withLock existing.hash
        val torrent = when(val payload = jackett.download(result)) {
            is TorrentPayload.Magnet -> api.addMagnet(payload.value, result.title)
            is TorrentPayload.File -> api.upload(payload.value, result.title)
        }
        store.put(torrent, result.title); enrich(torrent.hash); torrent.hash
    }
    suspend fun addMagnet(value: String): String = adding.withLock {
        server.requireRunning(); val torrent = api.addMagnet(value, "")
        store.put(torrent); enrich(torrent.hash); torrent.hash
    }
    fun enrich(hash: String) = scope.launch { refreshMetadata(hash) }
    suspend fun refreshMetadata(hash: String, force: Boolean = false): Boolean {
        val item = store.item(hash) ?: return false
        if(!force && item.metadata != null) return true
        val sources = listOfNotNull(item.sourceTitle.takeIf { it.isNotBlank() && it != "Torrent" }) + item.files.filter { it.isVideo }.take(3).map { it.path.substringAfterLast('/') }
        if(sources.isEmpty()) return false
        val ticket = store.metadataTicket(hash, automatic = !force) ?: return false
        val value = metadata.resolve(sources, settings.state.value, force) ?: return false
        return store.metadata(hash, value, ticket)
    }
    suspend fun files(hash: String): List<TorrentFile> {
        server.requireRunning()
        val torrent = withTimeout(60_000) {
            var t = api.get(hash)
            while (t.files.isEmpty()) { delay(1000); t = api.get(hash) }
            t
        }
        store.put(torrent); enrich(hash); return torrent.files.filter { it.isVideo }
    }
    suspend fun remove(hash: String) {
        server.requireRunning(); api.remove(hash); store.remove(hash)
    }
}
