package tv.torrserve.silicon.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.core.storage.UiScale

data class SettingsUi(val draft: Settings = Settings(), val message: String = "", val busy: Boolean = false, val connection: Int = tv.torrserve.silicon.R.string.settings_disconnected)
class SettingsViewModel(private val graph: AppGraph): ViewModel() {
    private val mutable = MutableStateFlow(SettingsUi())
    val state = mutable.asStateFlow()
    private var baseline = Settings()
    init { viewModelScope.launch {
        try {
        graph.ready.await()
        baseline = graph.settings.state.value
        mutable.update { it.copy(draft = baseline) }
        graph.settings.state.collect { s ->
            mutable.update { it.copy(draft = tv.torrserve.silicon.core.storage.SettingsMerge.rebase(it.draft, baseline, s)) }
            baseline = s
        }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(message = graph.app.getString(tv.torrserve.silicon.R.string.settings_load_failed)) }
        }
    } }
    fun edit(block: (Settings) -> Settings) { mutable.update {
        val s = block(it.draft); it.copy(draft = s, connection = if(s.jackettUrl != it.draft.jackettUrl || s.jackettKey != it.draft.jackettKey) tv.torrserve.silicon.R.string.settings_disconnected else it.connection)
    } }
    fun moveProvider(provider: String, direction: Int) {
        edit { it.copy(metadataOrder = tv.torrserve.silicon.core.storage.MetadataPriority.move(it.metadataOrder, provider, direction)) }
    }
    fun scale(direction: Int) = appearance { it.copy(uiScalePercent = UiScale.adjust(it.uiScalePercent, direction)) }
    fun resetScale() = appearance { it.copy(uiScalePercent = UiScale.DEFAULT) }
    fun theme(value: String) = appearance { it.copy(uiTheme = value) }
    fun hints(value: Boolean) = appearance { it.copy(showNavigationHints = value) }
    fun language(value: String) = appearance { it.copy(uiLanguage = value) }
    private fun appearance(change: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                graph.settings.update(block = change)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = graph.app.getString(tv.torrserve.silicon.R.string.settings_operation_failed)) }
            }
        }
    }
    private fun action(block: suspend () -> String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { graph.ready.await(); val message = block(); mutable.update { it.copy(message = message) } }
            catch (e: Exception) { if (e is CancellationException) throw e; mutable.update {
                val failure = e as? tv.torrserve.silicon.core.network.ServiceFailure
                val status = if(failure?.service == "Jackett") when(failure.status) { 401,403 -> tv.torrserve.silicon.R.string.settings_invalid_key; 408 -> tv.torrserve.silicon.R.string.server_timeout; else -> tv.torrserve.silicon.R.string.server_connection_error } else it.connection
                it.copy(message = graph.app.getString(tv.torrserve.silicon.R.string.settings_operation_failed), connection = status)
            } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun save() = action {
        val s = state.value.draft
        if (s.jackettUrl.isNotBlank()) graph.jackett.endpoint(s, "caps")
        val base = baseline
        graph.settings.update { current -> tv.torrserve.silicon.core.storage.SettingsMerge.rebase(s, base, current) }
        graph.app.getString(tv.torrserve.silicon.R.string.settings_saved)
    }
    fun test() = action { graph.jackett.test(state.value.draft); mutable.update { it.copy(connection = tv.torrserve.silicon.R.string.server_connected) }; graph.app.getString(tv.torrserve.silicon.R.string.settings_jackett_connected) }
    fun clearCache() = action { graph.store.clearCache(); graph.app.getString(tv.torrserve.silicon.R.string.settings_metadata_cleared) }
}
