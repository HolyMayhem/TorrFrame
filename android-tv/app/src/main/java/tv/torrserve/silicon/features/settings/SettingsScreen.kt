package tv.torrserve.silicon.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
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

@Composable fun SettingsScreen(state: SettingsUi, server: ServerState, vm: SettingsViewModel, entryFocus: FocusRequester? = null, updates: @Composable () -> Unit) {
    val s = state.draft
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionHeading("Settings", "Set up search, playback and your library.") }
            TvAction(if(state.busy) "Saving…" else "Save", !state.busy, modifier = if(entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier, primary = true, action = vm::save)
        }
        Message(state.message)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            item { SettingsGroup("Server") {
                Text(when(server) { is ServerState.Running -> "Running · ${server.version}"; ServerState.Starting -> "Starting…"; ServerState.Stopped -> "Stopped"; is ServerState.Error -> server.message }, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction("Start", !state.busy && server !is ServerState.Running, action = vm::start)
                    TvAction("Stop", !state.busy && server is ServerState.Running, action = vm::stop)
                    TvAction("Restart", !state.busy, action = vm::restart)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    EditField("Port", s.port.toString(), modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { value -> value.toIntOrNull()?.let { n -> vm.edit { it.copy(port = n) } } }
                    EditField("Cache (MB)", s.cacheMb.toString(), modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { value -> value.toIntOrNull()?.let { n -> vm.edit { it.copy(cacheMb = n) } } }
                }
            } }
            item { SettingsGroup("Search · Jackett") {
                EditField("Jackett URL", s.jackettUrl, keyboardType = KeyboardType.Uri) { value -> vm.edit { it.copy(jackettUrl = value) } }
                EditField("Jackett API Key", s.jackettKey, secret = true) { value -> vm.edit { it.copy(jackettKey = value) } }
                if(state.connection.isNotBlank()) Text(state.connection, color = Muted)
                TvAction("Test Connection", !state.busy, action = vm::test)
            } }
            item { SettingsGroup("Metadata") {
                Text("Lookup priority · first matching source wins", color = Muted)
                MetadataPriorityControls(s.metadataOrder, vm::moveProvider)
                TvAction("AniList for anime: ${if(s.aniListEnabled) "On" else "Off"}") { vm.edit { it.copy(aniListEnabled = !it.aniListEnabled) } }
                Text("When enabled, AniList is checked before the sources above.", color = Muted)
                Text("Metadata language", color = Muted)
                ChoiceRow(listOf("en-US", "ru-RU"), s.language) { value -> vm.edit { it.copy(language = value) } }
                TvAction("Clear Metadata Cache", !state.busy, action = vm::clearCache)
            } }
            item { SettingsGroup("Playback") {
                TvAction("Resume Playback: ${if(s.resume) "On" else "Off"}") { vm.edit { it.copy(resume = !it.resume) } }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    EditField("Audio language (en / ru)", s.audioLanguage, modifier = Modifier.weight(1f)) { value -> vm.edit { it.copy(audioLanguage = value.trim()) } }
                    EditField("Subtitle language (en / ru)", s.subtitleLanguage, modifier = Modifier.weight(1f)) { value -> vm.edit { it.copy(subtitleLanguage = value.trim()) } }
                }
            } }
            item { SettingsGroup("Appearance") {
                ChoiceRow(listOf("en", "ru"), s.uiLanguage) { value -> vm.edit { it.copy(uiLanguage = value) } }
                Text("Theme: Dark", color = Muted)
            } }
            item { SettingsGroup("Updates", updates) }
            item { SettingsGroup("About") {
                Text("TorrServe Silicon ${BuildConfig.VERSION_NAME}\nNative Android TV · Dark theme\nTorrServer by YouROK · Compose for TV · Media3\nTorrServer: GPL-3.0 · Kotlin, OkHttp, Media3, Coil: Apache-2.0", color = Muted)
                TvAction("Open Source Licenses") { licenses = true }
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
                TvAction("Move up", enabled = index > 0,
                    modifier = Modifier.testTag("priority:$provider:up").focusRequester(focuses.getValue("$provider:up"))) {
                    pendingFocus = "$provider:${if(index == 1) "down" else "up"}"
                    move(provider, -1)
                }
                TvAction("Move down", enabled = index < order.lastIndex,
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

@Composable private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().focusGroup().background(Surface, RoundedCornerShape(20.dp))
        .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(LocalTvLayout.current.panelPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}
