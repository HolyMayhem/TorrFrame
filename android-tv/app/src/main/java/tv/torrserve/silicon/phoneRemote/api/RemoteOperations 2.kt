package tv.torrserve.silicon.phoneRemote.api

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import tv.torrserve.silicon.core.server.ApplicationActionFailure
import tv.torrserve.silicon.core.storage.SettingsConflict
import tv.torrserve.silicon.phoneRemote.security.RemoteSession
import java.util.UUID

data class RemoteOperation(val id: String, val owner: String, val requestId: String, val type: String,
    val status: String = "running", val result: JSONObject? = null, val error: String = "", val conflict: Boolean = false) {
    fun json(includeResult: Boolean = false) = JSONObject().put("id", id).put("requestId", requestId).put("type", type)
        .put("status", status).put("error", error).put("conflict", conflict).apply { if (includeResult) put("result", result ?: JSONObject.NULL) }
}
class RemoteOperations(private val scope: CoroutineScope, private val execute: suspend (RemoteSession, String, JSONObject) -> JSONObject,
    private val errorMessage: (Exception) -> String) {
    private val records = LinkedHashMap<String, RemoteOperation>()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(2)
    private val mutable = MutableSharedFlow<JSONObject>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutable.asSharedFlow()
    @Synchronized fun summaries(session: RemoteSession) = records.values.filter { it.owner == session.id }.map { it.json() }
    @Synchronized fun find(session: RemoteSession, id: String) = records[id]?.takeIf { it.owner == session.id }?.json(true)
    @Synchronized private fun update(operation: RemoteOperation) { records[operation.id] = operation; mutable.tryEmit(operation.json()) }
    @Synchronized fun revokeExcept(active: Set<String>) {
        records.values.filter { it.owner !in active && it.status == "running" }.forEach { jobs[it.id]?.cancel() }
    }
    @Synchronized fun submit(session: RemoteSession, request: JSONObject): JSONObject {
        val requestId = request.getString("requestId"); require(requestId.matches(Regex("[A-Za-z0-9_-]{16,64}")))
        records.values.firstOrNull { it.owner == session.id && it.requestId == requestId }?.let { return JSONObject().put("operationId", it.id) }
        check(records.values.count { it.status == "running" } < 8)
        if (records.size >= 32) records.entries.firstOrNull { it.value.status != "running" }?.let { records.remove(it.key) }
        val type = request.getString("type"); require(type in RemoteCommands.supported)
        val args = request.optJSONObject("args") ?: JSONObject()
        val operation = RemoteOperation(UUID.randomUUID().toString(), session.id, requestId, type)
        records[operation.id] = operation
        mutable.tryEmit(operation.json())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(minOf(120_000, (session.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))) { permits.withPermit { execute(session, type, args) } }
                check(result.toString().toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Remote response limit" }
                update(operation.copy(status = "completed", result = result))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) {
                    update(operation.copy(status = "cancelled")); throw e
                }
                update(operation.copy(status = "failed", error = errorMessage(e), conflict = e is SettingsConflict))
            } finally { jobs.remove(operation.id) }
        }
        jobs[operation.id] = job; job.start()
        return JSONObject().put("operationId", operation.id)
    }
}
