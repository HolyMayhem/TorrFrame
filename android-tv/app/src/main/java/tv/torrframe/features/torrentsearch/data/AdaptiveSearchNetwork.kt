package tv.torrframe.features.torrentsearch.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.torrframe.core.network.Http
import tv.torrframe.core.network.HttpReply
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.network.TmdbDns
import tv.torrframe.core.storage.JacRedMode
import tv.torrframe.core.storage.JacRedSettings
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

enum class AdaptiveMode { Off, Auto, Forced }
enum class SearchRoute { Direct, Local, ByeDpi }
internal enum class SearchDnsMode { Fallback, System, Secure }

// Start with the sources where the system resolver returned a block-page IP.
internal val secureDnsSources = mapOf("rutorinfo" to "rutor.info", "nonameclub" to "nnmclub.to")
private val secureDnsHosts = secureDnsSources.values.toSet()


internal class SearchMeasurement(
    val mode: AdaptiveMode,
    val jacRed: JacRedSettings,
    val jacRedPreferred: String?,
    val retune: Boolean,
    val dnsMode: SearchDnsMode = SearchDnsMode.Fallback,
    val routes: MutableSet<SearchRoute> = linkedSetOf(),
    val strategies: MutableSet<DpiStrategy> = linkedSetOf()
) : AbstractCoroutineContextElement(Key) {
    var secureDnsUsed = false
    var failurePhase: SearchProbeError? = null
    companion object Key : CoroutineContext.Key<SearchMeasurement>
}

