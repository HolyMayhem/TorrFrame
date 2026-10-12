package tv.torrserve.silicon.features.home

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
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.core.models.LibraryItem
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.Muted

@Composable fun HomeScreen(items: List<LibraryItem>, search: () -> Unit, library: () -> Unit,
    addMagnet: () -> Unit, fallback: FocusRequester, entryFocus: FocusRequester? = null, open: (String) -> Unit) {
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    if (items.isEmpty()) {
        Row(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("YOUR PERSONAL CINEMA", color = Muted, fontSize = 12.sp)
                Text("Your next movie starts here.", style = MaterialTheme.typography.displaySmall)
                Text("Add a magnet link or search with Jackett. Your films and viewing progress will appear here.", color = Muted)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvAction("Search Torrents", modifier = Modifier.testTag("home:search").then(if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier), primary = true, action = search)
                    TvAction("Add Magnet Link", modifier = Modifier.testTag("home:add"), action = addMagnet)
                }
            }
            InfoPanel("Make it yours", "Your library is empty.", Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("01  Add a film", style = MaterialTheme.typography.titleMedium)
                        Text("Magnet links or Jackett search.", color = Muted, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("02  Choose and watch", style = MaterialTheme.typography.titleMedium)
                        Text("A built-in player, ready to go.", color = Muted, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("03  Continue anytime", style = MaterialTheme.typography.titleMedium)
                        Text("Your place is saved automatically.", color = Muted, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                }
            }
        }; return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        val watching = items.filter { it.inProgress }.take(20)
        if (watching.isNotEmpty()) {
            item { Text("Continue Watching", style = MaterialTheme.typography.headlineSmall) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp)) {
                items(watching, key = { it.hash }) { item -> Poster(item,
                    Modifier.width(144.dp).then(if(lastFocus == "continue:${item.hash}") Modifier.focusRequester(focus) else Modifier),
                    focused = { lastFocus = "continue:${item.hash}" }) { open(item.hash) } }
            } }
        }
        item { Text("My Library", style = MaterialTheme.typography.headlineSmall) }
        items(items.take(10).chunked(5), key = { it.first().hash }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(8.dp)) {
                row.forEach { item -> Poster(item, Modifier.weight(1f).then(if("library:${item.hash}" == lastFocus) Modifier.focusRequester(focus) else Modifier),
                    focused = { lastFocus = "library:${item.hash}" }) { open(item.hash) } }
                repeat(5-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { TvAction("My Library", modifier = if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier, action = library) }
    }
    LaunchedEffect(Unit) { if(lastFocus != null) { withFrameNanos { }; runCatching { focus.requestFocus() }.onFailure { fallback.requestFocus() } } }
}
