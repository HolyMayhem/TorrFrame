package tv.torrserve.silicon.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.TvDimensions

@Composable fun LibraryScreen(state: LibraryUi, refresh: () -> Unit, search: () -> Unit, entryFocus: FocusRequester? = null, open: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf("Recently Added") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }; val grid = rememberLazyGridState()
    val localFallback = remember { FocusRequester() }
    val fallback = entryFocus ?: localFallback
    val items = remember(state.items, sort, filter) {
        val values = state.items.filter {
            val kind = it.metadata?.kind ?: tv.torrserve.silicon.features.metadata.MediaNameParser.parse(it.sourceTitle).kind
            filter == "All" || kind == if(filter == "Movies") MediaKind.Movie else MediaKind.Tv
        }
        when(sort) { "Title" -> values.sortedBy { it.title.lowercase() }; "Year" -> values.sortedByDescending { it.metadata?.year ?: 0 }; else -> values }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("My Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvAction("Refresh", !state.busy, modifier = Modifier.focusRequester(fallback), action = refresh); TvAction("Explore catalog", primary = true, action = search)
        }
        if(state.items.isEmpty() && !state.busy) {
            Message(state.message)
            InfoPanel("Your library is empty.", "Your added torrents and viewing progress appear here. Explore movies and series in Catalog.", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TvAction("Explore catalog", primary = true, action = search)
            }
            return@Column
        }
        ChoiceRow(listOf("Recently Added", "Title", "Year"), sort) { sort = it }
        ChoiceRow(listOf("All", "Movies", "TV Shows"), filter) { filter = it }
        Message(state.message)
        if (state.busy) Text("Loading…")
        if (items.isEmpty()) Text("No titles match this filter.")
        LazyVerticalGrid(columns = GridCells.Adaptive(TvDimensions.posterWidth), state = grid,
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(8.dp), modifier = Modifier.weight(1f)) {
            items(items, key = { it.hash }) { item -> Poster(item,
                if (item.hash == focused) Modifier.focusRequester(requester) else Modifier,
                focused = { focused = item.hash }) { open(item.hash) } }
        }
    }
    LaunchedEffect(Unit) { if(focused != null) { withFrameNanos { }; runCatching {
        if(items.any { it.hash == focused }) requester.requestFocus() else fallback.requestFocus()
    }.onFailure { fallback.requestFocus() } } }
}
