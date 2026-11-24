package tv.torrframe.features.torrentsearch.data

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

/** Records the connection phase when the socket fails without a typed TLS exception. */
internal class SearchConnectionTrace : EventListener() {
    @Volatile var phase: SearchProbeError? = null
        private set

    override fun dnsStart(call: Call, domainName: String) { phase = SearchProbeError.Dns }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        phase = SearchProbeError.Tcp
    }
    override fun secureConnectStart(call: Call) { phase = SearchProbeError.Tls }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (handshake != null) phase = null
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        phase = null
    }
}
