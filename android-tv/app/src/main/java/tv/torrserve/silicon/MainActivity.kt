package tv.torrserve.silicon

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.torrserve.silicon.ui.components.TvText as Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import tv.torrserve.silicon.features.catalog.presentation.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.core.server.ServerState
import tv.torrserve.silicon.features.library.*
import tv.torrserve.silicon.features.search.*
import tv.torrserve.silicon.features.settings.*
import tv.torrserve.silicon.features.updates.*
import tv.torrserve.silicon.features.player.PlayerActivity
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.*

class MainActivity : ComponentActivity() {
    private var frameMonitor: tv.torrserve.silicon.core.performance.FrameMonitor? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        val began = SystemClock.elapsedRealtime(); super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") val refresh = windowManager.defaultDisplay.refreshRate.coerceAtLeast(30f)
        frameMonitor = tv.torrserve.silicon.core.performance.FrameMonitor(window,(1_000_000_000 / refresh).toLong())
        val graph = (application as TorrServeApplication).graph
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            TorrServeTheme(settings.uiScalePercent, light = settings.uiTheme == "light", accent = settings.uiAccent, animationsEnabled = settings.animationsEnabled) {
                CompositionLocalProvider(LocalUiLanguage provides settings.uiLanguage) { AppScreen(graph) }
                LaunchedEffect(Unit) { withFrameNanos { }; reportFullyDrawn()
                    if(BuildConfig.DEBUG) Log.i("TorrServeTiming", "activity_ui_ms=${SystemClock.elapsedRealtime() - began}") }
            }
        }
    }
    override fun onDestroy() { frameMonitor?.close(); frameMonitor = null; super.onDestroy() }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if(keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
private class Models(private val graph: AppGraph, private val app: android.app.Application): ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        if (modelClass == CatalogViewModel::class.java) CatalogViewModel(graph.catalog,
            flow { graph.ready.await(); emitAll(graph.settings.state.map { if (it.uiLanguage == "ru") "ru-RU" else "en-US" }) }, extras.createSavedStateHandle(), userLists = graph.catalogUserLists) as T
        else create(modelClass)
    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = when(modelClass) {
        LibraryViewModel::class.java -> LibraryViewModel(graph)
        SearchViewModel::class.java -> SearchViewModel(graph)
        SettingsViewModel::class.java -> SettingsViewModel(graph)
        TorrServerSettingsViewModel::class.java -> TorrServerSettingsViewModel(graph)
        UpdatesViewModel::class.java -> UpdatesViewModel(app, graph)
        else -> error("Unknown ViewModel")
    } as T
}
@Composable private fun AppScreen(graph: AppGraph) {
    val context = LocalContext.current
    val factory = remember { Models(graph, context.applicationContext as android.app.Application) }
    val library: LibraryViewModel = viewModel(factory = factory)
    val settings: SettingsViewModel = viewModel(factory = factory)
    val torrSettings: TorrServerSettingsViewModel = viewModel(factory = factory)
    val updates: UpdatesViewModel = viewModel(factory = factory)
    val lib by library.state.collectAsStateWithLifecycle()
    val set by settings.state.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val saved by graph.settings.state.collectAsStateWithLifecycle()
    val server by graph.server.state.collectAsStateWithLifecycle()
    val connection by graph.connection.state.collectAsStateWithLifecycle()
    val torrSet by torrSettings.state.collectAsStateWithLifecycle()
    val phonePreference by graph.phonePreferences.state.collectAsStateWithLifecycle()
    var phoneQr by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Library") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val screens = rememberSaveableStateHolder()
    val initialFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val screenFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var restoreSettingsFocus by remember { mutableStateOf(false) }
    fun openSettings(category: SettingsCategory? = null) { settingsCategory = category; settingsOpen = true }
    var pendingContentFocus by remember { mutableStateOf<String?>(null) }
    fun navigateFromContent(target: String) {
        if (target == "Settings") openSettings(SettingsCategory.Search)
        else { pendingContentFocus = target; section = target }
    }
    BackHandler(!settingsOpen && !phoneQr && (detail != null || section != "Library")) {
        if(detail != null) { detail = null; library.closeFiles() } else { section = "Library"; initialFocus.requestFocus() }
    }
    fun play(hash: String, file: TorrentFile) {
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .putExtra("stream", graph.api.stream(hash,file)).putExtra("identity", "$hash:${file.id}")
            .putExtra("title", lib.items.find { it.hash == hash }?.title ?: file.path.substringAfterLast('/')))
    }
    val selected = lib.items.find { it.hash == detail }
    val layout = LocalTvLayout.current
    Column(Modifier.fillMaxSize().testTag("app:screen").background(Background).padding(horizontal = layout.screenInset)
        .padding(top = layout.screenInset, bottom = if (saved.showNavigationHints) layout.screenInset else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().testTag("app:header").focusProperties { down = screenFocus }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BrandMark()
                Text("TorrServe", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp), translate = false)
            }
            listOf("Search", "Library").forEach { tab ->
                TvNavItem(tab, section == tab && detail == null,
                    modifier = if(tab == "Library") Modifier.focusRequester(initialFocus) else Modifier,
                    displayLabel = if(tab == "Search") "Catalog" else tab) { detail = null; section = tab; library.closeFiles() }
            }
            Spacer(Modifier.weight(1f))
            if (phonePreference.enabled) tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteShortcut {
                if (graph.phoneRemote.state.value.status == tv.torrserve.silicon.phoneRemote.server.PhoneRemoteStatus.Running) phoneQr = true
                else openSettings(SettingsCategory.PhoneRemote)
            }
            ServerStatusIndicator(saved.useLocalServer, server, connection) {
                openSettings(SettingsCategory.TorrServer)
            }
            SettingsShortcut(Modifier.focusRequester(settingsFocus), settingsOpen) { openSettings() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline.copy(alpha = .55f)))
        // Enter the mounted screen, including whichever detail page or lazy item is visible.
        Box(Modifier.weight(1f).testTag("app:content").focusRequester(screenFocus).focusGroup()) {
            if(selected != null) MovieDetailScreen(selected, lib, library, { detail = null }, ::play)
            else screens.SaveableStateProvider(section) {
                when(section) {
                    "Library" -> LibraryScreen(lib, library::refresh, { navigateFromContent("Search") }, contentFocus) { detail = it }
                    "Search" -> {
                        val catalog: CatalogViewModel = viewModel(factory = factory)
                        val catalogState by catalog.state.collectAsStateWithLifecycle()
                        CatalogScreen(catalogState, catalog, contentFocus)
                    }
                }
            }
        }
        if (saved.showNavigationHints) Row(Modifier.fillMaxWidth().testTag("app:footer"), horizontalArrangement = Arrangement.SpaceBetween) {
            val hintStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 9.sp, lineHeight = 12.sp)
            Text("↑ ↓ ← →  Navigate     OK  Select     BACK  Return", color = Muted, style = hintStyle)
            Text("Android TV", color = Muted.copy(alpha = .7f), style = hintStyle, translate = false)
        }
    }
    if (settingsOpen) SettingsScreen(set, server, settings, torrSet, connection, torrSettings,
        initialCategory = settingsCategory,
        dismiss = { settingsOpen = false; restoreSettingsFocus = true },
        phoneRemote = { tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteSettings(graph) }) {
        UpdatesPanel(update, set.draft, { value -> settings.edit { it.copy(updateRepository = value) } }, updates) {
            updates.installer()?.let { intent -> runCatching { context.startActivity(intent) }.onFailure { updates.installerFailed() } }
        }
    }
    LaunchedEffect(restoreSettingsFocus) {
        if (restoreSettingsFocus) { withFrameNanos { }; settingsFocus.requestFocus(); restoreSettingsFocus = false }
    }
    if (phoneQr) tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteQrDialog(graph.phoneRemote) { phoneQr = false }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    LaunchedEffect(section) { if (section !in listOf("Search", "Library")) section = "Library" }
    LaunchedEffect(pendingContentFocus) {
        if(pendingContentFocus != null) {
            withFrameNanos { }
            contentFocus.requestFocus()
            pendingContentFocus = null
        }
    }
}
