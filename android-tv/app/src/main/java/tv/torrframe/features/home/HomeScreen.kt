package tv.torrframe.features.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.*
import tv.torrframe.ui.components.TvText as Text
import tv.torrframe.core.models.LibraryItem
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.Muted
import tv.torrframe.ui.theme.LocalTvLayout
import tv.torrframe.ui.theme.TvDimensions

@Composable fun HomeScreen(items: List<LibraryItem>, search: () -> Unit, library: () -> Unit,
    settings: () -> Unit, fallback: FocusRequester, entryFocus: FocusRequester? = null, open: (String) -> Unit) {
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    if (items.isEmpty()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if(maxWidth < 780.dp || maxHeight < 260.dp) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    HomeWelcome(Modifier.widthIn(max = 600.dp), search, settings, entryFocus)
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomeWelcome(Modifier.weight(1.35f), search, settings, entryFocus)
                    InfoPanel("Make it yours", "Your library is empty.", Modifier.weight(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeStep("01  Connect Jackett", "Search your trackers in one place.")
                            HomeStep("02  Choose and watch", "A built-in player, ready to go.")
                            HomeStep("03  Continue anytime", "Your place is saved automatically.")
                        }
                    }
                }
            }
        }; return
    }
    val columns = LocalTvLayout.current.libraryColumns
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        val watching = items.filter { it.inProgress }.take(20)
        if (watching.isNotEmpty()) {
            item { Text("Continue Watching", style = MaterialTheme.typography.headlineSmall) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                items(watching, key = { it.hash }) { item -> Poster(item,
                    Modifier.width(TvDimensions.posterWidth).then(if(lastFocus == "continue:${item.hash}") Modifier.focusRequester(focus) else Modifier),
                    focused = { lastFocus = "continue:${item.hash}" }) { open(item.hash) } }
            } }
        }
        item { Text("My Library", style = MaterialTheme.typography.headlineSmall) }
        items(items.take(columns * 2).chunked(columns), key = { it.first().hash }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                row.forEach { item -> Poster(item, Modifier.weight(1f).then(if("library:${item.hash}" == lastFocus) Modifier.focusRequester(focus) else Modifier),
                    focused = { lastFocus = "library:${item.hash}" }) { open(item.hash) } }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { TvAction("My Library", modifier = if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier, action = library) }
    }
    LaunchedEffect(Unit) { if(lastFocus != null) { withFrameNanos { }; runCatching { focus.requestFocus() }.onFailure { fallback.requestFocus() } } }
}

@Composable private fun HomeWelcome(modifier: Modifier, search: () -> Unit, settings: () -> Unit, entryFocus: FocusRequester?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("YOUR PERSONAL CINEMA", color = Muted, fontSize = 12.sp)
        Text("Your next movie starts here.", style = MaterialTheme.typography.displaySmall)
        Text("Find films with Jackett. Your library and viewing progress will appear here.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Search Torrents", modifier = Modifier.testTag("home:search").then(if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier), primary = true, action = search)
            TvAction("Open Settings", modifier = Modifier.testTag("home:settings"), action = settings)
        }
    }
}

@Composable private fun HomeStep(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, color = Muted, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
    }
}
