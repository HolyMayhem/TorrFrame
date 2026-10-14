package tv.torrserve.silicon.phoneRemote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.*
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.R
import tv.torrserve.silicon.phoneRemote.discovery.QrCodeGenerator
import tv.torrserve.silicon.phoneRemote.security.PairingLink
import tv.torrserve.silicon.phoneRemote.server.*
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

@Composable fun PhoneRemoteSettings(graph: AppGraph, entryFocus: FocusRequester? = null) {
    val manager = graph.phoneRemote
    val state by manager.state.collectAsStateWithLifecycle()
    val preference by graph.phonePreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var qr by remember { mutableStateOf(false) }; var devices by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    val failureText = stringResource(R.string.phone_operation_failed)
    val invalidPort = stringResource(R.string.phone_port_conflict)
    TvToggle(stringResource(R.string.phone_enable), preference.enabled, Modifier.testTag("phone:enabled")
        .then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)) { enabled ->
        scope.launch { try { manager.enabled(enabled); failure = "" } catch (e: Exception) { if (e is CancellationException) throw e; failure = failureText } }
    }
    PhoneStatus(state)
    Message(state.error); Message(failure)
    EditField(stringResource(R.string.phone_port), preference.port.toString(), keyboardType = KeyboardType.Number, russian = true) { text ->
        val port = text.toIntOrNull()
        if (port == null || port !in 1024..65535 || port == graph.settings.state.value.port) failure = invalidPort
        else scope.launch { try { manager.port(port); failure = "" } catch (e: Exception) { if (e is CancellationException) throw e; failure = failureText } }
    }
    if (state.url.isNotBlank()) TvText(stringResource(R.string.phone_address, state.url), color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvAction(stringResource(R.string.phone_qr), enabled = state.status == PhoneRemoteStatus.Running, modifier = Modifier.testTag("phone:qr")) { qr = true }
        TvAction(stringResource(R.string.phone_devices)) { devices = true }
    }
    if (preference.enabled && state.status == PhoneRemoteStatus.Error) TvAction(stringResource(R.string.phone_retry)) {
        scope.launch { try { manager.enabled(true) } catch (e: Exception) { if (e is CancellationException) throw e; failure = failureText } }
    }
    TvText(stringResource(R.string.phone_http_warning), color = Muted)
    if (qr) PhoneRemoteQrDialog(manager) { qr = false }
    if (devices) PhoneRemoteDevices(manager) { devices = false }
}

@Composable private fun PhoneStatus(state: PhoneRemoteState) {
    TvText(stringResource(when (state.status) {
        PhoneRemoteStatus.Disabled -> R.string.phone_disabled
        PhoneRemoteStatus.Starting -> R.string.phone_starting
        PhoneRemoteStatus.Running -> R.string.phone_running
        PhoneRemoteStatus.Error -> R.string.phone_error
        PhoneRemoteStatus.NoNetwork -> R.string.phone_no_network
    }), color = if (state.status == PhoneRemoteStatus.Running) AccentText else Muted, modifier = Modifier.testTag("phone:status"))
}

@Composable fun PhoneRemoteQrDialog(manager: PhoneRemoteManager, dismiss: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf<PairingLink?>(null) }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var expired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
    var qrJob by remember { mutableStateOf<Job?>(null) }
    fun refresh() { qrJob?.cancel(); qrJob = scope.launch {
        if (manager.state.value.status != PhoneRemoteStatus.Running) return@launch
        val value = manager.pairingLink(); link = value; expired = false
        image = withContext(Dispatchers.Default) { QrCodeGenerator.bitmap(value.url) }
    } }
    LaunchedEffect(state.status, state.url) { qrJob?.cancel(); image = null; link = null; if (state.status == PhoneRemoteStatus.Running) refresh() }
    LaunchedEffect(link) { link?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); expired = true; image = null } }
    val window = LocalWindowInfo.current.containerSize
    val maxHeight = with(LocalDensity.current) { window.height.toDp() } - 32.dp
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 960.dp).heightIn(max = maxHeight).fillMaxWidth(.92f).testTag("phone:qr:panel").background(Surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(stringResource(R.string.phone_title), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(210.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    image?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.phone_qr), modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhoneStatus(state)
                    TvText(state.url, color = AccentText)
                    TvText(stringResource(if (expired) R.string.phone_qr_expired else R.string.phone_qr_instruction), color = Muted)
                    if (!expired) link?.let {
                        TvText(stringResource(R.string.phone_pair_code_title), color = Muted)
                        TvText(it.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp),
                            modifier = Modifier.testTag("phone:pair-code"), translate = false)
                    }
                    TvText(stringResource(R.string.phone_qr_security_hint), color = Muted, fontSize = 12.sp)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(stringResource(R.string.phone_close), modifier = Modifier.focusRequester(focus).testTag("phone:qr:close"), action = dismiss)
                TvAction(stringResource(R.string.phone_qr_refresh), enabled = state.status == PhoneRemoteStatus.Running) { refresh() }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable private fun PhoneRemoteDevices(manager: PhoneRemoteManager, dismiss: () -> Unit) {
    val sessions by manager.sessions.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(560.dp).heightIn(max = 400.dp).background(Surface, RoundedCornerShape(24.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(stringResource(R.string.phone_devices), style = MaterialTheme.typography.headlineSmall)
            if (sessions.isEmpty()) TvText(stringResource(R.string.phone_no_devices), color = Muted)
            if (error) TvText(stringResource(R.string.phone_operation_failed))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sessions, key = { it.id }) { session ->
                    TvAction("${session.label} · ${stringResource(R.string.phone_revoke)}") {
                        scope.launch { try { manager.sessions.revoke(session.id); focus.requestFocus() } catch (e: Exception) { if (e is CancellationException) throw e; error = true } }
                    }
                }
            }
            TvAction(stringResource(R.string.phone_close), modifier = Modifier.focusRequester(focus), action = dismiss)
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable fun PhoneRemoteShortcut(open: () -> Unit) {
    val motion = rememberTvButtonMotion(outline = androidx.compose.ui.graphics.Color.Transparent, shape = androidx.compose.foundation.shape.CircleShape)
    androidx.tv.material3.Button(onClick = { motion.activate(); open() }, modifier = motion.modifier.then(motion.pointerClick(action = open)).size(30.dp).testTag("phone:shortcut"), contentPadding = PaddingValues(6.dp),
        interactionSource = motion.interactions, border = motion.border, colors = motion.colors,
        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Image(painterResource(R.drawable.ic_phone), modifier = motion.contentModifier, contentDescription = stringResource(R.string.phone_qr), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.tv.material3.LocalContentColor.current))
    }
}
