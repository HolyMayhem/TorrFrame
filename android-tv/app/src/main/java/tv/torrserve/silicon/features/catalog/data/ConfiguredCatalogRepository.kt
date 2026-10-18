package tv.torrserve.silicon.features.catalog.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.features.catalog.domain.*
import java.security.MessageDigest

/** Separate provider caches; automatic feeds retain their provider across pagination. */
class ConfiguredCatalogRepository(
    private val settings: () -> CatalogNetworkSettings,
    private val http: Http,
    private val tmdb: TmdbClient,
    private val cache: CatalogCache,
    private val scope: CoroutineScope,
) : CatalogRepository {
    private class Sources(val config: CatalogNetworkSettings, val primary: CatalogRepository, val backup: CatalogRepository) {
        private val feeds = linkedMapOf<String, CatalogSource>()
        @Synchronized fun source(key: String) = feeds[key]
        @Synchronized fun remember(key: String, source: CatalogSource) {
            feeds.remove(key); feeds[key] = source
            while (feeds.size > 64) feeds.remove(feeds.keys.first())
        }
    }
    private var active: Sources? = null
    @Synchronized private fun sources(): Sources {
        val config = settings().connection()
        active?.takeIf { it.config == config }?.let { return it }
        val primary = CatalogRepositoryImpl(TmdbCatalogApi(tmdb), cache, scope, namespace = "tmdb:")
        val digest = MessageDigest.getInstance("SHA-256").digest((config.effectiveCubUrl + "\n" + config.cubToken).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val backup = CatalogRepositoryImpl(CubCatalogApi(http, config), cache, scope, namespace = "cub:v2:$digest:")
        return Sources(config, primary, backup).also { active = it }
    }
    private fun <T> browse(key: String, page: Int, operation: (CatalogRepository) -> Flow<CatalogLoad<T>>) = flow {
        val sources = sources()
        when (sources.config.source) {
            CatalogSource.Tmdb -> emitAll(operation(sources.primary))
            CatalogSource.Cub -> emitAll(operation(sources.backup))
            CatalogSource.Auto -> {
                // Never append page 2 from another provider with different ordering/contents.
                val pinned = if (page > 1) sources.source(key) else null
                if (pinned != null) {
                    emitAll(operation(if (pinned == CatalogSource.Cub) sources.backup else sources.primary))
                    return@flow
                }
                var failure: CatalogLoad<T>? = null
                operation(sources.primary).collect { load ->
                    if (load.error) failure = load else {
                        if (!load.loading) sources.remember(key, CatalogSource.Tmdb)
                        emit(load)
                    }
                }
                failure?.let { original ->
                    operation(sources.backup).collect { load ->
                        if (!load.loading && !load.error) sources.remember(key, CatalogSource.Cub)
                        emit(if (load.data == null && original.data != null) load.copy(data = original.data, stale = true) else load)
                    }
                }
            }
        }
    }
    override fun section(section: CatalogSection, language: String, page: Int, force: Boolean) =
        browse("section:${section.name}:$language", page) { it.section(section, language, page, force) }
    override fun search(query: String, language: String, page: Int, force: Boolean) =
        browse("search:${query.trim()}:$language", page) { it.search(query, language, page, force) }
    override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean) =
        browse("genre:${filter.name}:${type.path}:$language", page) { it.discover(filter, type, language, page, force) }
    // CUB's /3/ endpoints redirect to TMDB; keep its auth, localization, rate budget and cache here.
    override fun details(ref: CatalogRef, language: String, force: Boolean) = flow {
        emitAll(sources().primary.details(ref, language, force))
    }
    override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) = flow {
        emitAll(sources().primary.related(ref, source, language, page, force))
    }
}
