package tv.torrserve.silicon.features.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.torrserve.silicon.core.database.MetadataCache
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.core.storage.MetadataPriority

class MetadataResolver(private val providers: MetadataSource, private val store: MetadataCache,
    private val bypassCache:(String)->Boolean = { false }) {
    private val permits = Semaphore(2)
    private class WorkLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val locks = mutableMapOf<String, WorkLock>()
    private suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(key) { WorkLock() }.also { it.users++ } }
        try { return lock.mutex.withLock { block() } }
        finally { synchronized(locks) { if (--lock.users == 0) locks.remove(key) } }
    }
    suspend fun resolve(sources: List<String>, s: Settings, forceRefresh: Boolean = false): Metadata? = withContext(Dispatchers.IO) {
        val order = (if (s.aniListEnabled) listOf("AniList") else emptyList()) + MetadataPriority.normalized(s.metadataOrder)
        // Check higher-priority providers even when a lower-priority result is cached.
        for (provider in order) {
            val result = resolveProvider(sources, s, provider, forceRefresh)
            if (result != null) return@withContext result
        }
        null
    }
    private suspend fun resolveProvider(sources: List<String>, s: Settings, provider: String, forceRefresh: Boolean): Metadata? {
        val candidates = MediaNameParser.candidates(sources, provider, s.language)
        val key = "v4|$provider|${s.language}|" + candidates.joinToString("|") { "${MediaNameParser.normalized(it.parsed.title)}:${it.parsed.year}:${it.parsed.kind}" }
        // Identical lookups share their cache work; unrelated titles do not wait on their network calls.
        return withKeyLock(key) {
            val bypass = bypassCache(provider)
            if(!bypass) {
                if(forceRefresh) store.removeCache(key)
                val cached=store.cached(key); if(cached.present)return@withKeyLock cached.value
            }
            // Waiters for the same key do not consume another network slot.
            permits.withPermit { try {
                data class Ranked(val match: MetadataMatch, val title: Double, val year: Int, val confidence: Int)
                var best: Ranked? = null
                val queries = if(provider == "AniList") candidates.take(2) else candidates.take(4) + candidates.take(2).map {
                    it.copy(parsed = it.parsed.copy(kind = if(it.parsed.kind == MediaKind.Movie) MediaKind.Tv else MediaKind.Movie), confidence = it.confidence - 20)
                }
                for (candidate in queries) {
                    val p = candidate.parsed
                    val options = providers.search(p, s, provider)
                    val ranks = options.mapNotNull { match ->
                        val m = match.value
                        val score = (listOf(m.title, m.originalTitle) + match.aliases).maxOf { MediaNameParser.similarity(it, p.title) }
                        if (score < 0.65 || (p.year != null && m.year != null && kotlin.math.abs(p.year - m.year) > 1) || m.kind != p.kind ||
                            (provider == "AniList" && (score != 3.0 || (p.year != null && p.year != m.year)))) null
                        else Ranked(match, score, if (p.year != null && p.year == m.year) 1 else 0, candidate.confidence)
                    }
                    val rank = ranks.maxWithOrNull(compareBy<Ranked> { it.title }.thenBy { it.year }.thenBy { it.confidence }.thenBy { it.match.popularity })
                    if (rank != null && (best == null || compareValuesBy(rank, best, { it.title }, { it.year }, { it.confidence }, { it.match.popularity }) > 0)) best = rank
                    if (rank?.title == 3.0 && (p.year == null || rank.year == 1)) break
                }
                val metadata = best?.let { providers.details(it.match, s) }
                if(!bypass && !bypassCache(provider))store.cache(key, metadata)
                metadata
            } catch (e: Exception) { if (e is CancellationException) throw e; null } }
        }
    }
}
