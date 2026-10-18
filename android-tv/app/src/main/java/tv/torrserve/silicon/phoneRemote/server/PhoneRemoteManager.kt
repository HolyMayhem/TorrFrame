package tv.torrserve.silicon.phoneRemote.server

import tv.torrserve.silicon.core.localization.uiString
import android.content.Intent
import android.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.R
import tv.torrserve.silicon.phoneRemote.discovery.LocalAddressResolver
import tv.torrserve.silicon.phoneRemote.security.*
import tv.torrserve.silicon.phoneRemote.synchronization.StateSyncManager

enum class PhoneRemoteStatus { Disabled, Starting, Running, Error, NoNetwork }
data class PhoneRemoteState(val status: PhoneRemoteStatus = PhoneRemoteStatus.Disabled, val address: String = "", val port: Int = 8765, val error: String = "") {
    val url get() = if (address.isBlank()) "" else "http://$address:$port"
}

class PhoneRemoteManager(private val graph: AppGraph) {
    private val prefs get() = graph.phonePreferences
    private val mutable = MutableStateFlow(PhoneRemoteState())
    val state = mutable.asStateFlow()
    val sessions = SessionManager(SessionPreferences(graph.app))
    val pairing = PairingManager()
    private val lock = Mutex()
    private val configuration = Mutex()
    private var initialized = false
    private var runtime: PhoneRemoteServer? = null
    private var generation = 0L
    suspend fun initialize() = configuration.withLock {
        if (initialized) return@withLock
        try {
            prefs.load(); sessions.load(); initialized = true
            if (prefs.state.value.enabled) startService() else sessions.clear()
        } catch (e: Exception) { if (e is CancellationException) throw e; error() }
    }
    private fun startService() {
        try { graph.app.startForegroundService(Intent(graph.app, PhoneRemoteService::class.java)) }
        catch (_: RuntimeException) { error() }
    }
    fun error() { mutable.value = mutable.value.copy(status = PhoneRemoteStatus.Error, error = graph.app.uiString(R.string.phone_start_failed)) }
    suspend fun enabled(enabled: Boolean) = configuration.withLock {
        val value = prefs.state.value
        if (enabled && value.port == graph.settings.state.value.port) {
            mutable.value = mutable.value.copy(status = PhoneRemoteStatus.Error, error = graph.app.uiString(R.string.phone_port_conflict)); return@withLock
        }
        if (enabled) { prefs.save(value.copy(enabled = true)); startService() }
        else withContext(NonCancellable) {
            var failure: Exception? = null
            try { sessions.clear() } catch (e: Exception) { failure = e }
            try { prefs.save(value.copy(enabled = false)) } catch (e: Exception) { failure = e }
            try {
                lock.withLock { ++generation; stopLocked(); mutable.value = PhoneRemoteState(port = value.port) }
            } finally { graph.app.stopService(Intent(graph.app, PhoneRemoteService::class.java)) }
            if (failure != null) {
                mutable.value = PhoneRemoteState(PhoneRemoteStatus.Error, port = value.port, error = graph.app.uiString(R.string.phone_preferences_failed))
                throw failure
            }
        }
    }
    suspend fun port(value: Int) = configuration.withLock {
        require(value in 1024..65535 && value != graph.settings.state.value.port)
        prefs.save(prefs.state.value.copy(port = value))
    }
    fun pairingLink(): PairingLink {
        check(state.value.status == PhoneRemoteStatus.Running)
        return pairing.issue(state.value.url)
    }
    private suspend fun stopLocked() { pairing.clear(); runtime?.stop(); runtime = null }
    suspend fun serve(scope: CoroutineScope) {
        val owner = lock.withLock { ++generation; stopLocked(); generation }
        val connectivity = graph.app.getSystemService(android.net.ConnectivityManager::class.java)
        val resolver = LocalAddressResolver(graph.app)
        val changes = Channel<Unit>(Channel.CONFLATED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changes.trySend(Unit) }
            override fun onLost(network: Network) { changes.trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { changes.trySend(Unit) }
        }
        var registered = false
        val settingsJob = scope.launch { combine(prefs.state, graph.settings.state.map { it.port }.distinctUntilChanged()) { _, _ -> Unit }.collect { changes.trySend(Unit) } }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            connectivity.registerNetworkCallback(request, callback); registered = true
            changes.trySend(Unit)
            for (signal in changes) {
                lock.withLock {
                    if (generation != owner) return@withLock
                    val preference = prefs.state.value
                    if (!preference.enabled) { stopLocked(); mutable.value = PhoneRemoteState(port = preference.port); return@withLock }
                    val address = resolver.current()
                    if (address == null) {
                        stopLocked(); mutable.value = PhoneRemoteState(PhoneRemoteStatus.NoNetwork, port = preference.port); return@withLock
                    }
                    if (state.value.status == PhoneRemoteStatus.Running && state.value.address == address && state.value.port == preference.port) return@withLock
                    stopLocked()
                    mutable.value = PhoneRemoteState(PhoneRemoteStatus.Starting, address, preference.port)
                    if (preference.port == graph.settings.state.value.port) { error(); return@withLock }
                    val backend = StateSyncManager(graph, scope, sessions)
                    val created = PhoneRemoteServer(RequestGuard(address, preference.port), sessions, pairing, backend, AndroidWebAssets(graph.app.assets))
                    try { created.start(); runtime = created; mutable.value = PhoneRemoteState(PhoneRemoteStatus.Running, address, preference.port) }
                    catch (e: Exception) {
                        created.stop()
                        if (e is CancellationException) throw e
                        error()
                    }
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; error() }
        finally {
            settingsJob.cancel(); changes.close()
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            withContext(NonCancellable) { lock.withLock {
                if (generation == owner) {
                    stopLocked()
                    if (prefs.state.value.enabled) error() else mutable.value = PhoneRemoteState(port = prefs.state.value.port)
                }
            } }
        }
    }
}
