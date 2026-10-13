package tv.torrserve.silicon.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import coil3.compose.AsyncImage
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*
import tv.torrserve.silicon.features.metadata.MediaNameParser

@Composable fun MovieDetailScreen(item: LibraryItem, state: LibraryUi, vm: LibraryViewModel,
    back: () -> Unit, play: (String, TorrentFile) -> Unit) {
    var removing by remember { mutableStateOf(false) }
    val watchFocus = remember { FocusRequester() }; val backFocus = remember { FocusRequester() }
    LaunchedEffect(item.hash) { withFrameNanos { }; runCatching { watchFocus.requestFocus() }.onFailure { backFocus.requestFocus() } }
    fun watch() { vm.files(item.hash) { files ->
        tv.torrserve.silicon.features.player.preferredVideo(item, files)?.let { preferred -> vm.closeFiles(); play(item.hash, preferred) }
    } }
    Box(Modifier.fillMaxSize()) {
        if (item.metadata?.backdrop?.isNotBlank() == true) AsyncImage(item.metadata.backdrop, null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Background, Background.copy(alpha = .8f), Background.copy(alpha = .5f)))))
        LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { TvAction("Back", modifier = Modifier.focusRequester(backFocus), action = back) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    MovieCover(item, Modifier.width(if (LocalTvLayout.current.short) 150.dp else 180.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("movie:title"), translate = false)
                        item.metadata?.originalTitle?.takeIf { it.isNotBlank() && it != item.title }?.let {
                            Text(it, color = Muted, translate = false)
                        }
                        Text(listOfNotNull(item.metadata?.year?.toString(), item.metadata?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                            item.metadata?.runtime?.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · "), color = Muted, modifier = Modifier.testTag("movie:facts"))
                        item.metadata?.genres?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, modifier = Modifier.testTag("movie:genres"), translate = false) }
                        Text(item.metadata?.overview.orEmpty().ifBlank { item.sourceTitle }, modifier = Modifier.testTag("movie:overview"), maxLines = 8, overflow = TextOverflow.Ellipsis, translate = false)
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(if(item.inProgress) "Continue Watching" else "Watch", !state.busy, Modifier.focusRequester(watchFocus), primary = true) { watch() }
                TvAction("Files", !state.busy) { vm.files(item.hash) }
                TvAction("Refresh Metadata", !state.busy) { vm.metadata(item.hash) }
                TvAction("Remove", !state.busy) { removing = true }
            } }
            item { if(state.busy) Text("Loading…"); Message(state.message) }
        }
    }
    if(removing) Confirm("Remove from library?", "The torrent and its saved playback history will be removed.",
        { removing = false }) { removing = false; vm.remove(item.hash, back) }
    if(state.fileHash == item.hash && state.files.isNotEmpty()) {
        val fileFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = vm::closeFiles) {
            Column(Modifier.width(600.dp).heightIn(max = 420.dp).background(Surface, RoundedCornerShape(24.dp)).border(1.dp, Outline, RoundedCornerShape(24.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Choose a video", style = MaterialTheme.typography.headlineSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(state.files, key = { it.id }) { file ->
                        val parsed = MediaNameParser.parse(file.path.substringAfterLast('/'))
                        val label = if(parsed.season != null) "S${parsed.season} · E${parsed.episode ?: "—"}  ${file.path.substringAfterLast('/')}" else file.path.substringAfterLast('/')
                        TvAction(label, modifier = Modifier.fillMaxWidth().then(if(file.id == state.files.first().id) Modifier.focusRequester(fileFocus) else Modifier)) { vm.closeFiles(); play(item.hash, file) }
                    }
                }
                TvAction("Back", action = vm::closeFiles)
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; fileFocus.requestFocus() }
    }
}

@Composable internal fun MovieCover(item: LibraryItem, modifier: Modifier = Modifier) {
    var failed by remember(item.metadata?.poster) { mutableStateOf(false) }
    var loaded by remember(item.metadata?.poster) { mutableStateOf(false) }
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(SurfaceSecondary).testTag("movie:poster"), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (!failed && item.metadata?.poster?.isNotBlank() == true) AsyncImage(item.metadata.poster,
            contentDescription = item.title, modifier = Modifier.fillMaxSize().testTag(if (loaded) "movie:poster:loaded" else "movie:poster:loading"),
            contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { failed = true })
        else Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = Muted, translate = false)
    }
}
