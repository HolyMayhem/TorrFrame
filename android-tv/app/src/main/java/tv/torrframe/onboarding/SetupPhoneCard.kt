package tv.torrframe.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import tv.torrframe.R
import tv.torrframe.phoneRemote.discovery.QrCodeGenerator
import tv.torrframe.phoneRemote.server.PhoneRemoteManager
import tv.torrframe.phoneRemote.server.PhoneRemoteStatus
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.theme.Muted
import tv.torrframe.ui.theme.Positive

/** Pairing links are short-lived; no QR bitmap or token is persisted. */
@Composable internal fun SetupPhoneCard(manager: PhoneRemoteManager, enabled: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by manager.state.collectAsStateWithLifecycle()
    val sessions by manager.sessions.sessions.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(manager, lifecycle, enabled, state.status, state.url, sessions.size) {
        bitmap = null; failed = false
        if (!enabled || state.status != PhoneRemoteStatus.Running) return@LaunchedEffect
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val link = manager.pairingLink()
                    bitmap = withContext(Dispatchers.Default) { QrCodeGenerator.bitmap(link.url) }
                    delay((link.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                    bitmap = null
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(170.dp).background(if (bitmap == null) Color.Transparent else Color.White, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.phone_qr), modifier = Modifier.fillMaxSize().padding(5.dp)) }
                ?: Image(painterResource(R.drawable.ic_phone), contentDescription = null,
                    colorFilter = ColorFilter.tint(Muted), modifier = Modifier.size(72.dp))
        }
        TvText(stringResource(when {
            !enabled -> R.string.phone_disabled
            sessions.isNotEmpty() -> R.string.setup_phone_connected
            failed -> R.string.phone_operation_failed
            state.status == PhoneRemoteStatus.NoNetwork -> R.string.phone_no_network
            state.status == PhoneRemoteStatus.Error -> R.string.phone_error
            state.status == PhoneRemoteStatus.Running -> R.string.setup_phone_scan
            else -> R.string.phone_starting
        }), color = if (enabled && sessions.isNotEmpty()) Positive else Muted)
    }
}
