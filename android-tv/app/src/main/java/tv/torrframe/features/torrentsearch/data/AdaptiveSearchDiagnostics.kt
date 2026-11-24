package tv.torrframe.features.torrentsearch.data

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.torrframe.R
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.server.ApplicationActionFailure
import tv.torrframe.core.storage.Settings
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.SearchTarget
import tv.torrserve.torrentsearch.model.TorrentIdentity
import tv.torrserve.torrentsearch.model.TorrentSearchOptions
import tv.torrserve.torrentsearch.provider.SearchProvider
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class AdaptiveDiagnostic { Check, Retune, Compare }
enum class SearchProbeError { Timeout, Dns, Tls, Http, Response, Network, Proxy, Local }

data class SearchProbe(
    val ok: Boolean,
    val count: Int,
    val millis: Long,
    val routes: Set<SearchRoute>,
    val strategies: Set<DpiStrategy>,
    val error: SearchProbeError?,
    val httpStatus: Int?
)

data class SearchProbeSource(val name: String, val first: SearchProbe, val second: SearchProbe?)
data class SearchComparison(
    val query: String,
    val firstMode: AdaptiveMode,
    val sources: List<SearchProbeSource>,
    val unique: Int,
    val proxiedUnique: Int?,
    val directOnly: Int,
    val proxyOnly: Int
)

class AdaptiveSearchDiagnostics(
    private val engine: TorrentSearchEngine,
    private val settings: () -> Settings,
    private val preferredJacRed: () -> String?
) {
    private data class Measurement(val probe: SearchProbe, val keys: Set<String>)
    private val state = MutableStateFlow(false)
    val busy = state.asStateFlow()

    suspend fun run(target: SearchTarget, diagnostic: AdaptiveDiagnostic): SearchComparison {
        if (!state.compareAndSet(expect = false, update = true)) throw ApplicationActionFailure(R.string.dpi_busy)
        try {
            return withContext(Dispatchers.Default) {
                val saved = settings()
                val options = saved.torrentSearch.normalized()
                val preferred = preferredJacRed()
                val firstMode = when (diagnostic) {
                    AdaptiveDiagnostic.Check -> saved.adaptiveMode
                    AdaptiveDiagnostic.Retune -> AdaptiveMode.Forced
                    AdaptiveDiagnostic.Compare -> AdaptiveMode.Off
                }
                val providers = engine.activeProviders(target, options)
                if (providers.isEmpty()) throw ApplicationActionFailure(R.string.dpi_no_sources)
                val slots = Semaphore(2)
                val results = coroutineScope {
                    providers.map { provider -> async {
                        slots.withPermit {
                            val first = measure(provider, target, options, SearchMeasurement(
                                firstMode, saved.jacRed, preferred, diagnostic == AdaptiveDiagnostic.Retune))
                            val second = if (diagnostic == AdaptiveDiagnostic.Compare) measure(provider, target, options,
                                SearchMeasurement(AdaptiveMode.Forced, saved.jacRed, preferred, false)) else null
                            Triple(provider.name, first, second)
                        }
                    } }.awaitAll()
                }
                val direct = results.flatMapTo(hashSetOf()) { it.second.keys }
                val compare = diagnostic == AdaptiveDiagnostic.Compare
                val proxied = if (compare) results.flatMapTo(hashSetOf()) { it.third?.keys.orEmpty() } else emptySet()
                SearchComparison(target.title, firstMode, results.map { (name, a, b) -> SearchProbeSource(name, a.probe, b?.probe) },
                    direct.size, proxied.size.takeIf { compare },
                    if (compare) direct.count { it !in proxied } else 0, proxied.count { it !in direct })
            }
        } finally {
            state.value = false
        }
    }

    private suspend fun measure(
        provider: SearchProvider, target: SearchTarget, options: TorrentSearchOptions, context: SearchMeasurement
    ): Measurement = withContext(context) {
        val start = SystemClock.elapsedRealtime()
        val keys = linkedSetOf<String>()
        var error: SearchProbeError? = null
        var status: Int? = null
        val timeout = options.sourceTimeoutMs.coerceAtMost(10_000)
        try {
            val completed = withTimeoutOrNull(timeout) {
                provider.searchBatches(target, options.titleMode, timeout).collect { rows ->
                    rows.asSequence().filter { row ->
                        row.title.isNotBlank() && (row.infoHash != null || TorrentIdentity.web(row.detailsUrl) != null ||
                            TorrentIdentity.web(row.torrentUrl) != null) && options.accepts(row)
                    }.forEach { keys += it.key }
                }
                true
            }
            if (completed == null) error = SearchProbeError.Timeout
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val causes = failure.failureChain()
            val code = (failure as? ServiceFailure)?.status
            error = when {
                causes.any { it is DpiProcessFailure } -> SearchProbeError.Proxy
                causes.any { it is PrivateSearchAddress } -> SearchProbeError.Local
                code == 408 -> SearchProbeError.Timeout
                code != null && code > 0 -> SearchProbeError.Http.also { status = code }
                causes.any { it is UnknownHostException } -> SearchProbeError.Dns
                causes.any { it is SSLException } -> SearchProbeError.Tls
                failure !is ServiceFailure || code != null && code < 0 -> SearchProbeError.Response
                else -> SearchProbeError.Network
            }
        }
        Measurement(SearchProbe(error == null, keys.size, SystemClock.elapsedRealtime() - start, context.routes.toSet(),
            context.strategies.toSet(), error, status), keys)
    }
}
