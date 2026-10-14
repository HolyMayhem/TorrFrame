package tv.torrserve.silicon.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import tv.torrserve.silicon.ui.theme.*

internal data class TvButtonMotion(
    val interactions: MutableInteractionSource,
    val colors: ButtonColors,
    val border: ButtonBorder,
    val modifier: Modifier,
    val contentModifier: Modifier,
    val activate: () -> Unit,
)

/** Shared visual feedback; TV Material still owns focus, clicks and press cancellation. */
@Composable internal fun rememberTvButtonMotion(
    enabled: Boolean = true,
    container: Color = Surface,
    content: Color = TextPrimary,
    outline: Color = Outline,
    shape: Shape = RoundedCornerShape(TvDimensions.buttonRadius),
): TvButtonMotion {
    val interactions = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val animations = LocalAnimationsEnabled.current
    var clickSerial by remember { mutableIntStateOf(0) }
    var clickPulse by remember { mutableStateOf(false) }
    LaunchedEffect(clickSerial) {
        if (clickSerial > 0 && animations) {
            clickPulse = true
            try { delay(130) } finally { clickPulse = false }
        } else clickPulse = false
    }
    val pressed by interactions.collectIsPressedAsState()
    val active = enabled && focused
    val targetContent = if (!enabled) Muted.copy(alpha = .55f) else if (active) OnAccent else content
    val duration = if (animations) 200 else 0
    val pressActive = enabled && animations && (pressed || clickPulse)
    val pressScale by animateFloatAsState(if (pressActive) .94f else 1f,
        tween(if (!animations) 0 else if (pressActive) 70 else 170), label = "buttonPress")
    val contentScale by animateFloatAsState(if (animations && active) 1.06f else 1f,
        tween(duration), label = "buttonFocusContent")
    val base = if (!enabled) Surface else if (active) Accent else container
    val background by animateColorAsState(
        if (pressActive) lerp(base, targetContent, .18f) else base,
        tween(duration), label = "buttonBackground")
    val foreground by animateColorAsState(targetContent, tween(duration), label = "buttonContent")
    val borderColor by animateColorAsState(if (focused) FocusBorderColor else outline,
        tween(duration), label = "buttonOutline")
    val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp,
        tween(duration), label = "buttonOutlineWidth")
    val border = Border(BorderStroke(borderWidth, borderColor), shape = shape)
    // Every Material interaction state uses the same interpolated values, avoiding color jumps.
    return TvButtonMotion(interactions,
        ButtonDefaults.colors(containerColor = background, contentColor = foreground,
            focusedContainerColor = background, focusedContentColor = foreground,
            pressedContainerColor = background, pressedContentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground),
        ButtonDefaults.border(border = border, focusedBorder = border, pressedBorder = border,
            disabledBorder = border, focusedDisabledBorder = border),
        Modifier.onFocusChanged { focused = it.isFocused }.graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        Modifier.graphicsLayer { scaleX = contentScale; scaleY = contentScale },
        { if (enabled && animations) clickSerial++ })
}

/** TV Material handles DPAD; this also supports clicks in the emulator without losing focus. */
@Composable internal fun TvButtonMotion.pointerClick(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, action: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val currentAction by rememberUpdatedState(action)
    val currentLongAction by rememberUpdatedState(onLongClick)
    val currentActivate by rememberUpdatedState(activate)
    return Modifier.focusRequester(focus).pointerInput(enabled, interactions, onLongClick != null) {
        detectTapGestures(
            onLongPress = if (onLongClick != null) ({ if (enabled) { currentActivate(); currentLongAction?.invoke() } }) else null,
            onPress = { position ->
                inputMode.requestInputMode(InputMode.Keyboard)
                focus.requestFocus()
                if (enabled) {
                    val press = PressInteraction.Press(position)
                    interactions.emit(press)
                    var released = false
                    try { released = tryAwaitRelease() }
                    finally { interactions.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)) }
                }
            },
            onTap = { if (enabled) { currentActivate(); currentAction() } })
    }
}
