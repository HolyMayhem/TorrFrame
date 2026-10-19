package tv.torrserve.silicon.features.startup

import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.torrserve.silicon.core.localization.uiString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import tv.torrserve.silicon.BuildConfig
import tv.torrserve.silicon.R

/** Content is always composed and initialized underneath this independent black cover. */
@Composable fun StartupSplash(state: StartupSplashState, ready: Boolean, close: () -> Unit,
    revealed: () -> Unit, content: @Composable (covered: Boolean) -> Unit) {
    val frame by state.state.collectAsState()
    val visible = frame.phase != StartupPhase.Finished
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val onRevealed by rememberUpdatedState(revealed)
    var loadingHintVisible by remember(state, frame.phase) { mutableStateOf(false) }
    LaunchedEffect(state, frame.phase) {
        if (frame.phase == StartupPhase.Frozen) {
            awaitDeadline(state::loadingHintRemainingMs)
            loadingHintVisible = true
        }
    }
    LaunchedEffect(Unit) { state.begin() }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.stopped(activity?.isChangingConfigurations == true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, ready) {
        state.setUiReady(false)
        if (ready) {
            awaitDeadline(state::readyDelayRemainingMs)
            state.setUiReady(true)
        }
    }
    LaunchedEffect(frame.phase, frame.uiReady) {
        if (BuildConfig.DEBUG) Log.i("TorrFrameStartup", "phase=${frame.phase} ready=${frame.uiReady} prepared=$ready at_ms=${SystemClock.elapsedRealtime()}")
        when (frame.phase) {
            StartupPhase.Video -> { awaitDeadline(state::videoRemainingMs); state.videoEnded(failed = true) }
            StartupPhase.Black -> state.fadeWhenReady(frame.uiReady)
            StartupPhase.Finished -> if (frame.uiReady) { withFrameNanos { }; onRevealed() }
            else -> Unit
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        visible && event.key != Key.Back && event.key != Key.Escape
    }) {
        Box(Modifier.fillMaxSize().then(if (visible) Modifier.clearAndSetSemantics { } else Modifier)) { content(visible) }
        if (visible) {
            var alpha by remember { mutableFloatStateOf(if (frame.phase == StartupPhase.Fading)
                1f - FastOutSlowInEasing.transform(state.fadeProgress()) else 1f) }
            LaunchedEffect(frame.phase) {
                if (frame.phase != StartupPhase.Fading) alpha = 1f
                else while (true) {
                    withFrameNanos { }
                    if (state.state.value.phase != StartupPhase.Fading) break
                    val progress = state.fadeProgress()
                    alpha = 1f - FastOutSlowInEasing.transform(progress)
                    if (progress >= 1f) { state.finish(); break }
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
                .background(Color.Black).testTag("startup:cover").pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
                }) {
                if (frame.phase == StartupPhase.Video || frame.phase == StartupPhase.Frozen) StartupVideo(state, frame.phase)
                // The phase guard removes this in the very same composition that resumes the clip.
                if (frame.phase == StartupPhase.Frozen && loadingHintVisible) {
                    BasicText(LocalContext.current.uiString(R.string.startup_preparing),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 32.dp)
                            .testTag("startup:preparing"),
                        style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 14.sp, textAlign = TextAlign.Center))
                }
            }
        }
    }
    // Registered after the content's BackHandlers: Back closes the launcher instead of acting on hidden UI.
    BackHandler(visible) { state.finish(); close() }
}

/** Recheck the monotonic deadline after wake-up; timer rounding must never strand the black cover. */
private suspend fun awaitDeadline(remaining: () -> Long) {
    while (true) {
        val millis = remaining()
        if (millis <= 0) return
        delay(millis)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@android.annotation.SuppressLint("InflateParams") // AndroidView owns measurement; there is no XML parent to attach to.
@Composable private fun StartupVideo(state: StartupSplashState, phase: StartupPhase) {
    val owner = LocalLifecycleOwner.current
    val player = remember(state) { StartupVideoPlayer(state) }
    SideEffect { if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play() }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> player.play()
            Lifecycle.Event.ON_STOP -> player.release()
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release(); state.videoEnded() }
    }
    // The TextureView stays mounted during the hold. No image decoder, profile conversion,
    // overlay or surface replacement can alter the frozen video's brightness.
    Box(Modifier.fillMaxSize().then(if (phase == StartupPhase.Frozen) Modifier.testTag("startup:freeze") else Modifier)) {
        AndroidView(modifier = Modifier.fillMaxSize().testTag("startup:video"), factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.startup_video, null).apply {
                player.prepare(context, this, owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
        }, onRelease = { player.release() })
    }
}
