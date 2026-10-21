package tv.torrframe.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.sound.UiDialog as Dialog
import androidx.tv.material3.MaterialTheme
import tv.torrframe.R
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*

enum class PlayerPanel { Audio, Subtitles, Options, SubtitleStyle, Speed, Aspect, Decoder }
internal fun PlayerPanel.title() = when (this) {
    PlayerPanel.Audio -> R.string.player_audio
    PlayerPanel.Subtitles -> R.string.player_subtitles
    PlayerPanel.Options -> R.string.player_options
    PlayerPanel.SubtitleStyle -> R.string.player_subtitle_style
    PlayerPanel.Speed -> R.string.player_speed
    PlayerPanel.Aspect -> R.string.player_aspect
    PlayerPanel.Decoder -> R.string.player_decoder
}
internal fun VideoAspect.title() = when (this) {
    VideoAspect.Fit -> R.string.player_aspect_fit
    VideoAspect.Zoom -> R.string.player_aspect_zoom
    VideoAspect.Fill -> R.string.player_aspect_fill
}
internal fun AudioDecoder.title() = if (this == AudioDecoder.Device) R.string.player_decoder_device else R.string.player_decoder_ffmpeg

@Composable internal fun PlayerMenu(panel: PlayerPanel, state: PlayerUiState, preferences: PlayerPreferences,
    change: ((PlayerPreferences) -> PlayerPreferences) -> Unit, controller: PlayerController,
    open: (PlayerPanel) -> Unit, back: () -> Unit, file: () -> Unit, url: () -> Unit) {
    val first = remember(panel) { FocusRequester() }
    Dialog(onDismissRequest = back) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().fillMaxHeight(.92f)
            .background(Surface, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp))
            .padding(20.dp).testTag("player:menu:${panel.name}"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvBackAction(action = back)
                TvText(stringResource(panel.title()), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (panel) {
                    PlayerPanel.Audio, PlayerPanel.Subtitles -> {
                        val audio = panel == PlayerPanel.Audio
                        val tracks = state.tracks.filter { it.type == if (audio) "audio" else "subtitle" }
                        if (!audio) TvAction(stringResource(R.string.player_subtitles_off), selected = tracks.none { it.selected },
                            modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("player:subtitle:off")) { controller.track(null) }
                        if (tracks.isEmpty()) {
                            TvText(stringResource(R.string.player_no_tracks), color = Muted)
                            if (audio) TvAction(stringResource(R.string.player_close), modifier = Modifier.focusRequester(first), action = back)
                        }
                        tracks.forEachIndexed { index, track ->
                            TvAction(track.label, selected = track.selected, translate = false,
                                modifier = Modifier.fillMaxWidth().then(if (audio && index == 0) Modifier.focusRequester(first) else Modifier)
                                    .testTag("player:track:${track.id}")) { controller.track(track.id) }
                        }
                        if (!audio) {
                            TvAction(stringResource(R.string.player_subtitle_file), modifier = Modifier.fillMaxWidth().testTag("player:subtitle:file"), action = file)
                            TvAction(stringResource(R.string.player_subtitle_url), modifier = Modifier.fillMaxWidth().testTag("player:subtitle:url"), action = url)
                            TvAction(stringResource(R.string.player_subtitle_style), modifier = Modifier.fillMaxWidth().testTag("player:subtitle:style")) { open(PlayerPanel.SubtitleStyle) }
                        }
                    }
                    PlayerPanel.Options -> {
                        TvAction("${stringResource(R.string.player_speed)} · ${preferences.speed}×", modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("player:speed")) { open(PlayerPanel.Speed) }
                        TvAction("${stringResource(R.string.player_aspect)} · ${stringResource(preferences.aspect.title())}", modifier = Modifier.fillMaxWidth().testTag("player:aspect")) { open(PlayerPanel.Aspect) }
                        TvAction(stringResource(R.string.player_decoder), modifier = Modifier.fillMaxWidth().testTag("player:decoder")) { open(PlayerPanel.Decoder) }
                        PlayerEngineOptions(preferences, change)
                        if (state.videoInfo.isNotBlank()) {
                            TvText(stringResource(R.string.player_video_info), color = Muted)
                            TvText(state.videoInfo, translate = false)
                        }
                    }
                    PlayerPanel.Speed -> PlayerPreferences.SPEEDS.forEachIndexed { index, speed ->
                        TvAction("${speed}×", selected = preferences.speed == speed, translate = false,
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("player:speed:$speed")) { change { it.copy(speed = speed) } }
                    }
                    PlayerPanel.Aspect -> VideoAspect.entries.forEachIndexed { index, aspect ->
                        TvAction(stringResource(aspect.title()), selected = preferences.aspect == aspect,
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("player:aspect:${aspect.name}")) { change { it.copy(aspect = aspect) } }
                    }
                    PlayerPanel.Decoder -> {
                        AudioDecoder.entries.forEachIndexed { index, decoder ->
                            TvAction(stringResource(decoder.title()), selected = preferences.decoder == decoder,
                                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("player:decoder:${decoder.name}")) { change { it.copy(decoder = decoder) } }
                        }
                        TvText(stringResource(R.string.player_decoder_hint), color = Muted)
                    }
                    PlayerPanel.SubtitleStyle -> PlayerSubtitleOptions(preferences, change, Modifier.focusRequester(first))
                }
            }
        }
        LaunchedEffect(panel) { withFrameNanos { }; first.requestFocus() }
    }
}

