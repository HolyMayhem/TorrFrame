package tv.torrserve.silicon.features.catalog.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable internal fun userListTitle(list: CatalogUserList) = if (list == CatalogUserList.Favorites)
    catalogText("Favorites", "Избранное") else catalogText("Watch later", "Смотреть позже")

@Composable internal fun CatalogListMenu(item: CatalogItem, state: CatalogUi, dismiss: () -> Unit,
    choose: (CatalogUserList) -> Unit) {
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val entry = state.userEntries.firstOrNull { it.ref == item.ref }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(360.dp).background(Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(20.dp).testTag("catalog:list-menu"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvText(item.title, style = MaterialTheme.typography.titleLarge, translate = false)
                if (state.listError) TvText(catalogText("Could not save the list. Try again.", "Не удалось сохранить список. Попробуйте ещё раз."), color = Muted)
                CatalogUserList.entries.forEachIndexed { index, list ->
                    val added = entry?.contains(list) == true
                    val label = when(list) {
                        CatalogUserList.Favorites -> if (added) catalogText("Remove from favorites", "Удалить из избранного") else catalogText("Add to favorites", "Добавить в избранное")
                        CatalogUserList.WatchLater -> if (added) catalogText("Remove from watch later", "Удалить из «Смотреть позже»") else catalogText("Watch later", "Смотреть позже")
                    }
                    TvAction(label, enabled = state.listsReady && !state.listBusy,
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .testTag("catalog:list-menu:${list.name}")) { choose(list) }
                }
                TvBackAction(Modifier.focusRequester(back).testTag("catalog:list-menu:back"), dismiss)
            }
        }
        LaunchedEffect(state.listsReady, state.listBusy) {
            withFrameNanos { }
            if (state.listsReady && !state.listBusy) first.requestFocus() else back.requestFocus()
        }
    }
}
