package tv.torrserve.silicon.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.library.friendly

data class SettingsUi(val draft: Settings = Settings(), val message: String = "", val busy: Boolean = false, val connection: String = "Disconnected")
class SettingsViewModel(private val graph: AppGraph): ViewModel() {
    private val mutable = MutableStateFlow(SettingsUi())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch { graph.settings.state.collect { s -> mutable.update { it.copy(draft = s) } } } }
    fun edit(block: (Settings) -> Settings) { mutable.update {
        val s = block(it.draft); it.copy(draft = s, connection = if(s.jackettUrl != it.draft.jackettUrl || s.jackettKey != it.draft.jackettKey) "Disconnected" else it.connection)
    } }
    fun provider(value: String) { viewModelScope.launch {
        val key = graph.settings.providerKey(value); edit { it.copy(metadataProvider = value, metadataKey = key) }
    } }
    private fun action(block: suspend () -> String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { graph.ready.await(); val message = block(); mutable.update { it.copy(message = message) } }
            catch (e: Exception) { if (e is CancellationException) throw e; mutable.update {
                val failure = e as? tv.torrserve.silicon.core.network.ServiceFailure
                val status = if(failure?.service == "Jackett") when(failure.status) { 401,403 -> "Invalid API Key"; 408 -> "Timeout"; else -> "Connection Error" } else it.connection
                it.copy(message = friendly(e), connection = status)
            } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun save() = action {
        val s = state.value.draft
        if (s.jackettUrl.isNotBlank()) graph.jackett.endpoint(s, "caps")
        val previous = graph.settings.state.value
        if (graph.server.playbackOwned && (previous.port != s.port || previous.cacheMb != s.cacheMb)) error("Stop playback before changing server settings.")
        if (previous.port != s.port) graph.server.stop()
        graph.settings.save(s)
        graph.server.start(); graph.api.configureCache(s.cacheMb)
        "Settings saved."
    }
    fun test() = action { graph.jackett.test(state.value.draft); mutable.update { it.copy(connection = "Connected") }; "Connected to Jackett." }
    fun start() = action { graph.server.requireRunning(); "TorrServer is running." }
    fun stop() = action { graph.server.stop(); "TorrServer stopped." }
    fun restart() = action { graph.server.restart(); "TorrServer restarted." }
    fun clearCache() = action { graph.store.clearCache(); "Metadata cache cleared. Saved library metadata is retained." }
}
