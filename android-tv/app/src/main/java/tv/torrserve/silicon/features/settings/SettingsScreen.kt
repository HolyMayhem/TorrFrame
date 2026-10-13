package tv.torrserve.silicon.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.server.ConnectionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import tv.torrserve.silicon.ui.components.TvText as Text
import tv.torrserve.silicon.core.server.ServerState
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*
import tv.torrserve.silicon.BuildConfig
import tv.torrserve.silicon.core.storage.MetadataPriority
import tv.torrserve.silicon.core.storage.UiAccent

@Composable fun SettingsScreen(state: SettingsUi, server: ServerState, vm: SettingsViewModel, torrState: TorrServerSettingsUi, connection: ConnectionState, torrVm: TorrServerSettingsViewModel, entryFocus: FocusRequester? = null, phoneRemote: @Composable () -> Unit = {}, updates: @Composable () -> Unit) {
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val engineFocus = remember { FocusRequester() }
    var restoreEngineFocus by remember { mutableStateOf(false) }
    if (engineOpen) {
        EngineSettingsScreen(torrState, torrVm, entryFocus) { engineOpen = false; restoreEngineFocus = true }
        return
    }
    LaunchedEffect(restoreEngineFocus) {
        if (restoreEngineFocus) { withFrameNanos { }; engineFocus.requestFocus(); restoreEngineFocus = false }
    }
    val s = state.draft
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionHeading(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) }
        }
        Message(state.message)
        ServerOperationStatus(torrState)
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            item("phone-remote") { SettingsGroup(stringResource(R.string.phone_title)) {
                phoneRemote()
            } }
            item("torrserver") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TorrServerConnectionPanel(torrState, connection, server, torrVm, engineFocus) { engineOpen = true }
            } }
            item { SettingsGroup(stringResource(R.string.settings_jackett)) {
                EditField(stringResource(R.string.settings_jackett_url), s.jackettUrl, keyboardType = KeyboardType.Uri, russian = true) { value -> vm.edit { it.copy(jackettUrl = value) } }
                EditField(stringResource(R.string.settings_jackett_key), s.jackettKey, secret = true, russian = true) { value -> vm.edit { it.copy(jackettKey = value) } }
                Text(stringResource(state.connection), color = Muted)
                TvAction(stringResource(R.string.settings_test_connection), action = vm::test)
            } }
            item { SettingsGroup(stringResource(R.string.settings_metadata)) {
                Text(stringResource(R.string.settings_metadata_priority), color = Muted)
                MetadataPriorityControls(s.metadataOrder, vm::moveProvider)
                TvToggle(stringResource(R.string.settings_anilist), s.aniListEnabled, Modifier.testTag("metadata:anilist")) { value -> vm.edit { it.copy(aniListEnabled = value) } }
                Text(stringResource(R.string.settings_anilist_hint), color = Muted)
                Text(stringResource(R.string.settings_metadata_language), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    ChoiceRow(listOf("en-US", "ru-RU"), s.language) { value -> vm.edit { it.copy(language = value) } }
                    TvAction(stringResource(R.string.settings_clear_metadata), action = vm::clearCache)
                }
            } }
            item { SettingsGroup(stringResource(R.string.settings_playback)) {
                TvToggle(stringResource(R.string.settings_resume), s.resume) { value -> vm.edit { it.copy(resume = value) } }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    EditField(stringResource(R.string.settings_audio), s.audioLanguage, modifier = Modifier.weight(1f), russian = true) { value -> vm.edit { it.copy(audioLanguage = value.trim()) } }
                    EditField(stringResource(R.string.settings_subtitles), s.subtitleLanguage, modifier = Modifier.weight(1f), russian = true) { value -> vm.edit { it.copy(subtitleLanguage = value.trim()) } }
                }
            } }
            item { SettingsGroup(stringResource(R.string.settings_updates), updates) }
            item("appearance") { SettingsGroup(stringResource(R.string.settings_appearance)) {
                UiScaleControls(s.uiScalePercent, vm::scale, vm::resetScale)
                Text(stringResource(R.string.ui_language_title), color = Muted)
                ChoiceRow(listOf("en", "ru"), s.uiLanguage, vm::language)
                Text(stringResource(R.string.ui_theme_title), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction(stringResource(R.string.ui_theme_dark), selected = s.uiTheme == "dark", modifier = Modifier.testTag("appearance:dark")) { vm.theme("dark") }
                    TvAction(stringResource(R.string.ui_theme_light), selected = s.uiTheme == "light", modifier = Modifier.testTag("appearance:light")) { vm.theme("light") }
                }
                TvToggle(stringResource(R.string.ui_navigation_hints), s.showNavigationHints, Modifier.testTag("appearance:hints"), vm::hints)
                Text(stringResource(R.string.ui_accent_title), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UiAccent.colors.keys.forEach { key ->
                        val label = stringResource(when (key) {
                            "white" -> R.string.ui_accent_white
                            "orange" -> R.string.ui_accent_orange
                            "pink" -> R.string.ui_accent_pink
                            "blue" -> R.string.ui_accent_blue
                            else -> R.string.ui_accent_green
                        })
                        TvAction(label, selected = s.uiAccent == key, modifier = Modifier.testTag("appearance:accent:$key")) { vm.accent(key) }
                    }
                }
            } }
            item("about") { SettingsGroup(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), color = Muted)
                TvAction(stringResource(R.string.settings_licenses)) { licenses = true }
            } }
        }
    }
    if(licenses) LicensesDialog { licenses = false }
}

@Composable internal fun MetadataPriorityControls(values: List<String>, move: (String, Int) -> Unit) {
    val order = MetadataPriority.normalized(values)
    val focuses = remember { MetadataPriority.defaultOrder.flatMap { p ->
        listOf("$p:up" to FocusRequester(), "$p:down" to FocusRequester())
    }.toMap() }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { index, provider -> key(provider) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${index + 1}. ${MetadataPriority.label(provider)}", modifier = Modifier.weight(1f).testTag("priority:$provider:label"))
                TvAction(stringResource(R.string.settings_move_up), enabled = index > 0,
                    modifier = Modifier.testTag("priority:$provider:up").focusRequester(focuses.getValue("$provider:up"))) {
                    pendingFocus = "$provider:${if(index == 1) "down" else "up"}"
                    move(provider, -1)
                }
                TvAction(stringResource(R.string.settings_move_down), enabled = index < order.lastIndex,
                    modifier = Modifier.testTag("priority:$provider:down").focusRequester(focuses.getValue("$provider:down"))) {
                    pendingFocus = "$provider:${if(index == order.lastIndex - 1) "up" else "down"}"
                    move(provider, 1)
                }
            }
        } }
    }
    LaunchedEffect(order) {
        pendingFocus?.let { focuses.getValue(it).requestFocus() }
        pendingFocus = null
    }
}

@Composable internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().focusGroup().background(Surface, RoundedCornerShape(20.dp))
        .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(LocalTvLayout.current.panelPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}