class AdaptiveSearchNetwork(
    context: Context,
    private val http: Http,
    val process: ByeDpiProcess,
    private val secureLookup: Dns = Dns(TmdbDns(hosts = secureDnsHosts)::lookupSecure),
    private val mode: () -> AdaptiveMode
) {
    private val memory = context.getSharedPreferences("torrentsearch-dpi-strategies", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val strategyLock = Any()
    @Volatile var generation: Long = 0
        private set
    private val limits = Mutex()
    private data class Limit(val next: Long, val status: Int? = null)
    private val requests = LinkedHashMap<String, Limit>()
    private val directClient = http.client.newBuilder().proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false).cache(null).build()
    private val quickClient = directClient.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).build()
    private val testClient = directClient.newBuilder()
        .connectTimeout(2_800, TimeUnit.MILLISECONDS).readTimeout(3_800, TimeUnit.MILLISECONDS)
        .callTimeout(4_500, TimeUnit.MILLISECONDS).followSslRedirects(false)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)).build()

    fun resetStrategies() = synchronized(strategyLock) {
        memory.edit { clear() }
        generation++
    }

    fun cacheIdentity(): String = "${mode().name}:$generation:${connectivity.activeNetwork?.networkHandle}"

    suspend fun exchange(
        request: Request, service: String, limit: Int, manualRedirects: Boolean = false, allowLocal: Boolean = false
    ): HttpReply {
        val measurement = coroutineContext[SearchMeasurement]
        val selected = measurement?.mode ?: mode()
        val activeGeneration = generation
        val host = request.url.host
        val bucket = if (JacRedMode.publicUrls.any { it.removePrefix("https://").trimEnd('/') == host }) "jacred-public" else host
        val dnsMode = measurement?.dnsMode ?: SearchDnsMode.Fallback
        val eligibleForSecureDns = !allowLocal && request.url.isHttps && request.url.port == 443 && host in secureDnsHosts
        suspend fun lookupSecure(): List<InetAddress> {
            measurement?.secureDnsUsed = true
            return try {
                runInterruptible(Dispatchers.IO) {
                    secureLookup.lookup(host).filter(InetAddress::isPublicSearchAddress)
                        .ifEmpty { throw UnknownHostException(host) }
                }
            } catch (error: UnknownHostException) {
                throw ServiceFailure(service, cause = error)
            }
        }
        suspend fun recoverDns(error: ServiceFailure): List<InetAddress>? {
            if (!eligibleForSecureDns || dnsMode != SearchDnsMode.Fallback || !error.canTrySecureDns()) return null
            return try { lookupSecure() } catch (_: ServiceFailure) { null }
        }
        suspend fun fetch(
            client: OkHttpClient, route: SearchRoute, strategy: DpiStrategy? = null, addresses: List<InetAddress>? = null
        ): HttpReply {
            measurement?.failurePhase = null
            throttle(bucket, service)
            measurement?.routes?.add(route)
            strategy?.let { measurement?.strategies?.add(it) }
            val trace = measurement?.let { SearchConnectionTrace() }
            val configured = if (addresses == null && trace == null) client else client.newBuilder().apply {
                if (addresses != null) dns { name -> if (name == host) addresses else client.dns.lookup(name) }
                if (trace != null) {
                    eventListenerFactory { call -> client.eventListenerFactory.create(call) + trace }
                    fastFallback(false)
                }
            }.build()
            try {
                return http.exchange(request, service, limit, manualRedirects, configured)
            } catch (error: ServiceFailure) {
                measurement?.failurePhase = trace?.phase
                if (error.status == 429 || error.status in 500..599 && error.retryAfterMillis != null) {
                    backoff(bucket, error)
                }
                throw error
            }
        }
        // Local custom JacRed/selected torrent URLs keep the private addresses from this lookup.
        // Binding the lookup to that result prevents a DNS change from escaping onto another route.
        if (allowLocal && (selected != AdaptiveMode.Off || measurement != null)) {
            val addresses = withContext(Dispatchers.IO) {
                try { Dns.SYSTEM.lookup(host) } catch (_: UnknownHostException) { emptyList() }
            }
            if (addresses.isNotEmpty() && addresses.none(InetAddress::isPublicSearchAddress)) {
                val local = (if (measurement == null) directClient else testClient).newBuilder()
                    .dns { name -> if (name == host) addresses else throw UnknownHostException() }
                    .followRedirects(false).build()
                return fetch(local, SearchRoute.Local)
            }
        }
        var secureAddresses = if (eligibleForSecureDns && dnsMode == SearchDnsMode.Secure) lookupSecure() else null
        if (selected == AdaptiveMode.Off) {
            return fetch(if (measurement == null) http.client else testClient, SearchRoute.Direct, addresses = secureAddresses)
        }

        val key = strategyKey("$host:${request.url.port}")
        val previous = if (measurement?.retune == true) null else key?.let(::remembered)
        val strategies = (listOfNotNull(previous) + DpiStrategy.entries).distinct()
        val tryDirectFirst = selected == AdaptiveMode.Auto && (previous == null || eligibleForSecureDns)
        val direct = if (measurement == null) quickClient else testClient
        if (tryDirectFirst) {
            try {
                return fetch(direct, SearchRoute.Direct)
            } catch (error: ServiceFailure) {
                secureAddresses = recoverDns(error)
                if (secureAddresses == null && !error.canTryAnotherRoute()) throw error
            }
            if (secureAddresses != null) {
                try {
                    return fetch(direct, SearchRoute.Direct, addresses = secureAddresses)
                } catch (error: ServiceFailure) {
                    if (!error.canTryAnotherRoute()) throw error
                }
            }
        }

        suspend fun viaProxy(strategy: DpiStrategy, addresses: List<InetAddress>?): HttpReply {
            measurement?.failurePhase = null
            val result = try {
                process.use(strategy, directClient) { fetch(it, SearchRoute.ByeDpi, strategy, addresses) }
            } catch (error: DpiProcessFailure) {
                throw ServiceFailure(service, cause = error)
            }
            if (result.status in 200..299 && key != null && (measurement == null || measurement.retune)) {
                remember(key, strategy, activeGeneration)
            }
            return result
        }

        var remaining = strategies
        var failure: ServiceFailure? = null
        if (selected == AdaptiveMode.Forced && eligibleForSecureDns && dnsMode == SearchDnsMode.Fallback) {
            try {
                return viaProxy(strategies.first(), null)
            } catch (error: ServiceFailure) {
                failure = error
                secureAddresses = recoverDns(error)
                if (secureAddresses == null) {
                    if (!error.canTryAnotherRoute()) throw error
                    remaining = strategies.drop(1)
                }
            }
        }

        for (strategy in remaining) {
            try {
                return viaProxy(strategy, secureAddresses)
            } catch (error: ServiceFailure) {
                val proxyFailed = error.failureChain().any { it is DpiProcessFailure }
                if (!proxyFailed && !error.canTryAnotherRoute()) throw error
                failure = error
                if (proxyFailed) break
            }
        }
        if (selected == AdaptiveMode.Auto && previous != null && !tryDirectFirst) {
            val result = fetch(direct, SearchRoute.Direct)
            if (key != null && measurement == null) synchronized(strategyLock) {
                if (generation == activeGeneration) memory.edit { remove(key) }
            }
            return result
        }
        throw checkNotNull(failure)
    }

    private suspend fun throttle(bucket: String, service: String) {
        while (true) {
            val wait = limits.withLock {
                val now = SystemClock.elapsedRealtime()
                val remaining = (requests[bucket]?.next ?: 0) - now
                val status = requests[bucket]?.status
                if (remaining > 0 && status != null) throw ServiceFailure(service, status, remaining)
                if (remaining <= 0) {
                    if (requests.size >= 128) requests.entries.removeAll { it.value.next <= now }
                    requests[bucket] = Limit(now + 750)
                }
                remaining
            }
            if (wait <= 0) return
            delay(wait)
        }
    }

    private suspend fun backoff(bucket: String, error: ServiceFailure) = limits.withLock {
        val now = SystemClock.elapsedRealtime()
        val wait = (error.retryAfterMillis ?: 60_000).coerceIn(1_000, Long.MAX_VALUE - now)
        val until = now + wait
        requests[bucket] = Limit(maxOf(until, requests[bucket]?.next ?: 0), error.status)
    }

    // No SSID/location permission. This fingerprint is local and best-effort across reconnects.
    private fun strategyKey(host: String): String? {
        val network = connectivity.activeNetwork ?: return null
        val links = connectivity.getLinkProperties(network) ?: return null
        val identity = listOf(links.interfaceName.orEmpty(),
            links.linkAddresses.map { it.toString() }.sorted().joinToString(),
            links.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }.sorted().joinToString(),
            links.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(), host).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun remembered(key: String): DpiStrategy? = synchronized(strategyLock) {
        val value = memory.getString(key, null) ?: return@synchronized null
        val expiry = value.substringAfter(':').toLongOrNull() ?: return@synchronized null
        if (System.currentTimeMillis() >= expiry) return@synchronized null
        DpiStrategy.entries.firstOrNull { it.name == value.substringBefore(':') }
    }

    private fun remember(key: String, strategy: DpiStrategy, activeGeneration: Long) = synchronized(strategyLock) {
        if (generation != activeGeneration) return@synchronized
        val expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
        val entries = memory.all
        memory.edit {
            if (key !in entries && entries.size >= 96) {
                entries.minByOrNull { it.value.toString().substringAfter(':').toLongOrNull() ?: 0 }?.let { remove(it.key) }
            }
            putString(key, "${strategy.name}:$expiry")
        }
    }
}

