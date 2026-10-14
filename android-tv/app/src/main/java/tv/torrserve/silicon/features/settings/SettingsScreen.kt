package tv.torrserve.silicon.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.InputMode
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

enum class SettingsCategory(val title: Int) {
    PhoneRemote(R.string.phone_title), TorrServer(R.string.settings_torrserver), Search(R.string.settings_search),
    Metadata(R.string.settings_metadata), Playback(R.string.settings_playback), Updates(R.string.settings_updates),
    Appearance(R.string.settings_appearance), About(R.string.settings_about)
}

@Composable fun SettingsScreen(state: SettingsUi, server: ServerState, vm: SettingsViewModel,
    torrState: TorrServerSettingsUi, connection: ConnectionState, torrVm: TorrServerSettingsViewModel,
    initialCategory: SettingsCategory? = null, dismiss: () -> Unit,
    phoneRemote: @Composable () -> Unit = {}, updates: @Composable () -> Unit) {
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val categoriesState = rememberLazyListState()
    val categoryFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    var returnCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val backFocus = remember { FocusRequester() }
    val engineFocus = remember { FocusRequester() }
    var restoreEngineFocus by remember { mutableStateOf(false) }
    var focusedPage by remember { mutableStateOf<String?>(null) }
    var panelHasFocus by remember { mutableStateOf(false) }
    val motionDuration = if (LocalAnimationsEnabled.current) 240 else 0
    val inputMode = LocalInputModeManager.current
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val drawerWidth by animateFloatAsState(if (category == null) .25f else .5f, tween(motionDuration), label = "settingsWidth")
    fun close() { visible.targetState = false }
    fun back() {
        if (engineOpen) { engineOpen = false; restoreEngineFocus = true }
        else if (category != null) { returnCategory = category; category = null }
        else close()
    }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) dismiss()
    }
    val contentDensity = LocalDensity.current
    val platformDensity = Density(LocalContext.current.resources.displayMetrics.density, LocalConfiguration.current.fontScale)
    // Keep the native dialog stable while the user's UI scale changes inside it.
    CompositionLocalProvider(LocalDensity provides platformDensity) {
        Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompositionLocalProvider(LocalDensity provides contentDensity) {
                val window = (LocalView.current.parent as DialogWindowProvider).window
                SideEffect { window.setDimAmount(0f) }
                BackHandler(onBack = ::back)
                Box(Modifier.fillMaxSize().testTag("settings:overlay")) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f))
                        .pointerInput(Unit) { detectTapGestures { close() } })
                    AnimatedVisibility(visibleState = visible, modifier = Modifier.align(Alignment.CenterEnd),
                        enter = slideInHorizontally(tween(motionDuration)) { it }, exit = slideOutHorizontally(tween(if (LocalAnimationsEnabled.current) 200 else 0)) { it }) {
                        Column(Modifier.fillMaxHeight().fillMaxWidth(drawerWidth).testTag("settings:drawer")
                            .onFocusChanged { panelHasFocus = it.hasFocus }.focusGroup()
                            .background(Background).border(1.dp, Outline).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (engineOpen) {
                                EngineSettingsScreen(torrState, torrVm) { engineOpen = false; restoreEngineFocus = true }
                            } else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (category != null) TvBackAction(
                                        modifier = Modifier.focusRequester(backFocus).testTag("settings:back"), action = ::back)
                                    Text(stringResource(category?.title ?: R.string.settings_title), modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.headlineSmall)
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                                if (category == null) {
                                    LazyColumn(Modifier.weight(1f).testTag("settings:categories"), state = categoriesState,
                                        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                                        items(SettingsCategory.entries, key = { it.name }) { value ->
                                            TvAction(stringResource(value.title), modifier = Modifier.fillMaxWidth()
                                                .focusRequester(categoryFocus.getValue(value)).testTag("settings:category:${value.name}")) {
                                                category = value
                                            }
                                        }
                                    }
                                } else key(category) {
                                    val s = state.draft
                                    LazyColumn(Modifier.weight(1f).testTag("settings:detail:${category!!.name}"),
                                        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                                        item("message") { Message(state.message) }
                                        when (category) {
                                            SettingsCategory.PhoneRemote -> item { SettingsGroup(stringResource(category!!.title), phoneRemote) }
                                            SettingsCategory.TorrServer -> item {
                                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    ServerOperationStatus(torrState)
                                                    TorrServerConnectionPanel(torrState, connection, server, torrVm, engineFocus) { engineOpen = true }
                                                }
                                            }
                                            SettingsCategory.Search -> item {
                                                SettingsGroup(stringResource(category!!.title)) {
                                                    EditField(stringResource(R.string.settings_jackett_url), s.jackettUrl, keyboardType = KeyboardType.Uri, russian = true) { value -> vm.edit { it.copy(jackettUrl = value) } }
                                                    EditField(stringResource(R.string.settings_jackett_key), s.jackettKey, secret = true, russian = true) { value -> vm.edit { it.copy(jackettKey = value) } }
                                                    Text(stringResource(state.connection), color = Muted)
                                                    TvAction(stringResource(R.string.settings_test_connection), action = vm::test)
                                                }
                                            }
                                            SettingsCategory.Metadata -> item {
                                                SettingsGroup(stringResource(category!!.title)) {
                                                    Text(stringResource(R.string.settings_metadata_priority), color = Muted)
                                                    MetadataPriorityControls(s.metadataOrder, vm::moveProvider)
                                                    TvToggle(stringResource(R.string.settings_anilist), s.aniListEnabled, Modifier.testTag("metadata:anilist")) { value -> vm.edit { it.copy(aniListEnabled = value) } }
                                                    Text(stringResource(R.string.settings_anilist_hint), color = Muted)
                                                    Text(stringResource(R.string.settings_metadata_language), color = Muted)
                                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        ChoiceRow(listOf("en-US", "ru-RU"), s.language) { value -> vm.edit { it.copy(language = value) } }
                                                        TvAction(stringResource(R.string.settings_clear_metadata), action = vm::clearCache)
                                                    }
                                                }
                                            }
                                            SettingsCategory.Playback -> item {
                                                SettingsGroup(stringResource(category!!.title)) {
                                                    TvToggle(stringResource(R.string.settings_resume), s.resume) { value -> vm.edit { it.copy(resume = value) } }
                                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        EditField(stringResource(R.string.settings_audio), s.audioLanguage, modifier = Modifier.fillMaxWidth(), russian = true) { value -> vm.edit { it.copy(audioLanguage = value.trim()) } }
                                                        EditField(stringResource(R.string.settings_subtitles), s.subtitleLanguage, modifier = Modifier.fillMaxWidth(), russian = true) { value -> vm.edit { it.copy(subtitleLanguage = value.trim()) } }
                                                    }
                                                }
                                            }
                                            SettingsCategory.Updates -> item { SettingsGroup(stringResource(category!!.title), updates) }
                                            SettingsCategory.Appearance -> item {
                                                SettingsGroup(stringResource(category!!.title)) {
                                                    TvToggle(stringResource(R.string.ui_animations), s.animationsEnabled, Modifier.testTag("appearance:animations"), vm::animations)
                                                    UiScaleControls(s.uiScalePercent, vm::scale, vm::resetScale)
                                                    Text(stringResource(R.string.ui_language_title), color = Muted)
                                                    ChoiceRow(listOf("en", "ru"), s.uiLanguage, vm::language)
                                                    Text(stringResource(R.string.ui_theme_title), color = Muted)
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        TvAction(stringResource(R.string.ui_theme_dark), selected = s.uiTheme == "dark", modifier = Modifier.testTag("appearance:dark")) { vm.theme("dark") }
                                                        TvAction(stringResource(R.string.ui_theme_light), selected = s.uiTheme == "light", modifier = Modifier.testTag("appearance:light")) { vm.theme("light") }
                                                    }
                                                    TvToggle(stringResource(R.string.ui_navigation_hints), s.showNavigationHints, Modifier.testTag("appearance:hints"), vm::hints)
                                                    Text(stringResource(R.string.ui_accent_title), color = Muted)
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                }
                                            }
                                            SettingsCategory.About -> item {
                                                SettingsGroup(stringResource(category!!.title)) {
                                                    Text(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), color = Muted)
                                                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.tmdb_logo),
                                                        contentDescription = "TMDB", modifier = Modifier.width(164.dp).height(24.dp))
                                                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, translate = false)
                                                    TvAction(stringResource(R.string.settings_licenses)) { licenses = true }
                                                }
                                            }
                                            null -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val windowFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(category, engineOpen, windowFocused) {
                    if (engineOpen) focusedPage = "engine"
                    if (!engineOpen && windowFocused) {
                        withFrameNanos { }
                        val page = category?.name ?: "categories"
                        if (focusedPage == page && panelHasFocus && !restoreEngineFocus) return@LaunchedEffect
                        focusedPage = page
                        inputMode.requestInputMode(InputMode.Keyboard)
                        val targetFocus = if (restoreEngineFocus) engineFocus else if (category == null)
                            categoryFocus.getValue(returnCategory ?: SettingsCategory.PhoneRemote) else backFocus
                        if (restoreEngineFocus) {
                            engineFocus.requestFocus(); restoreEngineFocus = false
                        } else if (category == null) {
                            val target = returnCategory ?: SettingsCategory.PhoneRemote
                            categoriesState.scrollToItem(SettingsCategory.entries.indexOf(target))
                            withFrameNanos { }
                            categoryFocus.getValue(target).requestFocus()
                            returnCategory = null
                        } else backFocus.requestFocus()
                        kotlinx.coroutines.delay(260)
                        if (!panelHasFocus) {
                            inputMode.requestInputMode(InputMode.Keyboard)
                            targetFocus.requestFocus()
                        }
                    }
                }
                if (licenses) LicensesDialog { licenses = false }
            }
        }
    }
}

@Composable internal fun MetadataPriorityControls(values: List<String>, move: (String, Int) -> Unit) {
    val order = MetadataPriority.normalized(values)
    val focuses = remember { MetadataPriority.defaultOrder.flatMap { p ->
        listOf("$p:up" to FocusRequester(), "$p:down" to FocusRequester())
    }.toMap() }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { index, provider -> key(provider) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}. ${MetadataPriority.label(provider)}", modifier = Modifier.testTag("priority:$provider:label"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
