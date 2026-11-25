package tv.torrframe.features.settings

import tv.torrframe.core.localization.uiString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrframe.AppGraph
import tv.torrframe.R
import tv.torrframe.core.server.ApplicationActionFailure
import tv.torrframe.core.storage.JacRedMode
import tv.torrframe.core.storage.JacRedSettings
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.storage.UiScale
import tv.torrframe.features.torrentsearch.data.*
import tv.torrserve.torrentsearch.model.MediaType
import tv.torrserve.torrentsearch.model.SearchTarget
import tv.torrframe.ui.sound.UiSound

data class SettingsUi(val draft: Settings = Settings(), val message: String = "", val busy: Boolean = false,
    val connection: Int = R.string.settings_disconnected, val jacRedTesting: Boolean = false, val jacRedStatus: Int? = null,
    val dpiQuery: String = "", val dpiType: MediaType = MediaType.Movie,
    val dpiReport: SearchComparison? = null, val dpiMessage: Int? = null)
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
            mutable.update {
                val draft = tv.torrframe.core.storage.SettingsMerge.rebase(it.draft, baseline, s)
                it.copy(draft = draft, message = if(s.uiLanguage != baseline.uiLanguage) "" else it.message,
                    jacRedStatus = it.jacRedStatus.takeIf { _ -> draft.jacRed == it.draft.jacRed })
            }
            baseline = s
        }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(message = graph.app.uiString(tv.torrframe.R.string.settings_load_failed)) }
        }
    } }
    fun edit(block: (Settings) -> Settings) {
        if (block(state.value.draft) == state.value.draft) return
        mutable.update {
            val s = block(it.draft)
            it.copy(draft = s, message = "", jacRedStatus = it.jacRedStatus.takeIf { _ -> s.jacRed == it.draft.jacRed },
                connection = if (s.jackettEnabled != it.draft.jackettEnabled || s.jackettUrl != it.draft.jackettUrl || s.jackettKey != it.draft.jackettKey)
                tv.torrframe.R.string.settings_disconnected else it.connection)
        }
        // Preserve writes when the Activity closes; the store serializes field updates.
        graph.scope.launch {
            try {
                graph.ready.await()
                graph.settings.update { current ->
                    val next = block(current)
                    if (next.jackettEnabled && next.jackettUrl != current.jackettUrl && next.jackettUrl.isNotBlank() && next.jackettKey.isNotBlank()) graph.jackett.endpoint(next, "caps")
                    next
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = graph.app.uiString(tv.torrframe.R.string.settings_operation_failed)) }
            }
        }
    }
    val telemetry get() = graph.telemetry
    fun restartSetup() = graph.setup.restart()
    val developer get() = graph.developer
    val externalPlayers get() = graph.externalPlayers
    val torrentProviders get() = graph.torrentSearch.providers
    fun catalogNetwork(change: (tv.torrframe.core.network.CatalogNetworkSettings) -> tv.torrframe.core.network.CatalogNetworkSettings) =
        edit { it.copy(catalogNetwork = change(it.catalogNetwork).validated()) }
    fun torrentSearch(change: (tv.torrserve.torrentsearch.model.TorrentSearchOptions) -> tv.torrserve.torrentsearch.model.TorrentSearchOptions) =
        edit { it.copy(torrentSearch = change(it.torrentSearch).normalized()) }
    val dpiStatus get() = graph.adaptiveSearch.process.status
    val dpiBusy get() = graph.adaptiveDiagnostics.busy
    fun adaptiveMode(mode: AdaptiveMode) = edit { it.copy(adaptiveMode = mode) }
    fun dpiQuery(value: String) = mutable.update { it.copy(dpiQuery = value.trim().take(160)) }
    fun dpiType(value: MediaType) = mutable.update { it.copy(dpiType = value) }
    fun resetDpiStrategies() {
        graph.adaptiveSearch.resetStrategies()
        mutable.update { it.copy(dpiReport = null, dpiMessage = R.string.dpi_reset_done) }
        graph.uiSounds.play(UiSound.Success)
    }
    fun runAdaptive(diagnostic: AdaptiveDiagnostic) {
        val (query, type) = state.value.let { it.dpiQuery to it.dpiType }
        if (query.isBlank() || dpiBusy.value) return
        mutable.update { it.copy(dpiReport = null, dpiMessage = null) }
        viewModelScope.launch {
            try {
                graph.ready.await()
                val report = graph.adaptiveDiagnostics.run(SearchTarget(query, type = type), diagnostic)
                mutable.update { it.copy(dpiReport = report) }
                graph.uiSounds.play(UiSound.Success)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(dpiMessage = (error as? ApplicationActionFailure)?.resource ?: R.string.settings_operation_failed) }
                graph.uiSounds.play(UiSound.Unavailable)
            }
        }
    }
    fun jacRedMode(mode: JacRedMode) = editJacRed { it.copy(mode = mode) }
    fun jacRedEndpoint(address: String) = editJacRed { it.updated(address, mode = JacRedMode.Custom) }
    fun jacRedKey(key: String) = editJacRed { it.updated(key = key) }
    private fun editJacRed(change: (JacRedSettings) -> JacRedSettings) {
        try {
            edit { it.copy(jacRed = change(it.jacRed)) }
        } catch (failure: ApplicationActionFailure) {
            graph.uiSounds.play(UiSound.Unavailable)
            mutable.update { it.copy(jacRedStatus = failure.resource) }
        }
    }
    fun resetJacRed() {
        edit { settings ->
            settings.copy(jacRed = JacRedSettings(), torrentSearch = settings.torrentSearch.copy(
                enabledProviders = settings.torrentSearch.enabledProviders?.plus(JacRedSearchProvider.ID)))
        }
        mutable.update { it.copy(jacRedStatus = null) }
    }
    fun testJacRed() {
        if (state.value.jacRedTesting) return
        val connection = state.value.draft.jacRed
        mutable.update { it.copy(jacRedTesting = true, jacRedStatus = null) }
        viewModelScope.launch {
            try {
                val status = try {
                    graph.ready.await()
                    graph.jacRed.test(connection)
                    R.string.jacred_connected
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    (failure as? ApplicationActionFailure)?.resource ?: R.string.jacred_connection_failed
                }
                if (state.value.draft.jacRed == connection) {
                    graph.uiSounds.play(if (status == R.string.jacred_connected) UiSound.Success else UiSound.Unavailable)
                    mutable.update { it.copy(jacRedStatus = status) }
                }
            } finally {
                mutable.update { it.copy(jacRedTesting = false) }
            }
        }
    }
    fun moveProvider(provider: String, direction: Int) {
        edit { it.copy(metadataOrder = tv.torrframe.core.storage.MetadataPriority.move(it.metadataOrder, provider, direction)) }
    }
    fun scale(direction: Int) = appearance { it.copy(uiScalePercent = UiScale.adjust(it.uiScalePercent, direction)) }
    fun resetScale() = appearance { it.copy(uiScalePercent = UiScale.DEFAULT) }
    fun theme(value: String) = appearance { it.copy(uiTheme = value) }
    fun sounds(value: Boolean) = appearance { it.copy(uiSoundsEnabled = value) }
    fun animations(value: Boolean) = appearance { it.copy(animationsEnabled = value) }
    fun hints(value: Boolean) = appearance { it.copy(showNavigationHints = value) }
    fun language(value: String) = appearance { it.copy(uiLanguage = value) }
    fun accent(value: String) = appearance { it.copy(uiAccent = value) }
    private fun appearance(change: (Settings) -> Settings) = edit(change)
    private fun action(block: suspend () -> String) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { graph.ready.await(); val message = block(); graph.uiSounds.play(tv.torrframe.ui.sound.UiSound.Success); mutable.update { it.copy(message = message) } }
            catch (e: Exception) { if (e is CancellationException) throw e; graph.uiSounds.play(tv.torrframe.ui.sound.UiSound.Unavailable); mutable.update {
                val failure = e as? tv.torrframe.core.network.ServiceFailure
                val status = if(failure?.service == "Jackett") when(failure.status) { 401,403 -> tv.torrframe.R.string.settings_invalid_key; 408 -> tv.torrframe.R.string.server_timeout; else -> tv.torrframe.R.string.server_connection_error } else it.connection
                it.copy(message = graph.app.uiString(tv.torrframe.R.string.settings_operation_failed), connection = status)
            } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun test() = action { graph.jackett.test(state.value.draft); mutable.update { it.copy(connection = tv.torrframe.R.string.server_connected) }; graph.app.uiString(tv.torrframe.R.string.settings_jackett_connected) }
    fun clearCache() = action { graph.store.clearCache(); graph.app.uiString(tv.torrframe.R.string.settings_metadata_cleared) }
}
