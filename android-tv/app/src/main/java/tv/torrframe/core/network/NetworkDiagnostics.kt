package tv.torrframe.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class NetworkFailureKind { Dns, Tls, Timeout, Connection, Http, Response }
data class NetworkIssue(val host: String, val kind: NetworkFailureKind, val status: Int,
    val time: Long, val simulated: Boolean = false)

/** Bounded, process-only diagnostics. Never retain URLs, query parameters, keys or exception messages. */
class NetworkDiagnostics(private val enabled: Boolean = true, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow<List<NetworkIssue>>(emptyList())
    val issues = mutable.asStateFlow()

    @Synchronized fun failure(host: String, error: Throwable, simulated: Boolean = false) {
        if (!enabled) return
        val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
        val status = causes.filterIsInstance<ServiceFailure>().firstOrNull { it.status != 0 }?.status ?: 0
        val kind = when {
            causes.any { it is UnknownHostException } -> NetworkFailureKind.Dns
            causes.any { it is SSLException } -> NetworkFailureKind.Tls
            causes.any { it is InterruptedIOException } || status == 408 -> NetworkFailureKind.Timeout
            causes.any { it is ConnectException } -> NetworkFailureKind.Connection
            status > 0 -> NetworkFailureKind.Http
            status < 0 -> NetworkFailureKind.Response
            else -> NetworkFailureKind.Connection
        }
        val issue = NetworkIssue(host.take(96), kind, status, now(), simulated)
        mutable.value = (listOf(issue) + mutable.value.filterNot { it.host == issue.host }).take(8)
    }

    @Synchronized fun recovered(host: String) {
        if (enabled) mutable.value = mutable.value.filterNot { it.host == host }
    }

    /** Also observes Coil image downloads, which use the same OkHttp client directly. */
    val listenerFactory = EventListener.Factory { _ -> object : EventListener() {
        private var successful = false
        override fun responseHeadersEnd(call: Call, response: Response) {
            successful = response.isSuccessful
            if (!successful && response.code !in 300..399)
                failure(call.request().url.host, ServiceFailure("HTTP", response.code))
        }
        override fun callFailed(call: Call, ioe: IOException) {
            if (!call.isCanceled() || ioe is InterruptedIOException) failure(call.request().url.host, ioe)
        }
        override fun callEnd(call: Call) {
            if (successful && !call.isCanceled()) recovered(call.request().url.host)
        }
    } }
}
