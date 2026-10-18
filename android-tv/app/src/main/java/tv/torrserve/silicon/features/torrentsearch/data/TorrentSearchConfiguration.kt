package tv.torrserve.silicon.features.torrentsearch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.features.catalog.domain.CatalogRef
import tv.torrserve.silicon.features.catalog.domain.CatalogType
import tv.torrserve.torrentsearch.model.*

/** Reuse TMDB's existing language cache only when a Russian query was requested. */
class TorrentSearchConfiguration(private val graph: AppGraph) {
    suspend fun options(): TorrentSearchOptions { graph.ready.await();return graph.settings.state.value.torrentSearch }
    suspend fun prepare(target: SearchTarget, options: TorrentSearchOptions): SearchTarget {
        if(options.titleMode==SearchTitleMode.Original || target.tmdbId==null)return target
        val title=try { withTimeoutOrNull(5_000) {
            graph.catalog.details(CatalogRef(requireNotNull(target.tmdbId),if(target.type==MediaType.Movie)CatalogType.Movie else CatalogType.Tv),"ru-RU")
                .mapNotNull { it.data?.item?.title?.takeIf(String::isNotBlank) }.first()
        } }catch(e:Exception){if(e is CancellationException)throw e;null}
        return if(title!=null)target.copy(title=title,originalTitle=target.originalTitle.ifBlank { target.title }) else target
    }
}
