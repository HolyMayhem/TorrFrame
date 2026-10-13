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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.core.server.ServerState
import tv.torrserve.silicon.features.home.HomeScreen
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
            TorrServeTheme(settings.uiScalePercent) {
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
    val search: SearchViewModel = viewModel(factory = factory)
    val settings: SettingsViewModel = viewModel(factory = factory)
    val torrSettings: TorrServerSettingsViewModel = viewModel(factory = factory)
    val updates: UpdatesViewModel = viewModel(factory = factory)
    val lib by library.state.collectAsStateWithLifecycle()
    val query by search.state.collectAsStateWithLifecycle()
    val set by settings.state.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val saved by graph.settings.state.collectAsStateWithLifecycle()
    val server by graph.server.state.collectAsStateWithLifecycle()
    val connection by graph.connection.state.collectAsStateWithLifecycle()
    val torrSet by torrSettings.state.collectAsStateWithLifecycle()
    val phonePreference by graph.phonePreferences.state.collectAsStateWithLifecycle()
    var phoneQr by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Home") }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val screens = rememberSaveableStateHolder()
    val initialFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var pendingContentFocus by remember { mutableStateOf<String?>(null) }
    fun navigateFromContent(target: String) { pendingContentFocus = target; section = target }
    BackHandler(detail != null || section != "Home") {
        if(detail != null) { detail = null; library.closeFiles() } else { section = "Home"; initialFocus.requestFocus() }
    }
    fun play(hash: String, file: TorrentFile) {
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .putExtra("stream", graph.api.stream(hash,file)).putExtra("identity", "$hash:${file.id}")
            .putExtra("title", lib.items.find { it.hash == hash }?.title ?: file.path.substringAfterLast('/')))
    }
    val selected = lib.items.find { it.hash == detail }
    val layout = LocalTvLayout.current
    Column(Modifier.fillMaxSize().background(Background).padding(layout.screenInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().testTag("app:header").focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BrandMark()
                Column {
                    Text("TorrServe", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp), translate = false)
                    Text("Silicon", color = Muted, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 10.sp, lineHeight = 13.sp), translate = false)
                }
            }
            listOf("Home", "Search", "Library", "Settings").forEach { tab ->
                TvNavItem(tab, section == tab && detail == null,
                    modifier = (if(tab == "Home") Modifier.focusRequester(initialFocus) else Modifier)
                        .then(if(detail == null) Modifier.focusProperties { down = contentFocus } else Modifier)) { detail = null; section = tab; library.closeFiles() }
            }
            Spacer(Modifier.weight(1f))
            if (phonePreference.enabled) tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteShortcut {
                if (graph.phoneRemote.state.value.status == tv.torrserve.silicon.phoneRemote.server.PhoneRemoteStatus.Running) phoneQr = true
                else navigateFromContent("Settings")
            }
            TvAction(stringResource(when(connection.status) {
                tv.torrserve.silicon.core.server.ConnectionStatus.Connected -> R.string.server_connected
                tv.torrserve.silicon.core.server.ConnectionStatus.Connecting -> R.string.server_connecting
                tv.torrserve.silicon.core.server.ConnectionStatus.Unavailable -> R.string.server_unavailable
                tv.torrserve.silicon.core.server.ConnectionStatus.Error -> R.string.server_connection_error
            })) {
                detail = null; section = "Settings"
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline.copy(alpha = .55f)))
        Box(Modifier.weight(1f).testTag("app:content").focusGroup()) {
            if(selected != null) MovieDetailScreen(selected, lib, library, { detail = null }, { value ->
                detail = null; navigateFromContent("Search"); search.query(value); if(saved.jackettConfigured) search.search(value)
            }, ::play)
            else screens.SaveableStateProvider(section) {
                when(section) {
                    "Home" -> Column { Message(lib.message); HomeScreen(lib.items, { navigateFromContent("Search") }, { navigateFromContent("Library") }, { navigateFromContent("Settings") }, initialFocus, contentFocus) { detail = it } }
                    "Library" -> LibraryScreen(lib, library::refresh, { navigateFromContent("Search") }, contentFocus) { detail = it }
                    "Search" -> SearchScreen(query, saved.jackettConfigured, search, { navigateFromContent("Settings") }, contentFocus) { hash ->
                        detail = hash; library.files(hash) { files -> if(files.size == 1) { library.closeFiles(); play(hash, files.single()) } }
                    }
                    "Settings" -> SettingsScreen(set, server, settings, torrSet, connection, torrSettings, contentFocus, phoneRemote = { tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteSettings(graph) }) {
                        UpdatesPanel(update, set.draft, { value -> settings.edit { it.copy(updateRepository = value) } }, updates) {
                            updates.installer()?.let { intent -> runCatching { context.startActivity(intent) }.onFailure { updates.installerFailed() } }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().testTag("app:footer"), horizontalArrangement = Arrangement.SpaceBetween) {
            val hintStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 9.sp, lineHeight = 12.sp)
            Text("↑ ↓ ← →  Navigate     OK  Select     BACK  Return", color = Muted, style = hintStyle)
            Text("Android TV", color = Muted.copy(alpha = .7f), style = hintStyle, translate = false)
        }
    }
    if (phoneQr) tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteQrDialog(graph.phoneRemote) { phoneQr = false }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    LaunchedEffect(pendingContentFocus) {
        if(pendingContentFocus != null) {
            withFrameNanos { }
            contentFocus.requestFocus()
            pendingContentFocus = null
        }
    }
}
