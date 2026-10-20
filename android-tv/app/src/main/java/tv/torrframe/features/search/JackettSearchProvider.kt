package tv.torrframe.features.search

import tv.torrframe.core.storage.Settings
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

/** Jackett uses the same aggregation, filters and TV result actions as built-in providers. */
class JackettSearchProvider(private val client: JackettClient, private val settings: () -> Settings) : SearchProvider {
    override val id = ID
    override val name = "Jackett"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val config = settings()
        require(config.jackettEnabled && config.jackettConfigured) { "Configure and enable Jackett first." }
        return client.search(config, query, if (type == MediaType.Movie) "Movies" else "TV Shows").results.map { row ->
            TorrentResult(ID, name, row.title, seeds = row.seeds.toLong(), peers = row.peers.toLong(), sizeBytes = row.size,
                magnet = row.link.takeIf { TorrentIdentity.hash(it) != null },
                torrentUrl = row.link.takeIf { TorrentIdentity.web(it) != null },
                trackers = setOf(row.tracker.ifBlank { name }))
        }
    }
    companion object { const val ID = "jackett" }
}

internal fun TorrentResult.jackettResult() = SearchResult(key, title, magnet ?: requireNotNull(torrentUrl),
    infoHash.orEmpty(), sizeBytes ?: 0, (seeds ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
    (peers ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), trackers.joinToString(" · "), 0, emptySet())
