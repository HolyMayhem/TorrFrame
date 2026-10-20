package tv.torrframe.features.library

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.torrframe.core.database.LibraryStore
import tv.torrframe.core.models.*
import tv.torrframe.core.server.*
import tv.torrframe.core.storage.SettingsStore
import tv.torrframe.features.search.*
import tv.torrframe.features.metadata.MetadataResolver
import tv.torrframe.core.performance.Diagnostics

class LibraryRepository(val store: LibraryStore, private val api: TorrServerApi,
    private val server: ServerController, private val jackett: JackettClient,
    private val metadata: MetadataResolver, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val adding = Mutex()
    private data class Sync(val task: Deferred<Unit>, var users: Int = 1)
    private val syncing = Mutex()
    private val syncRequests = mutableMapOf<Pair<String, Boolean>, Sync>()
    suspend fun sync() {
        val settings = settings.state.value
        val key = settings.torrServerUrl to settings.useLocalServer
        val request = syncing.withLock {
            syncRequests[key]?.takeUnless { it.task.isCompleted }?.also { it.users++ }
                ?: Sync(scope.async(start = CoroutineStart.LAZY) {
                    server.requireRunning()
                    Diagnostics.timed("library_load") { store.putAll(api.at(key.first).list()) }
                }).also { syncRequests[key] = it }
        }
        try { request.task.await() }
        finally { withContext(NonCancellable) { syncing.withLock {
            if (--request.users == 0) {
                if (syncRequests[key] === request) syncRequests.remove(key)
                if (!request.task.isCompleted) request.task.cancel()
            }
        } } }
    }
    suspend fun add(result: SearchResult): String = import(result.title, result.hash) { jackett.download(result) }
    suspend fun add(payload: TorrentPayload, title: String, hash: String = ""): String = import(title, hash) { payload }
    private suspend fun import(title: String, hash: String, payload: suspend () -> TorrentPayload): String = adding.withLock {
        server.requireRunning()
        val existing = hash.takeIf { it.isNotBlank() }?.let { store.item(it) }
        // A local row can survive a server failure before TorrServer persists its metadata.
        // Re-add the search payload when the server no longer has that hash.
        if (existing != null && api.list().any { it.hash == existing.hash }) return@withLock existing.hash
        val torrent = when(val value = payload()) {
            is TorrentPayload.Magnet -> api.addMagnet(value.value, title)
            is TorrentPayload.File -> api.upload(value.value, title)
        }
        store.put(torrent, title); enrich(torrent.hash); torrent.hash
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