@Composable fun PlayerEngineOptions(preferences: PlayerPreferences, change: ((PlayerPreferences) -> PlayerPreferences) -> Unit) {
    TvToggle(stringResource(R.string.player_frame_rate), preferences.matchFrameRate, Modifier.testTag("player:frame-rate")) { value -> change { it.copy(matchFrameRate = value) } }
    TvText(stringResource(R.string.player_frame_rate_hint), color = Muted, style = MaterialTheme.typography.bodySmall)
    TvToggle(stringResource(R.string.player_skip_silence), preferences.skipSilence, Modifier.testTag("player:skip-silence")) { value -> change { it.copy(skipSilence = value) } }
}
@Composable internal fun PlayerSubtitleOptions(preferences: PlayerPreferences, change: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
    modifier: Modifier = Modifier) {
    TvText(stringResource(R.string.player_subtitle_size), color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvAction("−", enabled = preferences.subtitleSize > PlayerPreferences.SUBTITLE_SIZES.first(), modifier = (if (preferences.subtitleSize > 75) modifier else Modifier).testTag("player:subtitle:smaller")) {
            change { it.copy(subtitleSize = (it.subtitleSize - 25).coerceAtLeast(75)) }
        }
        TvText("${preferences.subtitleSize}%", translate = false)
        TvAction("+", enabled = preferences.subtitleSize < PlayerPreferences.SUBTITLE_SIZES.last(), modifier = (if (preferences.subtitleSize == 75) modifier else Modifier).testTag("player:subtitle:larger")) {
            change { it.copy(subtitleSize = (it.subtitleSize + 25).coerceAtMost(175)) }
        }
    }
    TvText(stringResource(R.string.player_subtitle_position), color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerPreferences.SUBTITLE_MARGINS.forEach { margin ->
            TvAction("$margin%", selected = preferences.subtitleMargin == margin, translate = false,
                modifier = Modifier.testTag("player:subtitle:margin:$margin")) { change { it.copy(subtitleMargin = margin) } }
        }
    }
    TvText(stringResource(R.string.player_subtitle_color), color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SubtitleColor.entries.forEach { color -> TvAction(stringResource(if (color == SubtitleColor.White) R.string.player_subtitle_white else R.string.player_subtitle_yellow), selected = color == preferences.subtitleColor) { change { it.copy(subtitleColor = color) } } }
    }
    TvToggle(stringResource(R.string.player_subtitle_background), preferences.subtitleBackground) { value -> change { it.copy(subtitleBackground = value) } }
    TvToggle(stringResource(R.string.player_subtitle_bold), preferences.subtitleBold) { value -> change { it.copy(subtitleBold = value) } }
    TvToggle(stringResource(R.string.player_subtitle_embedded), preferences.embeddedSubtitleStyle) { value -> change { it.copy(embeddedSubtitleStyle = value) } }
}
