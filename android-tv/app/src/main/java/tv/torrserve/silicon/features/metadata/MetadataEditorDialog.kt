package tv.torrserve.silicon.features.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable fun MetadataEditorDialog(item: LibraryItem, editor: MetadataEditor, dismiss: () -> Unit) {
    var query by remember { mutableStateOf(item.title) }
    var provider by remember { mutableStateOf(item.metadata?.provider ?: "OMDb") }
    var kind by remember { mutableStateOf(item.metadata?.kind ?: MediaKind.Movie) }
    var results by remember { mutableStateOf<List<MetadataMatch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
    val failed = stringResource(R.string.phone_operation_failed); val empty = stringResource(R.string.metadata_no_matches)
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; message = failed } finally { busy = false } }
    }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(660.dp).heightIn(max = 450.dp).background(Surface, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction(stringResource(R.string.phone_close), modifier = Modifier.focusRequester(focus), action = dismiss)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    EditField(stringResource(R.string.metadata_rename), item.title, russian = true) { title -> action { editor.rename(item.hash, title) } }
                    EditField(stringResource(R.string.metadata_query), query, russian = true) { query = it }
                }
                item { ChoiceRow(listOf("OMDb", "TMDB", "Kinopoisk", "AniList"), provider) { provider = it } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction(stringResource(R.string.metadata_movie), selected = kind == MediaKind.Movie) { kind = MediaKind.Movie }
                    TvAction(stringResource(R.string.metadata_tv), selected = kind == MediaKind.Tv) { kind = MediaKind.Tv }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction(stringResource(R.string.metadata_find)) { action { results = editor.search(query, provider, kind); if (results.isEmpty()) message = empty } }
                    TvAction(stringResource(R.string.metadata_reset)) { reset = true }
                } }
                item { if (busy) TvText(stringResource(R.string.server_busy)); Message(message) }
                items(results, key = { "${it.value.provider}:${it.value.id}" }) { option ->
                    TvAction(listOfNotNull(option.value.title, option.value.year?.toString()).joinToString(" · ")) { action { editor.select(item.hash, option); dismiss() } }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    if (reset) Confirm(stringResource(R.string.metadata_reset), stringResource(R.string.metadata_reset_warning), { reset = false }, confirmLabel = stringResource(R.string.metadata_reset), russian = true) {
        reset = false; action { editor.reset(item.hash); dismiss() }
    }
}
