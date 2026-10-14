package tv.torrserve.silicon.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.network.ServiceFailure
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.Settings

data class TorrServerSettingsUi(val settings: Settings = Settings(), val busy: Boolean = false,
    val message: String = "", val diagnostics: ConnectionDiagnostics? = null,
    val engine: JSONObject? = null, val engineAddress: String = "", val engineVersion: String = "",
    val edits: Map<String, String> = emptyMap(), val backgroundError: String = "", val webUrl: String = "")

class TorrServerSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(TorrServerSettingsUi())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch { graph.serverWebAddress.url.collect { url -> mutable.update { it.copy(webUrl = url) } } }
        viewModelScope.launch { graph.server.backgroundError.collect { message ->
            mutable.update { it.copy(backgroundError = message) }
        } }
        viewModelScope.launch {
            graph.settings.state.collect { settings -> mutable.update {
                val changed = settings.torrServerUrl != it.settings.torrServerUrl || settings.useLocalServer != it.settings.useLocalServer
                it.copy(settings = settings, engine = if (changed) null else it.engine,
                    edits = if (changed) emptyMap() else it.edits, diagnostics = if (changed) null else it.diagnostics)
            } }
        }
    }
    private fun text(id: Int, vararg values: Any) = graph.app.getString(id, *values)
    private fun message(id: Int) { mutable.update { it.copy(message = text(id)) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { graph.ready.await(); block() }
            catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                message(if (e is ApplicationActionFailure) e.resource else when ((e as? ServiceFailure)?.status) {
                    401, 403 -> R.string.server_access_denied
                    404, 405, 501 -> R.string.server_api_unsupported
                    408 -> R.string.server_timeout
                    -2 -> R.string.server_invalid_response
                    else -> if (e is TimeoutCancellationException) R.string.server_timeout else R.string.server_operation_failed
                })
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun available(): Pair<Settings, TorrServerApi> {
        val settings = graph.settings.state.value
        graph.server.requireRunning()
        return settings to graph.api.at(settings.torrServerUrl)
    }
    fun address(value: String) {
        val normalized = runCatching { ServerAddress.normalize(value) }.getOrElse { message(R.string.server_invalid_url); return }
        val current = graph.settings.state.value
        val local = current.useLocalServer && ServerAddress.isLocal(normalized, current.port)
        connect(normalized, local, current.port)
    }
    fun local(enabled: Boolean) {
        val current = graph.settings.state.value
        connect(if (enabled) ServerAddress.local(current.port) else current.torrServerUrl, enabled, current.port)
    }
    fun port(value: String) {
        val port = value.toIntOrNull()
        if (port == null || port !in 1024..65535) { message(R.string.server_invalid_port); return }
        connect(ServerAddress.local(port), true, port)
    }
    fun defaultAddress() = connect(ServerAddress.DEFAULT, true, 8090)
    private fun connect(address: String, local: Boolean, port: Int) = action {
        message(graph.serverActions.connect(address, local, port))
    }
    fun boot(enabled: Boolean) = action { graph.serverActions.boot(enabled) }
    fun lan(enabled: Boolean) = action { message(graph.serverActions.lan(enabled)) }
    fun check() = action { graph.connection.check() }
    fun diagnose() = action {
        mutable.update { it.copy(diagnostics = null) }
        val (settings, _) = available()
        val result = graph.connection.diagnose(settings.torrServerUrl)
        mutable.update { it.copy(diagnostics = result) }
        graph.connection.check(settings.torrServerUrl)
    }
    fun start() = action { graph.serverActions.start() }
    fun stop() = action { graph.serverActions.stop() }
    fun restart() = action { graph.serverActions.restart() }
    suspend fun requestEngineLoad() {
        state.first { !it.busy }
        loadEngine()
    }
    fun loadEngine() = action {
        val (version, current) = graph.serverActions.loadEngine()
        mutable.update { it.copy(engine = current, engineAddress = graph.settings.state.value.torrServerUrl, engineVersion = version, edits = emptyMap()) }
    }
    fun editEngine(field: EngineField, value: String) {
        if (state.value.busy) return
        val parsed = runCatching { field.parse(value) }.getOrNull()
        if (parsed == null) {
            mutable.update { it.copy(message = text(R.string.engine_invalid_value, field.minimum, field.maximum)) }; return
        }
        if (field.key == "PeersListenPort" && parsed is Long && parsed in 1..1023) { message(R.string.server_invalid_port); return }
        mutable.update { it.copy(edits = it.edits + (field.key to value), message = "") }
    }
    fun saveEngine() = action {
        val snapshot = state.value
        if (snapshot.engine == null || snapshot.edits.isEmpty()) { message(R.string.engine_no_changes); return@action }
        val result = graph.serverActions.saveEngine(snapshot.engineAddress, snapshot.edits, snapshot.edits.keys.associateWith { key -> EngineSettings.fields.first { it.key == key }.display(snapshot.engine) })
        mutable.update { it.copy(engine = result.settings, edits = emptyMap(), message = text(result.message)) }
    }
    fun resetEngine() = action {
        val result = graph.serverActions.resetEngine(state.value.engineAddress)
        mutable.update { it.copy(engine = result.settings, edits = emptyMap(), message = text(result.message)) }
    }
}
