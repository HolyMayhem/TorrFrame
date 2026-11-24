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

enum class AdaptiveDiagnostic { Check, Retune, Compare }
enum class SearchProbeError { Timeout, Dns, Tcp, Socks, Tls, Http, Response, Network, Proxy, Local }

data class SearchProbe(
    val ok: Boolean,
    val count: Int,
    val millis: Long,
    val routes: Set<SearchRoute>,
    val strategies: Set<DpiStrategy>,
    val error: SearchProbeError?,
    val httpStatus: Int?,
    val secureDnsUsed: Boolean
)

data class SearchProbeSource(val name: String, val first: SearchProbe, val second: SearchProbe?, val secureDirect: SearchProbe?)
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
    private data class ProviderRuns(val name: String, val first: Measurement, val proxied: Measurement?, val secureDirect: Measurement?)
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
                            val compare = diagnostic == AdaptiveDiagnostic.Compare
                            val secure = compare && provider.id in secureDnsSources
                            val first = measure(provider, target, options, SearchMeasurement(
                                firstMode, saved.jacRed, preferred, diagnostic == AdaptiveDiagnostic.Retune,
                                if (compare) SearchDnsMode.System else SearchDnsMode.Fallback))
                            val directSecure = if (secure) measure(provider, target, options,
                                SearchMeasurement(AdaptiveMode.Off, saved.jacRed, preferred, false, SearchDnsMode.Secure)) else null
                            val proxied = if (compare) measure(provider, target, options,
                                SearchMeasurement(AdaptiveMode.Forced, saved.jacRed, preferred, false,
                                    if (secure) SearchDnsMode.Secure else SearchDnsMode.System)) else null
                            ProviderRuns(provider.name, first, proxied, directSecure)
                        }
                    } }.awaitAll()
                }
                val direct = results.flatMapTo(hashSetOf()) { it.first.keys }
                val compare = diagnostic == AdaptiveDiagnostic.Compare
                val proxied = if (compare) results.flatMapTo(hashSetOf()) { it.proxied?.keys.orEmpty() } else emptySet()
                SearchComparison(target.title, firstMode, results.map { (name, first, proxy, directSecure) ->
                    SearchProbeSource(name, first.probe, proxy?.probe, directSecure?.probe)
                },
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
            val serviceFailure = failure as? ServiceFailure
            error = serviceFailure?.searchFailure(context.failurePhase) ?: SearchProbeError.Response
            if (error == SearchProbeError.Http) status = serviceFailure?.status
        }
        Measurement(SearchProbe(error == null, keys.size, SystemClock.elapsedRealtime() - start, context.routes.toSet(),
            context.strategies.toSet(), error, status, context.secureDnsUsed), keys)
    }
}
