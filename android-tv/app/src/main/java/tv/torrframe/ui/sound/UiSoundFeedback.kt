package tv.torrframe.ui.sound

import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tv.torrframe.TorrFrameApplication

@Composable internal fun rememberUiSounds(): UiSounds? {
    val app = LocalContext.current.applicationContext
    return remember(app) { (app as? TorrFrameApplication)?.graph?.uiSounds }
}

/** Replace platform clicks rather than playing a second click over our effects. */
@Composable internal fun UiSoundFeedback(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        onDispose { view.isSoundEffectsEnabled = previous }
    }
    content()
}

@Composable fun UiBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val sounds = rememberUiSounds()
    BackHandler(enabled) { sounds?.play(UiSound.Back); onBack() }
}

/** Dialogs use their own Compose view and Back dispatch, retaining the same quiet feedback. */
@Composable fun UiDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit) {
    val sounds = rememberUiSounds()
    Dialog(onDismissRequest = { sounds?.play(UiSound.Back); onDismissRequest() }, properties = properties) {
        // A long press can move focus into this window while OK is still held.
        // Only a new key-down in this dialog may arm an action; inherited repeats
        // and the release from the opening press must never reach its buttons.
        val openedAt = remember { SystemClock.uptimeMillis() }
        val presses = remember { mutableSetOf<Int>() }
        Box(Modifier.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode != NativeKeyEvent.KEYCODE_DPAD_CENTER && key.keyCode != NativeKeyEvent.KEYCODE_ENTER &&
                key.keyCode != NativeKeyEvent.KEYCODE_NUMPAD_ENTER) false
            else if (key.action == NativeKeyEvent.ACTION_DOWN && key.downTime < openedAt) true
            else when (key.action) {
                NativeKeyEvent.ACTION_DOWN -> {
                    if (key.repeatCount == 0) {
                        presses.add(key.keyCode)
                        false
                    } else key.keyCode !in presses
                }
                NativeKeyEvent.ACTION_UP -> !presses.remove(key.keyCode)
                else -> false
            }
        }, propagateMinConstraints = true) {
            UiSoundFeedback(content)
        }
    }
}
