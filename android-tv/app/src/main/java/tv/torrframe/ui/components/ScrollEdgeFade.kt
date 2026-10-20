package tv.torrframe.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.theme.Background
import tv.torrframe.ui.theme.LocalAnimationsEnabled

/** Blend clipped scroll content into its background without an offscreen layer or input overlay. */
@Composable internal fun Modifier.scrollEdgeFade(
    state: ScrollableState,
    vertical: Boolean = false,
    length: Dp = 24.dp,
    background: Color = Background,
): Modifier {
    val duration = if (LocalAnimationsEnabled.current) 160 else 0
    val backward by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(duration), label = "fadeBackward")
    val forward by animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(duration), label = "fadeForward")
    val rtl = !vertical && LocalLayoutDirection.current == LayoutDirection.Rtl
    return drawWithCache {
        val extent = if (vertical) size.height else size.width
        val edge = length.toPx().coerceIn(0f, extent / 2f)
        val clear = background.copy(alpha = 0f)
        val startBrush = if (vertical) Brush.verticalGradient(listOf(background, clear), 0f, edge)
            else Brush.horizontalGradient(listOf(background, clear), 0f, edge)
        val endBrush = if (vertical) Brush.verticalGradient(listOf(clear, background), extent - edge, extent)
            else Brush.horizontalGradient(listOf(clear, background), extent - edge, extent)
        val strip = if (vertical) Size(size.width, edge) else Size(edge, size.height)
        val end = if (vertical) Offset(0f, extent - edge) else Offset(extent - edge, 0f)
        onDrawWithContent {
            drawContent()
            if (edge > 0f) {
                drawRect(startBrush, size = strip, alpha = if (rtl) forward else backward)
                drawRect(endBrush, topLeft = end, size = strip, alpha = if (rtl) backward else forward)
            }
        }
    }
}
