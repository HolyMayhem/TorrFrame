package tv.torrserve.torrentsearch.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

/** A single consumer owns aggregation, so parallel sources never mutate shared result state. */
class TorrentSearchEngine(val providers: List<SearchProvider>, private val sourceTimeout: Long = 18_000,
    private val concurrency: Int = 4, private val maxResults: Int = 1500,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private data class Event(val source: SourceState, val batch: List<TorrentResult> = emptyList())
    fun search(target: SearchTarget, options: TorrentSearchOptions? = null): Flow<SearchSnapshot> = flow {
        val config=options?.normalized() ?: TorrentSearchOptions(sourceTimeoutMs=sourceTimeout)
        val active = providers.filter { target.type in it.types && (config.enabledProviders==null || it.id in config.enabledProviders) }
        val sources = active.associate { it.id to SourceState(it.id, it.name) }.toMutableMap()
        val results = linkedMapOf<String, TorrentResult>()
        emit(SearchSnapshot(sources = sources.values.toList()))
        val slots = Semaphore(concurrency)
        channelFlow {
            active.forEach { provider -> launch {
                slots.withPermit {
                    var count = 0
                    send(Event(SourceState(provider.id, provider.name, SourcePhase.Searching)))
                    try {
                        withTimeout(config.sourceTimeoutMs) {
                            target.queries(config.titleMode).forEach { query ->
                                val batch = provider.search(query, target.type).filter { it.title.isNotBlank() && (it.magnet?.let(TorrentIdentity::hash) != null || TorrentIdentity.web(it.detailsUrl) != null || TorrentIdentity.web(it.torrentUrl) != null) }
                                count += batch.size
                                send(Event(SourceState(provider.id, provider.name, SourcePhase.Searching, count), batch))
                            }
                        }
                        send(Event(SourceState(provider.id, provider.name, SourcePhase.Complete, count)))
                    } catch (_: TimeoutCancellationException) { send(Event(SourceState(provider.id, provider.name, SourcePhase.TimedOut, count))) }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        send(Event(SourceState(provider.id, provider.name, SourcePhase.Failed, count)))
                    }
                }
            } }
        }.collect { event ->
            sources[event.source.id] = event.source
            event.batch.forEach { row -> results[row.key] = results[row.key]?.let { merge(it, row) } ?: row }
            val sorted = results.values.filter(config::accepts).sortedWith(order).take(maxResults)
            if (results.size > maxResults) { results.clear(); sorted.forEach { results[it.key] = it } }
            emit(SearchSnapshot(sorted, sources.values.toList()))
        }
    }.flowOn(dispatcher)
    suspend fun resolve(row: TorrentResult): TorrentLink = withContext(dispatcher) {
        withTimeout(18_000) {
            requireNotNull(providers.firstOrNull { it.id == row.providerId }).resolve(row)
        }
    }
    private fun merge(a: TorrentResult, b: TorrentResult): TorrentResult {
        val preferred = when {
            a.magnet==null && b.magnet!=null -> b
            a.magnet!=null && b.magnet==null -> a
            a.quality.isBlank() && b.quality.isNotBlank() -> b
            else -> a
        }
        fun best(x: Long?, y: Long?) = listOfNotNull(x, y).maxOrNull()
        return preferred.copy(seeds = best(a.seeds, b.seeds), peers = best(a.peers, b.peers),
            sizeBytes = preferred.sizeBytes ?: a.sizeBytes ?: b.sizeBytes ?: TorrentSize.bytes(a.sizeText) ?: TorrentSize.bytes(b.sizeText),
            sizeText = preferred.sizeText ?: a.sizeText ?: b.sizeText, trackers = a.trackers + b.trackers)
    }
    companion object {
        val order = compareByDescending<TorrentResult> { it.seeds?.takeIf { count -> count >= 0L } ?: -1L }
            .thenBy { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.key }
    }
}
