package tv.torrserve.silicon.core.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.phoneRemote.discovery.LocalAddressResolver

/** The standard TorrServer web interface is independent of Phone Remote being enabled. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerWebAddressMonitor(context: Context, settings: SettingsStore, scope: CoroutineScope) {
    private val addresses = callbackFlow<String?> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val resolver = LocalAddressResolver(context)
        fun update() { trySend(runCatching { resolver.current() }.getOrNull()) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update()
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        var registered = false
        try { manager.registerNetworkCallback(request, callback); registered = true }
        catch (_: RuntimeException) { }
        update()
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
    val url = combine(addresses, settings.state) { address, state ->
        if (state.useLocalServer && state.lanAccess && address != null) "http://$address:${state.port}" else ""
    }.stateIn(scope, SharingStarted.Eagerly, "")
}
