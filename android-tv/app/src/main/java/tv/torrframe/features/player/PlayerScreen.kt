package tv.torrframe.features.player

import tv.torrframe.ui.sound.UiBackHandler as BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import tv.torrframe.R
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*

@Composable internal fun PlayerScreen(state: PlayerUiState, preferences: PlayerPreferences, controller: PlayerController,
    change: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
    close: () -> Unit, file: () -> Unit, url: () -> Unit, video: @Composable (controlsVisible: Boolean) -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var activity by remember { mutableLongStateOf(0) }
    var returning by remember { mutableStateOf(false) }
    var lastPanel by remember { mutableStateOf(PlayerPanel.Options) }
    val playFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val hiddenFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }; val subtitleFocus = remember { FocusRequester() }; val optionsFocus = remember { FocusRequester() }
    val duration = if (LocalAnimationsEnabled.current) 160 else 0
    fun showMenu(value: PlayerPanel) { lastPanel = value; panel = value; activity++ }
    fun backMenu() {
        panel = when (panel) {
            PlayerPanel.Speed, PlayerPanel.Aspect, PlayerPanel.Decoder -> PlayerPanel.Options
            PlayerPanel.SubtitleStyle -> PlayerPanel.Subtitles
            else -> null
        }
        if (panel == null) returning = true
        activity++
    }
    BackHandler { if (panel != null) backMenu() else close() }
    LaunchedEffect(state.error) { if (state.error) visible = true }
    LaunchedEffect(visible, state.connected, state.error) {
        withFrameNanos { }
        if (visible && state.error) retryFocus.requestFocus() else if (visible && state.connected) playFocus.requestFocus() else if (!visible) hiddenFocus.requestFocus()
    }
    LaunchedEffect(returning, panel) {
        if (returning && panel == null) {
            withFrameNanos { }
            when (lastPanel) { PlayerPanel.Audio -> audioFocus; PlayerPanel.Subtitles, PlayerPanel.SubtitleStyle -> subtitleFocus; else -> optionsFocus }.requestFocus()
            returning = false
        }
    }
    LaunchedEffect(activity, state.playing, visible, panel, state.error) {
        if (visible && state.playing && panel == null && !state.error) { delay(5000); visible = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player:screen").onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            activity++
            when (event.key) {
                Key.MediaPlayPause -> { controller.toggle(); true }
                Key.MediaPlay -> { if (!state.playWhenReady) controller.toggle(); true }
                Key.MediaPause -> { if (state.playWhenReady) controller.toggle(); true }
                Key.MediaFastForward -> { controller.seekBy(10_000); true }
                Key.MediaRewind -> { controller.seekBy(-10_000); true }
                else -> if (!visible) when (event.key) {
                    Key.DirectionLeft -> { controller.seekBy(-10_000); true }
                    Key.DirectionRight -> { controller.seekBy(10_000); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> { visible = true; true }
                    else -> false
                } else false
            }
        } else false
    }) {
        video(visible)
        if (!visible) Box(Modifier.fillMaxSize().focusRequester(hiddenFocus).focusable().testTag("player:hidden")
            .pointerInput(Unit) { detectTapGestures { visible = true; activity++ } })
        if (state.buffering && !state.error) TvText(stringResource(R.string.player_buffering),
            modifier = Modifier.align(Alignment.Center).background(Surface, RoundedCornerShape(14.dp)).padding(16.dp).testTag("player:buffering"))
        if (state.error) Column(Modifier.align(Alignment.Center).widthIn(max = 500.dp).background(Surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvText(stringResource(R.string.player_failed))
            TvAction(stringResource(R.string.player_retry), modifier = Modifier.focusRequester(retryFocus).testTag("player:retry"), action = controller::retry)
        }
        AnimatedVisibility(visible, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
            Box(Modifier.fillMaxSize().testTag("player:controls")) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Transparent))).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvBackAction(Modifier.testTag("player:back"), close)
                    TvText(state.title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Surface.copy(alpha = .97f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TvText(playbackTime(state.position), translate = false)
                        TvText(playbackTime(state.duration), color = Muted, translate = false)
                    }
                    PlayerSeekBar(state, controller)
                    Row(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if (panel == null && event.key == Key.DirectionDown) {
                            if (event.type == KeyEventType.KeyDown) visible = false
                            true
                        } else false
                    }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvAction(stringResource(R.string.player_seek_back), enabled = state.seekable, modifier = Modifier.testTag("player:rewind")) { controller.seekBy(-10_000) }
                        TvAction(stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play), enabled = state.connected,
                            primary = true, modifier = Modifier.width(140.dp).focusRequester(playFocus).testTag("player:play"), action = controller::toggle)
                        TvAction(stringResource(R.string.player_seek_forward), enabled = state.seekable, modifier = Modifier.testTag("player:forward")) { controller.seekBy(10_000) }
                        Spacer(Modifier.weight(1f))
                        TvAction(stringResource(R.string.player_audio), modifier = Modifier.focusRequester(audioFocus).testTag("player:audio")) { showMenu(PlayerPanel.Audio) }
                        TvAction(stringResource(R.string.player_subtitles), modifier = Modifier.focusRequester(subtitleFocus).testTag("player:subtitles")) { showMenu(PlayerPanel.Subtitles) }
                        SettingsShortcut(Modifier.focusRequester(optionsFocus).testTag("player:options")) { showMenu(PlayerPanel.Options) }
                    }
                }
            }
        }
        panel?.let { value -> PlayerMenu(value, state, preferences, change, controller, ::showMenu, ::backMenu, file, url) }
    }
}

