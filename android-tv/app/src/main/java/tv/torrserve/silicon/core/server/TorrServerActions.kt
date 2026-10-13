package tv.torrserve.silicon.core.server

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.storage.SettingsStore

class ApplicationActionFailure(val resource: Int) : IllegalStateException()
data class EngineResult(val settings: JSONObject, val message: Int)

/** Shared commands used by TV ViewModels and optional clients. No network listener. */
class TorrServerActions(private val context: Context, private val store: SettingsStore,
    private val server: ServerController, private val api: TorrServerApi, private val connection: ServerConnection,
    private val applicationScope: CoroutineScope,
    private val reservedPort: () -> Int? = { null }) {
    private val mutations = Mutex()
    private suspend fun <T> applicationMutation(block: suspend () -> T): T {
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
        val job = applicationScope.async {
            mutations.withLock {
                if (!phase.compareAndSet(0, 1)) throw CancellationException()
                block()
            }
        }
        try { return job.await() }
        catch (e: CancellationException) {
            if (phase.compareAndSet(0, 2)) job.cancel()
            throw e
        }
    }
    private fun idle() { if (server.playbackOwned) throw ApplicationActionFailure(R.string.server_stop_playback) }
    private fun local() { check(store.state.value.useLocalServer) }
    private fun service() {
        try { LocalServerService.start(context) }
        catch (_: RuntimeException) { server.backgroundError(context.getString(R.string.boot_restricted)); throw ApplicationActionFailure(R.string.boot_restricted) }
    }
    suspend fun connect(address: String, local: Boolean, port: Int, expectedRevision: Long? = null): Int = applicationMutation {
        if (expectedRevision != null && store.revision.value != expectedRevision) throw tv.torrserve.silicon.core.storage.SettingsConflict()
        idle()
        val normalized = ServerAddress.normalize(address)
        require(port in 1024..65535 && (!local || port != reservedPort()))
        val previous = store.state.value
        if (previous.torrServerUrl != normalized || previous.useLocalServer != local || previous.port != port) {
            LocalServerService.stop(context); server.backgroundOwned = false
            try { server.changeConnection { store.update(expectedRevision) { it.copy(torrServerUrl = normalized, useLocalServer = local, port = port) } } }
            catch (e: Exception) {
                if (previous.useLocalServer) { server.start(); if (previous.startOnBoot && server.state.value is ServerState.Running) service() }
                throw e
            }
        }
        if (local) { server.start(); if (store.state.value.startOnBoot && server.state.value is ServerState.Running) service() }
        if (connection.check(normalized).status == ConnectionStatus.Connected) R.string.server_address_saved else R.string.server_saved_unavailable
    }
    suspend fun start() = applicationMutation { local(); server.requireRunning(); if (store.state.value.startOnBoot) service(); connection.check(); Unit }
    suspend fun stop() = applicationMutation { local(); idle(); LocalServerService.stop(context); server.backgroundOwned = false; server.stop(); connection.check(); Unit }
    suspend fun restart() = applicationMutation { local(); idle(); server.restart(); if (store.state.value.startOnBoot && server.state.value is ServerState.Running) service(); connection.check(); Unit }
    suspend fun boot(enabled: Boolean, expectedRevision: Long? = null) = applicationMutation { local(); store.update(expectedRevision) { it.copy(startOnBoot = enabled) }; if (enabled) service() else LocalServerService.stop(context); Unit }
    private suspend fun target(): TorrServerApi { server.requireRunning(); return api.at(store.state.value.torrServerUrl) }
    suspend fun loadEngine(): Pair<String, JSONObject> {
        val api = target()
        val version = withTimeout(12_000) { api.version() }
        val json = api.settings()
        if (EngineSettings.fields.none { it.supported(json) }) throw ApplicationActionFailure(R.string.server_api_unsupported)
        return version to json
    }
    suspend fun saveEngine(address: String, edits: Map<String, String>, base: Map<String, String> = emptyMap()): EngineResult = applicationMutation {
        idle(); check(address == store.state.value.torrServerUrl)
        val api = target(); val current = api.settings()
        edits.forEach { (key, text) ->
            val field = EngineSettings.fields.firstOrNull { it.key == key } ?: error("Unsupported setting")
            val value = field.parse(text)
            if (key == "PeersListenPort" && value is Long && value > 0 && value.toInt() == reservedPort()) throw ApplicationActionFailure(R.string.phone_port_conflict)
            require(key != "PeersListenPort" || value !is Long || value !in 1..1023)
        }
        for ((key, oldValue) in base) {
            require(key in edits)
            val field = EngineSettings.fields.first { it.key == key }
            if (!field.supported(current) || field.display(current) != oldValue) throw ApplicationActionFailure(R.string.engine_changed)
        }
        val expected = EngineSettings.merge(current, edits)
        api.setSettings(expected)
        val actual = api.settings()
        try { EngineSettings.verify(expected, actual, edits.keys) }
        catch (_: IllegalStateException) { throw ApplicationActionFailure(R.string.engine_not_saved) }
        EngineResult(actual, memoryBudget(actual, R.string.engine_saved))
    }
    suspend fun resetEngine(address: String): EngineResult = applicationMutation {
        idle(); check(address == store.state.value.torrServerUrl)
        val api = target()
        if (withTimeout(12_000) { api.version() } != "MatriX.146.1") throw ApplicationActionFailure(R.string.server_api_unsupported)
        api.resetSettings(); val actual = api.settings()
        try { EngineSettings.verify(EngineSettings.merge(actual, EngineSettings.defaults), actual, EngineSettings.defaults.keys) }
        catch (_: IllegalStateException) { throw ApplicationActionFailure(R.string.engine_not_saved) }
        EngineResult(actual, memoryBudget(actual, R.string.engine_reset_done))
    }
    private suspend fun memoryBudget(engine: JSONObject, message: Int): Int {
        val saved = store.state.value
        if (!saved.useLocalServer || !engine.has("CacheSize")) return message
        val mb = ((engine.getLong("CacheSize") + 1048575) / 1048576).toInt().coerceIn(16, 4096)
        if (mb == saved.cacheMb) return message
        store.update { it.copy(cacheMb = mb) }; return R.string.engine_restart_memory
    }
}
