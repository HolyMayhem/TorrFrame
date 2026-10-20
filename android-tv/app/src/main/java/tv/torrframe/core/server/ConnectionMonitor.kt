package tv.torrframe.core.server

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.storage.Settings

enum class ConnectionStatus { Connecting, Connected, Unavailable, Error }
data class ConnectionState(val status: ConnectionStatus = ConnectionStatus.Connecting, val version: String = "", val failure: Int? = null)
data class ConnectionDiagnostics(val version: String, val latencyMs: Double, val bytes: Int? = null,
    val megabitsPerSecond: Double? = null, val bandwidthFailure: Int? = null)

class ServerConnection(private val api: TorrServerApi, private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ConnectionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val probeSequence = java.util.concurrent.atomic.AtomicLong()
    fun foreground(visible: Boolean) {
        job?.cancel()
        if (visible) job = scope.launch {
            settings.map { it.torrServerUrl }.distinctUntilChanged().collectLatest { address ->
                mutable.value = ConnectionState()
                while (isActive) {
                    check(address)
                    delay(15_000)
                }
            }
        }
    }
    suspend fun check(address: String = settings.value.torrServerUrl): ConnectionState {
        val sequence = probeSequence.incrementAndGet()
        val result = try {
            ConnectionState(ConnectionStatus.Connected, withTimeout(12_000) { api.at(address).version() })
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val failure = (e as? ServiceFailure)?.status ?: if (e is TimeoutCancellationException) 408 else -2
            ConnectionState(if (failure == 0 || failure == 408) ConnectionStatus.Unavailable else ConnectionStatus.Error, failure = failure)
        }
        mutable.update { current ->
            if (sequence == probeSequence.get() && address == settings.value.torrServerUrl) result else current
        }
        return result
    }
    suspend fun diagnose(address: String): ConnectionDiagnostics {
        val target = api.at(address)
        val began = System.nanoTime()
        val version = withTimeout(12_000) { target.version() }
        val latency = (System.nanoTime() - began) / 1_000_000.0
        return try {
            val transferStart = System.nanoTime()
            val bytes = withTimeout(30_000) { target.downloadTest() }
            if (bytes != 8 * 1024 * 1024) throw ServiceFailure("TorrServer", -2)
            val seconds = (System.nanoTime() - transferStart) / 1_000_000_000.0
            ConnectionDiagnostics(version, latency, bytes, bytes * 8 / seconds / 1_000_000)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            ConnectionDiagnostics(version, latency, bandwidthFailure = (e as? ServiceFailure)?.status ?: if (e is TimeoutCancellationException) 408 else -2)
        }
    }
}
