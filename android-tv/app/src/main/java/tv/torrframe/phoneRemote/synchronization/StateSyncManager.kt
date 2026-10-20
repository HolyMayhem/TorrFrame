package tv.torrframe.phoneRemote.synchronization

import tv.torrframe.core.localization.withUiLanguage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import tv.torrframe.AppGraph
import tv.torrframe.R
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.server.ApplicationActionFailure
import tv.torrframe.core.storage.SettingsConflict
import tv.torrframe.phoneRemote.api.*
import tv.torrframe.phoneRemote.security.RemoteSession
import tv.torrframe.phoneRemote.server.RemoteBackend
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Derived projections only. All writes go through application-owned repositories. */
class StateSyncManager(private val graph: AppGraph, parent: CoroutineScope, sessions: tv.torrframe.phoneRemote.security.SessionManager) : RemoteBackend {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> emit("sync.error", JSONObject()) })
    override val epoch = UUID.randomUUID().toString()
    private val counter = AtomicLong()
    override val sequence get() = counter.get()
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = mutable.asSharedFlow()
    private val commands = RemoteCommands(graph)
    private val operations = RemoteOperations(scope, commands::execute) { error, language ->
        val context = graph.app.withUiLanguage(language)
        when {
            error is ApplicationActionFailure -> context.getString(error.resource)
            error is SettingsConflict -> context.getString(R.string.phone_settings_conflict)
            error is TimeoutCancellationException || (error as? ServiceFailure)?.status == 408 -> context.getString(R.string.server_timeout)
            else -> context.getString(R.string.phone_operation_failed)
        }
    }
    @Synchronized private fun emit(type: String, data: Any) {
        mutable.tryEmit(JSONObject().put("v", 1).put("epoch", epoch).put("sequence", counter.incrementAndGet()).put("type", type).put("data", data).toString())
    }
    init {
        scope.launch { sessions.sessions.collect { active -> operations.revokeExcept(active.map { it.id }.toSet()) } }
        scope.launch { graph.catalogUserLists.entries.collect { emit("catalog.updated", JSONObject()) } }
        scope.launch { graph.store.changes.collect { emit("library.updated", JSONObject().put("revision", it)) } }
        scope.launch { graph.settings.state.collect { val (revision, settings) = graph.settings.snapshot(); emit("settings.updated", RemoteJson.settings(settings, revision)) } }
        scope.launch { combine(graph.server.state, graph.connection.state) { _, _ -> RemoteJson.server(graph).toString() }
            .distinctUntilChanged().collect { emit("torrserver.updated", JSONObject(it)) } }
        scope.launch { graph.playback.state.collect { emit("playback.updated", RemoteJson.playback(it)) } }
        scope.launch { graph.updates.state.collect { emit("updates.updated", RemoteJson.updates(graph)) } }
        scope.launch { operations.changes.collect { emit("operation.updated", it) } }
        scope.launch {
            while (isActive) {
                if (mutable.subscriptionCount.value > 0 && !graph.server.foreground) graph.connection.check()
                delay(15_000)
            }
        }
        scope.launch(Dispatchers.Main.immediate) { while (isActive) { delay(1000); if (mutable.subscriptionCount.value > 0 && graph.playback.state.value.active) graph.playback.refresh() } }
    }
    override suspend fun snapshot(session: RemoteSession): JSONObject {
        // Capture before suspending reads: updates during projection must remain
        // eligible for replay by the client's buffered WebSocket events.
        val startSequence = sequence
        val (revision, settings) = graph.settings.snapshot()
        return JSONObject().put("v", 1).put("epoch", epoch).put("sequence", startSequence)
            .put("searchProviders", org.json.JSONArray(graph.torrentSearch.providers.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("csrf", session.csrf).put("device", RemoteJson.device()).put("settings", RemoteJson.settings(settings, revision))
            .put("library", RemoteJson.library(graph.store.list())).put("server", RemoteJson.server(graph))
            .put("playback", RemoteJson.playback(graph.playback.state.value)).put("updates", RemoteJson.updates(graph))
            .put("operations", org.json.JSONArray(operations.summaries(session)))
    }
    override suspend fun command(session: RemoteSession, request: JSONObject) = operations.submit(session, request)
    override suspend fun operation(session: RemoteSession, id: String) = operations.find(session, id)
    override suspend fun close() { job.cancelAndJoin() }
}
