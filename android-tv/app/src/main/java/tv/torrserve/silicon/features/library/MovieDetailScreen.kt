package tv.torrserve.silicon.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.stringResource
import tv.torrserve.silicon.R
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable fun MovieDetailScreen(item: LibraryItem, state: LibraryUi, vm: LibraryViewModel,
    back: () -> Unit, torrentSearch: (() -> Unit)? = null, restoreTorrentFocus: Boolean = false, jackettEnabled: Boolean = false, jackettSearch: (() -> Unit)? = null, restoreJackettFocus: Boolean = false, play: (String, TorrentFile) -> Unit) {
    var removing by remember { mutableStateOf(false) }
    val watchFocus = remember { FocusRequester() }; val backFocus = remember { FocusRequester() }
    val torrentFocus = remember { FocusRequester() }
    val removeFocus = remember { FocusRequester() }; val jackettFocus = remember { FocusRequester() }
    var jackettFocused by remember { mutableStateOf(false) }
    val removedFocusedJackett = !jackettEnabled && jackettFocused
    LaunchedEffect(jackettEnabled) {
        if (removedFocusedJackett) { withFrameNanos { }; (if(torrentSearch != null) torrentFocus else watchFocus).requestFocus() }
    }
    val fileFocus = remember { FocusRequester() }; val list = rememberLazyListState()
    var filesRequested by remember(item.hash) { mutableStateOf(false) }
    var focusFiles by remember(item.hash) { mutableStateOf(false) }
    var restoreAction by remember { mutableStateOf<String?>(null) }
    val files = (if (state.fileHash == item.hash) state.files else item.files).filter { it.isVideo }
    // Cached torrent files are shown immediately. Fetch once only when this card
    // has no file metadata, after any existing library operation has completed.
    LaunchedEffect(item.hash, state.busy) {
        if (!state.busy && files.isEmpty() && !filesRequested) { filesRequested = true; vm.files(item.hash) }
    }
    LaunchedEffect(focusFiles, files.isNotEmpty()) {
        if (focusFiles && files.isNotEmpty()) {
            list.animateScrollToItem(1) // Files follow the complete hero, including its actions.
            withFrameNanos { }; fileFocus.requestFocus(); focusFiles = false
        }
    }
    LaunchedEffect(restoreAction) {
        restoreAction?.let { action ->
            withFrameNanos { }
            if (action == "remove") removeFocus.requestFocus()
            restoreAction = null
        }
    }
    LaunchedEffect(item.hash) { withFrameNanos { };if(restoreTorrentFocus && torrentSearch!=null)(if(restoreJackettFocus && jackettEnabled)jackettFocus else torrentFocus).requestFocus()else backFocus.requestFocus() }
    fun watch() {
        if (files.size > 1 && tv.torrserve.silicon.features.player.preferredVideo(item, files) == null) {
            focusFiles = true; return
        }
        vm.files(item.hash) { files ->
            val preferred = tv.torrserve.silicon.features.player.preferredVideo(item, files)
            if (preferred != null) play(item.hash, preferred) else focusFiles = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().headerEdgeFade(enabled = list.canScrollBackward)) {
        val heroHeight = (maxHeight * .68f).coerceIn(280.dp, 420.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("movie:content"), state = list,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("hero") { MovieDetailHeader(item, heroHeight, backFocus, watchFocus, back) {
                FlowRow(Modifier.fillMaxWidth().testTag("movie:actions"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TvIconAction(R.drawable.ic_play, if (item.inProgress) "Continue Watching" else "Watch",
                    Modifier.focusRequester(watchFocus).focusProperties { up = backFocus; if (files.isNotEmpty()) down = fileFocus }
                        .testTag("movie:watch"), enabled = !state.busy, primary = true) { watch() }
                TvIconAction(R.drawable.ic_delete, "Remove", Modifier.focusRequester(removeFocus).focusProperties { up = backFocus }.testTag("movie:remove"),
                    enabled = !state.busy) { removing = true }
                TvAction("Refresh Metadata", !state.busy, Modifier.focusProperties { up = backFocus }.testTag("movie:metadata")) { vm.metadata(item.hash) }
                if(torrentSearch != null) TvAction("TorrentSearch", modifier = Modifier.focusRequester(torrentFocus).focusProperties { up = backFocus }.testTag("movie:torrentsearch"), action = torrentSearch)
                if (jackettEnabled) TvAction("Jackett", enabled = jackettSearch != null, modifier = Modifier.focusRequester(jackettFocus).onFocusChanged { jackettFocused=it.isFocused }.focusProperties { up = backFocus }.testTag("movie:jackett"), translate = false) { jackettSearch?.invoke() }
                }
            } }
            item("files") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("movie:files"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Files", style = MaterialTheme.typography.headlineSmall)
                        if (files.isNotEmpty()) Text(pluralStringResource(R.plurals.library_video_count, files.size, files.size),
                            color = Muted, style = MaterialTheme.typography.labelLarge, translate = false)
                    }
                    if(state.busy) Text("Loading…")
                    Message(state.message)
                    if (files.isEmpty() && !state.busy) TvAction(stringResource(R.string.library_load_files),
                        modifier = Modifier.testTag("movie:files:retry")) { vm.files(item.hash) }
                }
            }
            itemsIndexed(files, key = { _, file -> "video:${file.id}" }) { index, file ->
                MovieFileAction(file, index + 1, enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("movie:file:${file.id}")
                        .then(if (file.id == files.first().id) Modifier.focusRequester(fileFocus).focusProperties { up = watchFocus } else Modifier)) { play(item.hash, file) }
            }
        }
    }
    if(removing) Confirm("Remove from library?", "The torrent and its saved playback history will be removed.",
        { removing = false; restoreAction = "remove" }) { removing = false; vm.remove(item.hash, back) }

}
