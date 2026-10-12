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

class MetadataResolver(private val providers: MetadataSource, private val store: MetadataCache) {
    private val permits = Semaphore(2)
    private val lock = Mutex()
    suspend fun resolve(sources: List<String>, s: Settings, forceRefresh: Boolean = false): Metadata? = withContext(Dispatchers.IO) { permits.withPermit {
        val order = (if (s.aniListEnabled) listOf("AniList") else emptyList()) + MetadataPriority.normalized(s.metadataOrder)
        // Check higher-priority providers even when a lower-priority result is cached.
        for (provider in order) {
            val result = resolveProvider(sources, s, provider, forceRefresh)
            if (result != null) return@withPermit result
        }
        null
    } }
    private suspend fun resolveProvider(sources: List<String>, s: Settings, provider: String, forceRefresh: Boolean): Metadata? {
        val candidates = MediaNameParser.candidates(sources, provider, s.language)
        val key = "v4|$provider|${s.language}|" + candidates.joinToString("|") { "${MediaNameParser.normalized(it.parsed.title)}:${it.parsed.year}:${it.parsed.kind}" }
        // Serialize resolver work to deduplicate simultaneous additions and cache writes.
        return lock.withLock {
            if(forceRefresh) store.removeCache(key)
            val cached = store.cached(key); if (cached.present) return@withLock cached.value
            try {
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
                store.cache(key, metadata); metadata
            } catch (e: Exception) { if (e is CancellationException) throw e; null }
        }
    }
}
