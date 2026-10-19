package tv.torrserve.silicon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.theme.*

/** Low-noise navigation/actions. Selection and actual remote focus have distinct treatments. */
@Composable fun TvQuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, underline: Boolean = false, filled: Boolean = false,
    translate: Boolean = true, centered: Boolean = false, style: TextStyle = MaterialTheme.typography.labelLarge,
    action: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val motion = rememberTvButtonMotion(enabled, container = if (filled) Surface else Color.Transparent,
        content = if (selected) AccentText else Muted, outline = Color.Transparent, shape = shape,
        focusedContainer = SurfaceSecondary, focusedContent = TextPrimary, focusedOutline = TextPrimary.copy(alpha = .8f))
    Button(onClick = { motion.activate(); action() }, enabled = enabled,
        modifier = modifier.heightIn(min = TvDimensions.actionHeight).then(motion.modifier)
            .then(motion.pointerClick(enabled, action = action)),
        shape = ButtonDefaults.shape(shape), colors = motion.colors, border = motion.border,
        interactionSource = motion.interactions, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)) {
        Column(modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TvText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, translate = translate)
            if (underline) Box(Modifier.width(30.dp).height(2.dp)
                .background(if (selected) AccentText else Color.Transparent, RoundedCornerShape(1.dp)))
        }
    }
}
