package tv.torrframe.phoneRemote.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.torrframe.R
import tv.torrframe.phoneRemote.security.SessionManager
import tv.torrframe.ui.components.*
import tv.torrframe.ui.sound.UiDialog as Dialog
import tv.torrframe.ui.theme.*

@Composable fun PhoneRemoteDevices(sessions: SessionManager, dismiss: () -> Unit) {
    val paired by sessions.sessions.collectAsStateWithLifecycle()
    val devices = paired.filter { it.expiresAt > System.currentTimeMillis() }
    val scope = rememberCoroutineScope()
    val backFocus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(560.dp).heightIn(max = 440.dp).background(Surface, RoundedCornerShape(24.dp))
            .padding(24.dp).testTag("phone:devices"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvBackAction(Modifier.focusRequester(backFocus).testTag("phone:devices:back"), dismiss)
                TvText(stringResource(R.string.phone_devices), style = MaterialTheme.typography.headlineSmall, translate = false)
            }
            TvText(stringResource(R.string.phone_devices_hint), color = Muted, style = MaterialTheme.typography.bodySmall, translate = false)
            if (devices.isEmpty()) TvText(stringResource(R.string.phone_no_devices), color = Muted, translate = false)
            if (error) TvText(stringResource(R.string.phone_operation_failed), color = Muted, translate = false)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(devices, key = { it.id }) { session ->
                    Row(Modifier.fillMaxWidth().background(SurfaceSecondary, RoundedCornerShape(16.dp)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_phone), contentDescription = null,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Muted), modifier = Modifier.size(22.dp))
                        TvText(session.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
                        TvAction(stringResource(R.string.phone_unpair), enabled = !busy, destructive = true,
                            modifier = Modifier.testTag("phone:device:revoke:${session.id}")) {
                            busy = true; error = false
                            scope.launch {
                                try { sessions.revoke(session.id); backFocus.requestFocus() }
                                catch (e: Exception) { if (e is CancellationException) throw e; error = true }
                                finally { busy = false }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { backFocus.requestFocus() }
    }
}
