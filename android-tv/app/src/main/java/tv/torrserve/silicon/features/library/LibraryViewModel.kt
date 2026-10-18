package tv.torrserve.silicon.features.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.core.models.*

data class LibraryUi(val items: List<LibraryItem> = emptyList(), val busy: Boolean = false,
    val message: String = "", val files: List<TorrentFile> = emptyList(), val fileHash: String? = null, val syncFailed: Boolean = false, val syncFailure: Int? = null)
class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    val initialLocalLoad = CompletableDeferred<Unit>()
    private val mutable = MutableStateFlow(LibraryUi())
    val state = mutable.asStateFlow()
    val metadataEditor get() = graph.metadataEditor
    init {
        viewModelScope.launch {
            try { graph.store.library.collect { items ->
                mutable.update { it.copy(items = items) }; initialLocalLoad.complete(Unit)
            } } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = friendly(e)) }
                initialLocalLoad.complete(Unit)
            }
        }
        refresh()
    }
    fun clearMessage() { mutable.update { it.copy(message = "") } }
    private fun operation(onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { graph.ready.await(); block() }
            catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; onFailure(e); mutable.update { it.copy(message = friendly(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() = operation(onFailure = { error -> mutable.update { it.copy(syncFailed = true, syncFailure = (error as? tv.torrserve.silicon.core.network.ServiceFailure)?.status) } }) {
        graph.library.sync(); mutable.update { it.copy(syncFailed = false, syncFailure = null) }
    }
    fun addMagnet(value: String, done: (String) -> Unit) = operation { done(graph.library.addMagnet(value)) }
    fun files(hash: String, done: (List<TorrentFile>) -> Unit = {}) = operation {
        val files = graph.library.files(hash)
        if (files.isEmpty()) { mutable.update { it.copy(message = "No supported video files were found.") }; return@operation }
        mutable.update { it.copy(files = files, fileHash = hash) }; done(files)
    }
    fun closeFiles() { mutable.update { it.copy(files = emptyList(), fileHash = null) } }
    fun remove(hash: String, done: () -> Unit) = operation { graph.library.remove(hash); done() }
    fun metadata(hash: String) = operation {
        val found = graph.library.refreshMetadata(hash, force = true)
        mutable.update { it.copy(message = if(found) "Metadata updated." else "No confident match found, or the provider is unavailable. The original title is retained.") }
    }
}
fun friendly(e: Exception): String = when(e) {
    is tv.torrserve.silicon.core.network.ServiceFailure -> e.message.orEmpty()
    is IllegalArgumentException, is IllegalStateException -> e.message?.take(200) ?: "Unable to complete the action."
    is TimeoutCancellationException -> "Timed out waiting for torrent metadata. Try again later."
    else -> "Unable to complete the action. Check the server and network connection."
}
