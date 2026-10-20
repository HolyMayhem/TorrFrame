package tv.torrframe.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.torrframe.ui.sound.UiDialog as Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tv.torrframe.R
import tv.torrframe.core.storage.Settings
import tv.torrframe.features.player.*
import tv.torrframe.ui.components.TvAction
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.theme.Muted

@Composable internal fun PlaybackSettings(settings: Settings, vm: SettingsViewModel) {
    var players by remember { mutableStateOf<List<ExternalPlayer>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf(false) }
    val externalFocus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh, picker) {
        players = try { vm.externalPlayers.available() }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
    }
    TvText(stringResource(R.string.player_mode), color = Muted)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerMode.entries.forEach { mode ->
            TvAction(stringResource(when(mode) {
                PlayerMode.Internal -> R.string.player_internal
                PlayerMode.External -> R.string.player_external
                PlayerMode.Ask -> R.string.player_ask
            }), selected = settings.playerMode == mode,
                modifier = Modifier.fillMaxWidth().testTag("playback:mode:${mode.name}")) {
                if (mode == PlayerMode.External && players.none { it.component == settings.externalPlayer }) picker = true
                else vm.edit { it.copy(playerMode = mode) }
            }
        }
    }
    BuiltInPlaybackSettings(settings, vm)
    val preferred = players.firstOrNull { it.component == settings.externalPlayer }
    TvAction(stringResource(R.string.player_selected, preferred?.label ?: stringResource(
        if (settings.externalPlayer.isBlank()) R.string.player_not_selected else R.string.player_unavailable)),
        modifier = Modifier.fillMaxWidth().focusRequester(externalFocus).testTag("playback:select"), translate = false) { picker = true }
    TvText(stringResource(R.string.player_external_hint), color = Muted)
    if (picker) Dialog(onDismissRequest = { picker = false; restoreFocus = true }) {
        Box(Modifier.heightIn(max = 420.dp)) {
            PlayerChoices(players, includeInternal = false, selected = settings.externalPlayer,
                choose = { player ->
                    if (player != null) vm.edit { it.copy(playerMode = PlayerMode.External, externalPlayer = player.component) }
                    picker = false; restoreFocus = true
                }, dismiss = { picker = false; restoreFocus = true })
        }
    }
    LaunchedEffect(restoreFocus, picker) {
        if (restoreFocus && !picker) { withFrameNanos { }; externalFocus.requestFocus(); restoreFocus = false }
    }
}

/** Shared controls, backed by the existing SettingsStore; no player-specific appearance settings. */
@Composable internal fun BuiltInPlaybackSettings(settings: Settings, vm: SettingsViewModel) {
    val options = settings.builtInPlayer
    TvText(stringResource(R.string.player_builtin_options), color = Muted)
    PlayerEngineOptions(options) { edit -> vm.edit { it.copy(builtInPlayer = edit(it.builtInPlayer)) } }
    TvText(stringResource(R.string.player_decoder), color = Muted)
    AudioDecoder.entries.forEach { decoder ->
        TvAction(stringResource(decoder.title()), selected = options.decoder == decoder,
            modifier = Modifier.fillMaxWidth().testTag("playback:decoder:${decoder.name}")) {
            vm.edit { it.copy(builtInPlayer = it.builtInPlayer.copy(decoder = decoder)) }
        }
    }
    TvText(stringResource(R.string.player_decoder_hint), color = Muted)
}
