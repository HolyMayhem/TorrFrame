package tv.torrframe.ui.sound

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
        UiSoundFeedback(content)
    }
}
