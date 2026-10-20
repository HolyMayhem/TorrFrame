package tv.torrframe.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import tv.torrframe.R
import tv.torrframe.ui.components.TvAction
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.theme.*

/** A focusable, scrollable TV picker shared by playback and the settings dialog. */
@Composable internal fun PlayerChoices(players: List<ExternalPlayer>, includeInternal: Boolean, selected: String = "",
    message: String = "", busy: Boolean = false, choose: (ExternalPlayer?) -> Unit, dismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().background(Surface, RoundedCornerShape(24.dp)).padding(24.dp)
        .testTag("player:choices"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvText(stringResource(R.string.player_choose), style = MaterialTheme.typography.headlineSmall)
        if (message.isNotBlank()) TvText(message, color = Muted, translate = false)
        if (players.isEmpty()) TvText(stringResource(R.string.player_none), color = Muted)
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (includeInternal) item("internal") {
                TvAction(stringResource(R.string.player_internal), enabled = !busy,
                    modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("player:internal")) { choose(null) }
            }
            itemsIndexed(players, key = { _, p -> p.component }) { index, player ->
                TvAction(player.label, enabled = !busy, selected = selected == player.component, translate = false,
                    modifier = Modifier.fillMaxWidth().then(if (!includeInternal && index == 0) Modifier.focusRequester(first) else Modifier)
                        .testTag("player:external:${player.component}")) { choose(player) }
            }
            item("cancel") {
                TvAction(stringResource(R.string.dialog_cancel), enabled = !busy,
                    modifier = Modifier.fillMaxWidth().then(if (!includeInternal && players.isEmpty()) Modifier.focusRequester(first) else Modifier)
                        .testTag("player:cancel"), action = dismiss)
            }
        }
    }
    LaunchedEffect(players, includeInternal, busy, message) {
        if (!busy) { withFrameNanos { }; input.requestInputMode(InputMode.Keyboard); first.requestFocus() }
    }
}
