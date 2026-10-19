package tv.torrserve.silicon.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import tv.torrserve.silicon.ui.theme.SurfaceSecondary
import tv.torrserve.silicon.ui.theme.Muted
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import tv.torrserve.silicon.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.TvDimensions

@Composable fun LibraryScreen(state: LibraryUi, refresh: () -> Unit, search: () -> Unit, entryFocus: FocusRequester? = null,
    availability: LibraryAvailability = LibraryAvailability.Ready, serverSettings: () -> Unit = {}, open: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }; val grid = rememberLazyGridState()
    val localFallback = remember { FocusRequester() }
    val fallback = entryFocus ?: localFallback
    val items = remember(state.items, filter) {
        state.items.sortedByDescending { it.addedAt }.filter {
            val kind = it.metadata?.kind ?: tv.torrserve.silicon.features.metadata.MediaNameParser.parse(it.sourceTitle).kind
            filter == "All" || kind == if(filter == "Movies") MediaKind.Movie else MediaKind.Tv
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("My Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvQuietAction("Refresh", enabled = !state.busy, modifier = Modifier.focusRequester(fallback), filled = true, action = refresh); TvQuietAction("Explore catalog", filled = true, action = search)
        }
        if(availability != LibraryAvailability.Ready) {
            val title=stringResource(when(availability) {
                LibraryAvailability.Stopped -> R.string.library_server_stopped
                LibraryAvailability.Connecting -> R.string.library_server_connecting
                else -> R.string.library_server_unavailable
            })
            val explanation=stringResource(when(availability) {
                LibraryAvailability.Stopped -> R.string.library_server_stopped_hint
                LibraryAvailability.Connecting -> R.string.library_server_connecting_hint
                LibraryAvailability.AccessDenied -> R.string.server_access_denied
                LibraryAvailability.InvalidResponse -> R.string.server_invalid_response
                else -> R.string.library_server_unavailable_hint
            })
            if(state.items.isEmpty())InfoPanel(title,explanation,Modifier.fillMaxWidth().testTag("library:server-status")) {
                TvAction(stringResource(R.string.library_server_settings),modifier=Modifier.testTag("library:server-settings"),action=serverSettings)
            } else Row(Modifier.fillMaxWidth().testTag("library:server-status").background(SurfaceSecondary,RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title,style=MaterialTheme.typography.titleMedium)
                    Text(explanation,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                }
                TvAction(stringResource(R.string.library_server_settings),modifier=Modifier.testTag("library:server-settings"),action=serverSettings)
            }
            if(state.items.isEmpty())return@Column
        }
        if(state.items.isEmpty() && !state.busy) {
            Message(state.message)
            InfoPanel("Your library is empty.", "Your added torrents and viewing progress appear here. Explore movies and series in Catalog.", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TvAction("Explore catalog", primary = true, action = search)
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Movies", "TV Shows").forEach { value ->
                TvQuietAction(value, selected = value == filter, underline = true) { filter = value }
            }
        }
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