@Composable private fun PlayerSeekBar(state: PlayerUiState, controller: PlayerController) {
    var focused by remember { mutableStateOf(false) }
    val accent = Accent; val track = Outline; val buffered = Muted.copy(alpha = .4f)
    val contrast = TextPrimary; val outlineWhite = Accent.luminance() > .9f && Surface.luminance() > .5f
    val label = stringResource(R.string.player_seek)
    Canvas(Modifier.fillMaxWidth().height(24.dp).testTag("player:seek")
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(state.position.toFloat().coerceAtMost(state.duration.toFloat()), 0f..state.duration.coerceAtLeast(1).toFloat())
            setProgress { controller.seekTo(it.toLong()); true }
        }
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> { if (event.type == KeyEventType.KeyDown) controller.seekBy(if (event.key == Key.DirectionLeft) -10_000 else 10_000); true }
                Key.DirectionCenter, Key.Enter -> { if (event.type == KeyEventType.KeyUp) controller.toggle(); true }
                else -> false
            }
        }.focusable(enabled = state.seekable).pointerInput(state.duration) {
            detectTapGestures { point -> if (size.width > 0) controller.seekTo((point.x / size.width * state.duration).toLong()) }
        }) {
        val start = 8.dp.toPx(); val end = size.width - start; val y = size.height / 2
        val total = state.duration.coerceAtLeast(1).toFloat()
        fun x(time: Long) = start + (end - start) * (time / total).coerceIn(0f, 1f)
        val width = if (focused) 6.dp.toPx() else 4.dp.toPx()
        drawLine(track, Offset(start, y), Offset(end, y), width, StrokeCap.Round)
        drawLine(buffered, Offset(start, y), Offset(x(state.buffered), y), width, StrokeCap.Round)
        if (outlineWhite) {
            drawLine(contrast, Offset(start, y), Offset(x(state.position), y), width + 2.dp.toPx(), StrokeCap.Round)
            drawCircle(contrast, if (focused) 8.dp.toPx() else 6.dp.toPx(), Offset(x(state.position), y))
        }
        drawLine(accent, Offset(start, y), Offset(x(state.position), y), width, StrokeCap.Round)
        drawCircle(accent, if (focused) 7.dp.toPx() else 5.dp.toPx(), Offset(x(state.position), y))
    }
}
