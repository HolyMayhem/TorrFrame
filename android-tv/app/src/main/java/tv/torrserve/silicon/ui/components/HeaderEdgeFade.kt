package tv.torrserve.silicon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import tv.torrserve.silicon.ui.theme.Background

/** Blend artwork into the fixed header without an input overlay or offscreen buffer. */
@Composable internal fun Modifier.headerEdgeFade(enabled: Boolean = true): Modifier {
    val background = Background
    if (!enabled) return this
    return drawWithCache {
        val edge = 28.dp.toPx().coerceAtMost(size.height)
        val brush = Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)), 0f, edge)
        onDrawWithContent {
            drawContent()
            if (edge > 0f) drawRect(brush, size = Size(size.width, edge))
        }
    }
}
