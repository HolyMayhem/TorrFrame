package tv.torrframe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import tv.torrframe.R
import tv.torrframe.core.server.*
import tv.torrframe.ui.theme.*

enum class ServerIndicator { Online, Offline, Error, Busy }
fun serverIndicator(local: Boolean, server: ServerState, connection: ConnectionState): ServerIndicator {
    if (local) when (server) {
        ServerState.Starting, ServerState.Stopping -> return ServerIndicator.Busy
        ServerState.Stopped -> return ServerIndicator.Offline
        is ServerState.Error -> return ServerIndicator.Error
        is ServerState.Running -> Unit
    }
    return when (connection.status) {
        ConnectionStatus.Connected -> ServerIndicator.Online
        ConnectionStatus.Connecting -> ServerIndicator.Busy
        ConnectionStatus.Unavailable -> ServerIndicator.Offline
        ConnectionStatus.Error -> ServerIndicator.Error
    }
}
@Composable fun ServerStatusIndicator(local: Boolean, server: ServerState, connection: ConnectionState, open: () -> Unit) {
    val motion = rememberTvButtonMotion(shape = CircleShape, container = Surface,
        outline = Outline, focusedContainer = SurfaceSecondary,
        focusedContent = TextPrimary, focusedOutline = TextPrimary.copy(alpha = .8f))
    val compact = LocalTvLayout.current.narrow
    val status = serverIndicator(local, server, connection)
    val color = when (status) {
        ServerIndicator.Online -> Positive
        ServerIndicator.Offline -> Muted
        ServerIndicator.Error -> Color(0xFFD94747)
        ServerIndicator.Busy -> Color(0xFFE0AD24)
    }
    val label = stringResource(when (status) {
        ServerIndicator.Online -> R.string.server_connected
        ServerIndicator.Offline -> R.string.server_unavailable
        ServerIndicator.Error -> R.string.server_connection_error
        ServerIndicator.Busy -> if (server == ServerState.Stopping && local) R.string.server_process_stopping else R.string.server_connecting
    })
    Button(onClick = { motion.activate(); open() }, modifier = motion.modifier.then(motion.pointerClick(action = open))
        .height(TvDimensions.actionHeight).then(if (compact) Modifier.width(TvDimensions.actionHeight) else Modifier)
        .testTag("server:indicator").semantics { contentDescription = label },
        shape = ButtonDefaults.shape(CircleShape),
        interactionSource = motion.interactions, colors = motion.colors,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        border = motion.border,
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape).testTag("server:indicator:${status.name}"))
            if (!compact) TvText(label, modifier = motion.contentModifier, style = MaterialTheme.typography.labelLarge, color = Muted)
        }
    }
}
