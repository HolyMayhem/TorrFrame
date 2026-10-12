package tv.torrserve.silicon.features.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.Muted
import tv.torrserve.silicon.ui.theme.Surface
import tv.torrserve.silicon.ui.theme.Outline

@Composable fun SearchScreen(state: SearchUi, configured: Boolean, vm: SearchViewModel,
    settings: () -> Unit, entryFocus: FocusRequester? = null, watch: (String) -> Unit) {
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if(lastFocus != null) { withFrameNanos { }; runCatching { focus.requestFocus() } } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading("Search", "Find your next film or series.")
        if (!configured) {
            InfoPanel("Connect Jackett", "Connect Jackett to search movies, series and torrents.", Modifier.fillMaxWidth().padding(top = 12.dp)) {
                TvAction("Open Settings", modifier = if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier, primary = true, action = settings)
            }; return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EditField("Search movies, series or torrents", state.query, modifier = Modifier.weight(1f).then(if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier), onValue = vm::query)
            TvAction(if (state.searching) "Cancel" else "Search", modifier = Modifier.padding(top = 24.dp)) {
                if (state.searching) vm.cancel() else vm.search()
            }
        }
        ChoiceRow(listOf("All", "Movies", "TV Shows"), state.filter, vm::filter)
        ChoiceRow(listOf("Seeds", "Size", "Date"), state.sort, vm::sort)
        Message(state.message)
        if (state.searching) Text("Searching…")
        else if (state.searched && state.results.isEmpty() && state.message.isBlank()) Text("No torrents found. Try another title.")
        val results = remember(state.results, state.sort, state.filter) {
            val values = state.results.filter { state.filter == "All" || it.kind == state.filter }
            when(state.sort) { "Size" -> values.sortedByDescending { it.size }; "Date" -> values.sortedByDescending { it.date }; else -> values.sortedByDescending { it.seeds } }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            items(results, key = { it.id }) { result ->
                Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleMedium, translate = false)
                    val date = if(result.date > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(result.date)) else ""
                    Text(listOf("%.1f GB".format(result.size / 1e9), "Seeds: ${result.seeds}", "Peers: ${result.peers}", result.tracker, date,
                        if(result.link.startsWith("magnet:")) "Magnet" else "Torrent").filter { it.isNotBlank() }.joinToString("  ·  "), color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvAction(if(result.id in state.added) "Added" else "Add to Library", state.adding == null,
                            Modifier.then(if(lastFocus == "${result.id}:add") Modifier.focusRequester(focus) else Modifier).onFocusChanged { if(it.isFocused) lastFocus = "${result.id}:add" }) { vm.add(result, false, watch) }
                        TvAction(if(state.adding == result.id) "Adding…" else "Watch", state.adding == null,
                            Modifier.then(if(lastFocus == "${result.id}:watch") Modifier.focusRequester(focus) else Modifier).onFocusChanged { if(it.isFocused) lastFocus = "${result.id}:watch" }, primary = true) { vm.add(result, true, watch) }
                    }
                }
            }
        }
    }
}
