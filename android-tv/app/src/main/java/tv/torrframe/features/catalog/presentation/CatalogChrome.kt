package tv.torrframe.features.catalog.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.sound.UiDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import tv.torrframe.R
import tv.torrframe.features.catalog.domain.CatalogFilter
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*

@Composable internal fun CatalogSearchField(query: String, modifier: Modifier = Modifier, open: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val motion = rememberTvButtonMotion(container = Surface, outline = Color.Transparent, shape = shape,
        focusedContainer = SurfaceSecondary, focusedContent = TextPrimary, focusedOutline = TextPrimary.copy(alpha = .8f))
    Button(onClick = { motion.activate(); open() }, modifier = modifier.heightIn(min = 38.dp)
        .then(motion.modifier).then(motion.pointerClick(action = open)),
        shape = ButtonDefaults.shape(shape), colors = motion.colors, border = motion.border,
        interactionSource = motion.interactions, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_search), contentDescription = null,
                modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(Muted))
            TvText(query.ifBlank { catalogText("Find a movie or series", "Найти фильм или сериал") },
                color = if (query.isBlank()) Muted else TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, translate = false, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Genres stay one remote action away without a permanently scrolling filter rail. */
@Composable internal fun CatalogGenreDialog(current: CatalogFilter, dismiss: () -> Unit, choose: (CatalogFilter) -> Unit) {
    val entry = remember { FocusRequester() }
    val genres = remember { CatalogFilter.entries.filter { it.isGenre } }
    val initial = current.takeIf { it.isGenre } ?: genres.first()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = genres.indexOf(initial))
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(.8f).fillMaxHeight(.8f)
            .background(Surface, RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp))
            .padding(20.dp).testTag("catalog:genres-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvBackAction(action = dismiss)
                TvText(catalogText("Genres", "Жанры"), style = MaterialTheme.typography.headlineSmall)
            }
            LazyColumn(Modifier.weight(1f).testTag("catalog:genres-list"), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(genres, key = { it.name }) { filter ->
                    TvQuietAction(filterTitle(filter), selected = current == filter,
                        modifier = Modifier.fillMaxWidth().then(if (filter == initial) Modifier.focusRequester(entry) else Modifier)
                            .testTag("catalog:filter:${filter.name}").semantics { selected = current == filter }) { choose(filter) }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; entry.requestFocus() }
    }
}
