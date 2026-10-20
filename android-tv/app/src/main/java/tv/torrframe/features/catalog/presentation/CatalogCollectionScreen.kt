package tv.torrframe.features.catalog.presentation

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.torrframe.features.catalog.domain.*
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.Muted

/** The full collection reuses the already loaded feed and its pagination/cache. */
@Composable internal fun CatalogCollectionScreen(title: String, feed: CatalogFeed, grid: LazyGridState,
    width: Dp, backFocus: FocusRequester, restoreKey: String?, restore: FocusRequester, requestInitialFocus: Boolean,
    back: () -> Unit, retry: () -> Unit, more: () -> Unit, open: (CatalogRef) -> Unit,
    focused: (CatalogItem) -> Unit, held: (CatalogItem, FocusRequester) -> Unit,
    visible: (CatalogItem) -> Unit = {}, locale: String = "") {
    val gridFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().testTag("catalog:collection"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvBackAction(modifier = Modifier.focusRequester(backFocus)
                .focusProperties { if (feed.items.isNotEmpty()) down = gridFocus }
                .testTag("catalog:collection:back"), action = back)
            TvText(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        CatalogFeedError(feed, retry, gridFocus, backFocus, "catalog:retry:collection")
        if (feed.loading && feed.items.isEmpty()) TvText(catalogText("Loading…", "Загрузка…"), color = Muted)
        if (!feed.loading && !feed.error && feed.items.isEmpty())
            TvText(catalogText("No titles in this collection.", "В подборке пока нет фильмов."), color = Muted)
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewportWidth = with(LocalDensity.current) { constraints.maxWidth.toDp() }
            val columns = (viewportWidth / (width + 12.dp)).toInt().coerceAtLeast(1)
            LazyVerticalGrid(columns = GridCells.Adaptive(width), state = grid,
                modifier = Modifier.fillMaxSize().scrollEdgeFade(grid, vertical = true).focusRequester(gridFocus).focusGroup().testTag("catalog:collection:grid"),
                contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(feed.items, key = { _, item -> item.ref.key }) { index, item ->
                    LaunchedEffect(item.ref, locale) { visible(item) }
                    CatalogPosterCard(item, Modifier
                        .then(if (item.ref.key == restoreKey) Modifier.focusRequester(restore) else Modifier)
                        .focusProperties { if (index < columns) up = backFocus }
                        .onFocusChanged { if (it.isFocused) focused(item) }, held) { open(item.ref) }
                }
                if (feed.loadingMore) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                    TvText(catalogText("Loading…", "Загрузка…"), color = Muted)
                }
                if (!feed.loading && !feed.error && feed.items.isEmpty() && feed.page < feed.totalPages)
                    item("more", span = { GridItemSpan(maxLineSpan) }) {
                        TvAction(catalogText("More", "Больше"), action = more)
                    }
            }
        }
    }
    LaunchedEffect(Unit) {
        // Detail return restores the saved card in the parent; do not overwrite it.
        if (requestInitialFocus) {
            withFrameNanos { }
            if (feed.items.isNotEmpty()) gridFocus.requestFocus() else backFocus.requestFocus()
        }
    }
    LaunchedEffect(grid, feed.page, feed.items.size, feed.error, feed.loading) {
        if (!feed.loading && !feed.error && feed.items.isNotEmpty()) {
            snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
                if (last >= feed.items.size - 5) more()
            }
        }
    }
}
