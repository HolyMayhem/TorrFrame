package tv.torrframe.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.torrframe.R
import tv.torrframe.core.telemetry.TelemetryConsent
import tv.torrframe.core.telemetry.TelemetryController
import tv.torrframe.ui.components.TvAction
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.components.TvToggle
import tv.torrframe.ui.sound.UiDialog
import tv.torrframe.ui.theme.Muted

@Composable internal fun TelemetrySettings(controller: TelemetryController) {
    val consent by controller.consent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun change(operation: suspend () -> Boolean) {
        if (working) return
        working = true
        controller.changeInAppScope(operation) { saved ->
            failed = !saved
            working = false
        }
    }
    SettingsGroup(stringResource(R.string.telemetry_privacy)) {
        TvText(stringResource(R.string.telemetry_description), color = Muted)
        if (consent != TelemetryConsent.Loading) {
            TvToggle(stringResource(R.string.telemetry_enable), consent == TelemetryConsent.Enabled,
                Modifier.testTag("telemetry:toggle")) { enabled -> change { controller.choose(enabled) } }
        }
        TvAction(stringResource(R.string.telemetry_preview), enabled = !working, modifier = Modifier.testTag("telemetry:preview")) {
            scope.launch { report = controller.preview() }
        }
        TvAction(stringResource(R.string.telemetry_reset), enabled = !working && consent == TelemetryConsent.Enabled,
            modifier = Modifier.testTag("telemetry:reset")) { confirmReset = true }
        if (failed) TvText(stringResource(R.string.settings_operation_failed), color = Muted)
    }
    report?.let { data ->
        val dismiss = { report = null }
        val close = remember { FocusRequester() }
        UiDialog(onDismissRequest = dismiss) {
            SettingsGroup(stringResource(R.string.telemetry_preview)) {
                TvText(stringResource(R.string.telemetry_preview_hint), color = Muted)
                Column(Modifier.heightIn(max = 280.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(data, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                TvAction(stringResource(R.string.telemetry_close), modifier = Modifier.focusRequester(close), action = dismiss)
            }
            LaunchedEffect(Unit) { withFrameNanos {}; close.requestFocus() }
        }
    }
    if (confirmReset) {
        val dismiss = { confirmReset = false }
        val cancel = remember { FocusRequester() }
        UiDialog(onDismissRequest = dismiss) {
            SettingsGroup(stringResource(R.string.telemetry_reset)) {
                TvText(stringResource(R.string.telemetry_reset_hint), color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvAction(stringResource(R.string.telemetry_cancel), modifier = Modifier.focusRequester(cancel), action = dismiss)
                    TvAction(stringResource(R.string.telemetry_reset_confirm), destructive = true) {
                        dismiss()
                        change { controller.reset() }
                    }
                }
            }
            LaunchedEffect(Unit) { withFrameNanos {}; cancel.requestFocus() }
        }
    }
}

@Composable internal fun TelemetryInvitation(choose: (Boolean) -> Unit, dismiss: () -> Unit) {
    val decline = remember { FocusRequester() }
    UiDialog(onDismissRequest = dismiss) {
        SettingsGroup(stringResource(R.string.telemetry_privacy)) {
            TvText(stringResource(R.string.telemetry_description), color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(stringResource(R.string.telemetry_accept), modifier = Modifier.testTag("telemetry:offer:accept")) { choose(true) }
                TvAction(stringResource(R.string.telemetry_decline),
                    modifier = Modifier.focusRequester(decline).testTag("telemetry:offer:decline")) { choose(false) }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos {}; decline.requestFocus() }
    }
}
