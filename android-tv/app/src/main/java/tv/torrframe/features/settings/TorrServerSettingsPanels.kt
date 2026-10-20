package tv.torrframe.features.settings

import tv.torrframe.ui.sound.UiBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.sound.UiDialog as Dialog
import androidx.tv.material3.MaterialTheme
import tv.torrframe.R
import tv.torrframe.core.server.*
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*

@Composable internal fun TorrServerConnectionPanel(state: TorrServerSettingsUi, connection: ConnectionState,
    server: ServerState, vm: TorrServerSettingsViewModel, engineFocus: FocusRequester, openEngine: () -> Unit) {
    val s = state.settings
    SettingsGroup(stringResource(R.string.server_title)) {
        val status = if (s.useLocalServer && server is ServerState.Starting) ConnectionStatus.Connecting else connection.status
        TvText(stringResource(when (status) {
            ConnectionStatus.Connected -> R.string.server_connected
            ConnectionStatus.Connecting -> R.string.server_connecting
            ConnectionStatus.Unavailable -> R.string.server_unavailable
            ConnectionStatus.Error -> R.string.server_connection_error
        }), color = if (status == ConnectionStatus.Connected) AccentText else Muted, modifier = Modifier.testTag("server:connection"))
        connection.failure?.let { failure ->
            TvText(stringResource(when (failure) {
                408 -> R.string.server_timeout
                401, 403 -> R.string.server_access_denied
                -2 -> R.string.server_invalid_response
                else -> R.string.server_operation_failed
            }), color = Muted)
        }
        if (connection.version.isNotBlank()) TvText(stringResource(R.string.server_version, connection.version), color = Muted)
        EditField(stringResource(R.string.server_url), s.torrServerUrl, keyboardType = KeyboardType.Uri, russian = true, onValue = vm::address)
        TvText(stringResource(R.string.server_default_hint), color = Muted)
        TvToggle(stringResource(R.string.server_mode), s.useLocalServer, Modifier.testTag("server:local"), vm::local)
        TvText(stringResource(if (s.useLocalServer) R.string.server_local_hint else R.string.server_remote_hint), color = Muted)
        TvAction(stringResource(R.string.server_default_url), action = vm::defaultAddress)
        TvAction(stringResource(R.string.server_check), modifier = Modifier.testTag("server:check"), action = vm::check)
        TvAction(stringResource(R.string.engine_open), modifier = Modifier.focusRequester(engineFocus).testTag("server:engine"), action = openEngine)
    }
    if (s.useLocalServer) SettingsGroup(stringResource(R.string.server_process_title)) {
        TvText(when (server) {
            is ServerState.Running -> stringResource(R.string.server_process_running, server.version)
            ServerState.Starting -> stringResource(R.string.server_process_starting)
            ServerState.Stopping -> stringResource(R.string.server_process_stopping)
            ServerState.Stopped -> stringResource(R.string.server_process_stopped)
            is ServerState.Error -> server.message
        }, color = Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvAction(stringResource(R.string.server_start), modifier = Modifier.testTag("server:start"), action = vm::start)
            TvAction(stringResource(R.string.server_stop), modifier = Modifier.testTag("server:stop"), action = vm::stop)
            TvAction(stringResource(R.string.server_restart), action = vm::restart)
        }
        EditField(stringResource(R.string.server_local_port), s.port.toString(), keyboardType = KeyboardType.Number, russian = true, onValue = vm::port)
        TvToggle(stringResource(R.string.server_lan), s.lanAccess, Modifier.testTag("server:lan"), vm::lan)
        TvText(stringResource(R.string.server_lan_hint), color = Muted)
        if (s.lanAccess) {
            TvText(stringResource(R.string.server_web_title), color = Muted)
            TvText(state.webUrl.ifBlank { stringResource(R.string.server_web_no_network) }, modifier = Modifier.testTag("server:web-url"))
            TvText(stringResource(R.string.server_web_hint), color = Muted)
        }
        TvToggle(stringResource(R.string.server_boot), s.startOnBoot, Modifier.testTag("server:boot"), vm::boot)
        Message(state.backgroundError)
        TvText(stringResource(R.string.server_boot_hint), color = Muted)
        TvText(stringResource(R.string.server_root_hint), color = Muted)
    }
    SettingsGroup(stringResource(R.string.server_speed_test)) {
        TvText(stringResource(R.string.server_speed_hint), color = Muted)
        TvAction(stringResource(R.string.server_speed_test), modifier = Modifier.testTag("server:diagnostics"), action = vm::diagnose)
        state.diagnostics?.let { result ->
            TvText(stringResource(R.string.server_latency, result.latencyMs), modifier = Modifier.testTag("server:latency"))
            if (result.megabitsPerSecond != null && result.bytes != null) {
                TvText(stringResource(R.string.server_bandwidth, result.megabitsPerSecond, result.bytes))
                TvText(stringResource(R.string.server_bandwidth_note), color = Muted)
            } else TvText(stringResource(if (result.bandwidthFailure in listOf(404, 405, 501))
                R.string.server_bandwidth_unsupported else R.string.server_bandwidth_failed), color = Muted)
        }
    }
}

