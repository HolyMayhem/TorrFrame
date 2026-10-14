package tv.torrserve.silicon.phoneRemote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

class LocalAddressResolver(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    fun current(): String? = connectivity.allNetworks.asSequence().filter { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }.flatMap { network -> connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence() }
        .map { it.address }.filterIsInstance<Inet4Address>().firstOrNull {
            it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress
        }?.hostAddress
}
