package tv.torrframe.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A three-second hold is separate from the usual short OK; repeats never restart the timer. */
@Composable internal fun Modifier.holdClick(motion: TvButtonMotion, enabled: Boolean, hold: () -> Unit, action: () -> Unit): Modifier {
    val activate = motion.activate
    val interactions = motion.interactions
    val focus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentHold by rememberUpdatedState(hold)
    val currentAction by rememberUpdatedState(action)
    val currentActivate by rememberUpdatedState(activate)
    var keyHeld by remember { mutableStateOf(false) }
    var beganAt by remember { mutableLongStateOf(0L) }
    var fired by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf<Job?>(null) }
    var keyPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    fun cancel(released: Boolean = false) {
        timer?.cancel(); timer = null
        keyPress?.let { interactions.tryEmit(if (released) PressInteraction.Release(it) else PressInteraction.Cancel(it)) }
        keyPress = null; keyHeld = false; fired = false
    }
    DisposableEffect(lifecycle, enabled) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    return this.focusRequester(focus).onFocusChanged {
        focused = it.isFocused
        if (!focused) cancel()
    }.onPreviewKeyEvent { event ->
        if (event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) false
        else {
            if (enabled && event.type == KeyEventType.KeyDown && !keyHeld) {
                keyHeld = true; fired = false
                keyPress = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero).also { interactions.tryEmit(it) }
                beganAt = event.nativeKeyEvent.downTime
                timer = scope.launch {
                    delay((3_000 - (android.os.SystemClock.uptimeMillis() - beganAt)).coerceAtLeast(0))
                    if (keyHeld && focused && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        fired = true; currentActivate(); currentHold()
                    }
                }
            } else if (event.type == KeyEventType.KeyUp) {
                val valid = enabled && keyHeld && !fired && !event.nativeKeyEvent.isCanceled
                val longClick = valid && android.os.SystemClock.uptimeMillis() - beganAt >= 3_000
                cancel(released = !event.nativeKeyEvent.isCanceled)
                if (longClick) { currentActivate(); currentHold() }
                else if (valid) { currentActivate(); currentAction() }
            }
            true
        }
    }.pointerInput(enabled, interactions) {
        detectTapGestures(onPress = { position ->
            if (enabled) {
                inputMode.requestInputMode(InputMode.Keyboard); focus.requestFocus()
                val press = PressInteraction.Press(position)
                interactions.emit(press)
                var released = false
                coroutineScope {
                    var longClick = false
                    val startedAt = android.os.SystemClock.uptimeMillis()
                    val pending = launch {
                        delay(3_000)
                        if (focused && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            longClick = true; currentActivate(); currentHold()
                        }
                    }
                    try {
                        released = tryAwaitRelease()
                        pending.cancel()
                        if (released && !longClick && focused && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            currentActivate()
                            if (android.os.SystemClock.uptimeMillis() - startedAt >= 3_000) currentHold() else currentAction()
                        }
                    } finally {
                        pending.cancel()
                        interactions.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    }
                }
            }
        })
    }
}