internal fun Throwable.failureChain() = generateSequence(this) { it.cause }.take(8).toList()

internal fun ServiceFailure.canTryAnotherRoute(): Boolean {
    if (retryAfterMillis != null || status != 0 && status != 408) return false
    val causes = failureChain()
    if (causes.any { it is SSLPeerUnverifiedException || it is CertificateException ||
            it is UnknownHostException || it is PrivateSearchAddress || it is DpiProcessFailure }) return false
    val socks = causes.filterIsInstance<SocksConnectFailure>().firstOrNull()
    if (socks != null) return socks.reply in setOf(null, 1, 4, 5)
    return causes.any { it is SocketException || it is InterruptedIOException || it is EOFException || it is SSLException }
}

internal fun ServiceFailure.canTrySecureDns(): Boolean {
    if (canTryAnotherRoute()) return true
    if (retryAfterMillis != null || status != 0) return false
    return failureChain().any { it is UnknownHostException || it is SSLPeerUnverifiedException || it is CertificateException }
}

internal fun ServiceFailure.searchFailure(phase: SearchProbeError?): SearchProbeError {
    val causes = failureChain()
    return when {
        causes.any { it is DpiProcessFailure } -> SearchProbeError.Proxy
        causes.any { it is PrivateSearchAddress } -> SearchProbeError.Local
        causes.any { it is SocksConnectFailure } -> SearchProbeError.Socks
        causes.any { it is UnknownHostException } -> SearchProbeError.Dns
        causes.any { it is SSLException } -> SearchProbeError.Tls
        causes.any { it is ConnectException || it is NoRouteToHostException } -> SearchProbeError.Tcp
        status == 408 -> phase ?: SearchProbeError.Timeout
        status > 0 -> SearchProbeError.Http
        status < 0 -> SearchProbeError.Response
        else -> phase ?: SearchProbeError.Network
    }
}