@Composable internal fun ServerOperationStatus(state: TorrServerSettingsUi) {
    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // TV Material has no progress component; a time-based dot indicator keeps the existing UI stack.
        var dots by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(400); dots = (dots + 1) % 4 } }
        TvText(stringResource(R.string.server_busy) + "·".repeat(dots), color = AccentText)
    }
    Message(state.message)
}

@Composable internal fun EngineSettingsScreen(state: TorrServerSettingsUi, vm: TorrServerSettingsViewModel,
    entryFocus: FocusRequester? = null, back: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    var confirm by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvBackAction(modifier = Modifier.focusRequester(backFocus)
                .then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier).testTag("engine:back"), action = back)
            SectionHeading(stringResource(R.string.engine_title), stringResource(R.string.engine_subtitle))
        }
        TvText(state.settings.torrServerUrl, color = Muted)
        ServerOperationStatus(state)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("actions") { SettingsGroup(stringResource(R.string.engine_title)) {
                TvText(stringResource(R.string.engine_warning), color = Muted)
                TvAction(stringResource(R.string.engine_reload), modifier = Modifier.testTag("engine:reload"), action = vm::loadEngine)
                TvAction(stringResource(R.string.engine_save), enabled = state.engine != null, modifier = Modifier.testTag("engine:save")) {
                    if (!state.busy) { if (state.edits.isNotEmpty()) confirm = "save" else vm.saveEngine() }
                }
                TvAction(stringResource(R.string.engine_reset), enabled = state.engine != null && state.engineVersion == "MatriX.146.1",
                    modifier = Modifier.testTag("engine:reset")) { if (!state.busy) confirm = "reset" }
                if (state.engineVersion.isNotEmpty() && state.engineVersion != "MatriX.146.1")
                    TvText(stringResource(R.string.engine_reset_unavailable), color = Muted)
                if (state.engine == null) TvText(stringResource(R.string.engine_empty), color = Muted)
            } }
            val json = state.engine
            if (json != null) items(EngineSettings.fields.filter { it.supported(json) }, key = { it.key }) { field ->
                val value = state.edits[field.key] ?: field.display(json)
                SettingsGroup(stringResource(field.label)) {
                    TvText(stringResource(field.hint), color = Muted)
                    if (field.boolean) TvToggle(stringResource(field.label), value.toBoolean(), Modifier.testTag("engine:${field.key}")) {
                        vm.editEngine(field, it.toString())
                    } else EditField(stringResource(field.label), value, keyboardType = KeyboardType.Decimal, russian = true) {
                        vm.editEngine(field, it)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { backFocus.requestFocus(); vm.requestEngineLoad() }
    confirm?.let { operation ->
        val reset = operation == "reset"
        ServerConfirm(stringResource(if (reset) R.string.engine_reset_confirm else R.string.engine_apply_confirm),
            stringResource(if (reset) R.string.engine_reset_warning else R.string.engine_apply_warning),
            stringResource(if (reset) R.string.engine_reset_action else R.string.engine_apply_action),
            { confirm = null }) { confirm = null; if (reset) vm.resetEngine() else vm.saveEngine() }
    }
}

@Composable private fun ServerConfirm(title: String, description: String, action: String,
    dismiss: () -> Unit, confirm: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(.85f).background(Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TvText(title, style = MaterialTheme.typography.headlineSmall)
            TvText(description, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(stringResource(R.string.dialog_cancel), modifier = Modifier.focusRequester(focus), action = dismiss)
                TvAction(action, action = confirm)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
