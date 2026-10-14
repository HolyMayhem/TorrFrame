package tv.torrserve.silicon.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.storage.UiScale
import tv.torrserve.silicon.ui.components.TvAction
import tv.torrserve.silicon.ui.components.TvText
import tv.torrserve.silicon.ui.theme.Muted

@Composable internal fun UiScaleControls(percent: Int, change: (Int) -> Unit, reset: () -> Unit) {
    val less = remember { FocusRequester() }; val more = remember { FocusRequester() }
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val decrease = stringResource(R.string.ui_scale_decrease)
    val increase = stringResource(R.string.ui_scale_increase)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvText(stringResource(R.string.ui_scale_title), color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvAction("−", enabled = percent > UiScale.MIN,
                modifier = Modifier.focusRequester(less).testTag("scale:minus").semantics { contentDescription = decrease }) {
                if (percent - UiScale.STEP <= UiScale.MIN) pendingFocus = more
                change(-1)
            }
            TvText("$percent%", modifier = Modifier.width(52.dp).testTag("scale:value"), translate = false)
            TvAction("+", enabled = percent < UiScale.MAX,
                modifier = Modifier.focusRequester(more).testTag("scale:plus").semantics { contentDescription = increase }) {
                if (percent + UiScale.STEP >= UiScale.MAX) pendingFocus = less
                change(1)
            }
        }
        TvAction(stringResource(R.string.ui_scale_default), modifier = Modifier.testTag("scale:reset"), action = reset)
        TvText(stringResource(R.string.ui_scale_hint), color = Muted)
    }
    LaunchedEffect(percent) {
        pendingFocus?.requestFocus()
        pendingFocus = null
    }
}
