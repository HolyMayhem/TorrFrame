package tv.torrframe

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import tv.torrframe.ui.sound.UiBackHandler as BackHandler
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.torrframe.ui.components.TvText as Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import tv.torrframe.core.localization.uiString
import tv.torrframe.features.catalog.presentation.*
import tv.torrframe.features.catalog.domain.CatalogUserList
import tv.torrframe.features.catalog.domain.CatalogItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import tv.torrframe.core.models.TorrentFile
import tv.torrframe.core.server.ServerState
import tv.torrframe.features.library.*
import tv.torrframe.features.settings.*
import tv.torrframe.features.updates.*
import tv.torrframe.features.torrentsearch.presentation.*
import tv.torrframe.features.torrentsearch.data.AppTorrentSearchActions
import tv.torrserve.torrentsearch.model.SearchTarget
import tv.torrserve.torrentsearch.model.MediaType
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.*

class MainActivity : ComponentActivity() {
    private var frameMonitor: tv.torrframe.core.performance.FrameMonitor? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        val began = SystemClock.elapsedRealtime()
        val graph = (application as TorrFrameApplication).graph
        val preview = graph.developer.takeStartupPreview()
        super.onCreate(if (preview != null) null else savedInstanceState)
        if (preview != null) graph.startupSplash.restartForPreview(preview.readyDelayMs)
        tv.torrframe.features.startup.StartupWindow.install(this)
        @Suppress("DEPRECATION") val refresh = windowManager.defaultDisplay.refreshRate.coerceAtLeast(30f)
        frameMonitor = tv.torrframe.core.performance.FrameMonitor(window,(1_000_000_000 / refresh).toLong())
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            val preferencesReady by produceState(false, graph) {
                try { graph.ready.await(); value = true }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
            val previewRequest by graph.developer.startupPreviews.collectAsStateWithLifecycle()
            LaunchedEffect(previewRequest) { if (previewRequest != null) recreate() }
            var mainReady by remember { mutableStateOf(false) }
            tv.torrframe.features.startup.StartupSplash(graph.startupSplash, mainReady, close = ::finish, revealed = {
                reportFullyDrawn()
                if(BuildConfig.DEBUG) Log.i("TorrFrameTiming", "activity_ui_ms=${SystemClock.elapsedRealtime() - began}")
            }, soundEnabled = preferencesReady && settings.uiSoundsEnabled) { covered ->
                TorrFrameTheme(settings.uiScalePercent, light = settings.uiTheme == "light", accent = settings.uiAccent, animationsEnabled = settings.animationsEnabled) {
                    CompositionLocalProvider(LocalCatalogImageSettings provides settings.catalogNetwork) {
                        CatalogImageRecovery {
                            AppLanguage(settings.uiLanguage) {
                                Box(Modifier.fillMaxSize()) {
                                    AppScreen(graph, covered) { mainReady = true }
                                    tv.torrframe.onboarding.SetupHost(graph, enabled = preferencesReady && !covered)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tv.torrframe.features.startup.StartupWindow.hideBars(window)
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
            flow { graph.ready.await(); emitAll(graph.settings.state.map { if (it.uiLanguage == "ru") "ru-RU" else "en-US" }) }, extras.createSavedStateHandle(), userLists = graph.catalogUserLists,
            configurationChanges = flow { graph.ready.await(); emitAll(graph.settings.state.map { it.catalogNetwork.connection() }) }) as T
        else if(modelClass == TorrentSearchViewModel::class.java) TorrentSearchViewModel(graph.torrentSearch,AppTorrentSearchActions(graph),extras.createSavedStateHandle(),
            options = { tv.torrframe.features.torrentsearch.data.TorrentSearchConfiguration(graph).options() },
            prepare = { target, options -> tv.torrframe.features.torrentsearch.data.TorrentSearchConfiguration(graph).prepare(target,options) }, jackettEngine = graph.jackettSearch) as T
        else create(modelClass)
    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = when(modelClass) {
        LibraryViewModel::class.java -> LibraryViewModel(graph)
        SettingsViewModel::class.java -> SettingsViewModel(graph)
        TorrServerSettingsViewModel::class.java -> TorrServerSettingsViewModel(graph)
        UpdatesViewModel::class.java -> UpdatesViewModel(app, graph)
        else -> error("Unknown ViewModel")
    } as T
}
@Composable private fun AppScreen(graph: AppGraph, startupCovered: Boolean, onReady: () -> Unit) {
    val context = LocalContext.current
    val developerUnlockedMessage = stringResource(R.string.developer_unlocked)
    val developerLockedMessage = stringResource(R.string.developer_locked)
    var developerNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(developerNotice) {
        if (developerNotice != null) { kotlinx.coroutines.delay(2_500); developerNotice = null }
    }
    val playbackScope = rememberCoroutineScope()
    val factory = remember { Models(graph, context.applicationContext as android.app.Application) }
    val library: LibraryViewModel = viewModel(factory = factory)
    val uiReady by rememberUpdatedState(onReady)
    LaunchedEffect(Unit) {
        try { graph.ready.await(); library.initialLocalLoad.await() }
        catch (e: Exception) { if (e is CancellationException) throw e /* Reveal the existing error UI too. */ }
        withFrameNanos { }; withFrameNanos { }; uiReady()
    }
    val settings: SettingsViewModel = viewModel(factory = factory)
    val torrSettings: TorrServerSettingsViewModel = viewModel(factory = factory)
    val updates: UpdatesViewModel = viewModel(factory = factory)
    val torrentSearch: TorrentSearchViewModel = viewModel(factory = factory)
    val torrentState by torrentSearch.state.collectAsStateWithLifecycle()
    var restoreTorrentFocus by rememberSaveable { mutableStateOf(false) }
    var restoreJackettFocus by rememberSaveable { mutableStateOf(false) }
    val lib by library.state.collectAsStateWithLifecycle()
    val set by settings.state.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val saved by graph.settings.state.collectAsStateWithLifecycle()
    val server by graph.server.state.collectAsStateWithLifecycle()
    val connection by graph.connection.state.collectAsStateWithLifecycle()
    val torrSet by torrSettings.state.collectAsStateWithLifecycle()
    val phonePreference by graph.phonePreferences.state.collectAsStateWithLifecycle()
    val developer by graph.developer.state.collectAsStateWithLifecycle()
    val diagnosticsVisible by graph.developer.diagnosticsVisible.collectAsStateWithLifecycle()
    val catalogReloads by graph.developer.catalogReloads.collectAsStateWithLifecycle()
    val libraryServer = libraryAvailability(saved.useLocalServer,server,connection,developer.serverUnavailable)
    LaunchedEffect(libraryServer) { if(libraryServer == LibraryAvailability.Ready)library.refresh() }
    var phoneQr by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("Library") }
    var catalogHomeRequest by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryList by rememberSaveable { mutableStateOf<CatalogUserList?>(null) }
    var libraryReturnList by rememberSaveable { mutableStateOf<CatalogUserList?>(null) }
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
        playbackScope.launch {
            try {
                graph.ready.await()
                graph.playback.open(graph.api.stream(hash, file), "$hash:${file.id}",
                    lib.items.find { it.hash == hash }?.title ?: file.path.substringAfterLast('/'))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.widget.Toast.makeText(context, context.uiString(R.string.player_start_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    fun closeTorrentSearch() { restoreJackettFocus = torrentState.source == TorrentSearchSource.Jackett; torrentSearch.close();restoreTorrentFocus=true }
    LaunchedEffect(saved.jackettEnabled) { if(!saved.jackettEnabled && torrentState.target != null && torrentState.source == TorrentSearchSource.Jackett) closeTorrentSearch() }
    LaunchedEffect(torrentState.play) {
        torrentState.play?.let { request -> torrentSearch.consumePlay();play(request.hash,request.file) }
    }
    LaunchedEffect(restoreTorrentFocus,torrentState.target) {
        if(restoreTorrentFocus && torrentState.target==null) { withFrameNanos { };withFrameNanos { };restoreTorrentFocus=false }
    }
    fun searchCatalogItem(item: CatalogItem, source: TorrentSearchSource = TorrentSearchSource.Builtin) {
        if(source == TorrentSearchSource.Jackett && (!saved.jackettEnabled || !saved.jackettConfigured)) { openSettings(SettingsCategory.Search); return }
        restoreTorrentFocus = false
        torrentSearch.open(SearchTarget(item.title, item.originalTitle, item.year,
            if (item.ref.type == tv.torrframe.features.catalog.domain.CatalogType.Tv) MediaType.Series else MediaType.Movie, item.ref.id), source)
    }
    val selected = lib.items.find { it.hash == detail }
    val layout = LocalTvLayout.current
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("app:screen").background(Background).padding(horizontal = layout.screenInset)
        .padding(top = 16.dp, bottom = if (saved.showNavigationHints) layout.screenInset else 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp).testTag("app:header")
            .focusProperties { down = screenFocus }.focusGroup()) {
            Row(Modifier.align(androidx.compose.ui.Alignment.CenterStart).testTag("app:brand"), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BrandMark(size = 30.dp)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = if (layout.narrow) 19.sp else 22.sp, lineHeight = 27.sp), translate = false)
            }
            Row(Modifier.align(androidx.compose.ui.Alignment.Center).testTag("app:navigation"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Search", "Library").forEach { tab ->
                    TvNavItem(tab, section == tab && detail == null,
                        modifier = if(tab == "Library") Modifier.focusRequester(initialFocus) else Modifier,
                        displayLabel = if(tab == "Search") "Catalog" else tab,
                        onLongClick = if (tab == "Search") ({
                            torrentSearch.close(); restoreTorrentFocus = false; restoreJackettFocus = false
                            detail = null; section = "Search"; library.closeFiles()
                            catalogHomeRequest++
                        }) else null) {
                        torrentSearch.close(); restoreTorrentFocus=false; detail = null; section = tab; library.closeFiles()
                    }
                }
            }
            Row(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).testTag("app:utilities"), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (phonePreference.enabled) tv.torrframe.phoneRemote.ui.PhoneRemoteShortcut {
                    if (graph.phoneRemote.state.value.status == tv.torrframe.phoneRemote.server.PhoneRemoteStatus.Running) phoneQr = true
                    else openSettings(SettingsCategory.PhoneRemote)
                }
                ServerStatusIndicator(saved.useLocalServer, server, connection) { openSettings(SettingsCategory.TorrServer) }
                SettingsShortcut(Modifier.focusRequester(settingsFocus), settingsOpen, onHold = {
                    playbackScope.launch {
                        graph.toggleDeveloper()?.let { enabled ->
                            developerNotice = if (enabled) developerUnlockedMessage else developerLockedMessage
                        }
                    }
                }) { openSettings() }
            }
        }
        if(developer.active)Text(stringResource(R.string.developer_active),color=Muted,
            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("developer:active"))
        // Enter the mounted screen, including whichever detail page or lazy item is visible.
        Box(Modifier.weight(1f).testTag("app:content").focusRequester(screenFocus).focusGroup()) {
            if(torrentState.target!=null)TorrentSearchScreen(torrentState,torrentSearch,contentFocus,::closeTorrentSearch)
            else if(selected != null) MovieDetailScreen(selected, lib, library, { detail = null },
                torrentSearch = { restoreTorrentFocus=false;torrentSearch.open(SearchTarget(selected.title,selected.metadata?.originalTitle.orEmpty(),selected.metadata?.year,
                    if(selected.metadata?.kind==tv.torrframe.core.models.MediaKind.Tv)MediaType.Series else MediaType.Movie,
                    selected.metadata?.takeIf { it.provider=="TMDB" }?.id?.toIntOrNull())) },
                jackettSearch = {
                    if (!saved.jackettConfigured) openSettings(SettingsCategory.Search) else {
                        restoreTorrentFocus=false
                        torrentSearch.open(SearchTarget(selected.title,selected.metadata?.originalTitle.orEmpty(),selected.metadata?.year,
                            if(selected.metadata?.kind==tv.torrframe.core.models.MediaKind.Tv)MediaType.Series else MediaType.Movie,
                            selected.metadata?.takeIf { it.provider=="TMDB" }?.id?.toIntOrNull()), TorrentSearchSource.Jackett)
                    }
                }, restoreJackettFocus=restoreJackettFocus,
                restoreTorrentFocus=restoreTorrentFocus,jackettEnabled=saved.jackettEnabled,play=::play)
            else screens.SaveableStateProvider(if (section == "Library" && libraryList != null) "LibraryList:${libraryList!!.name}" else section) {
                when(section) {
                    "Library" -> {
                        val list = libraryList
                        if (list == null) LibraryScreen(lib, library::refresh, { navigateFromContent("Search") }, contentFocus,
                            availability = libraryAvailability(saved.useLocalServer,server,connection,developer.serverUnavailable,
                                if(lib.syncFailed)lib.syncFailure ?: 0 else null),
                            serverSettings = { openSettings(SettingsCategory.TorrServer) },
                            openList = { libraryList = it }, restoreListFocus = libraryReturnList,
                            listFocusRestored = { libraryReturnList = null }) { detail = it }
                        else {
                            // Independent navigation, shared catalog repository/cache and saved user lists.
                            val catalog: CatalogViewModel = viewModel(key = "library-list:${list.name}", factory = factory)
                            val catalogState by catalog.state.collectAsStateWithLifecycle()
                            CatalogScreen(catalogState, catalog, contentFocus, torrentSearch = { searchCatalogItem(it) }, jackettSearch = { searchCatalogItem(it, TorrentSearchSource.Jackett) }, restoreJackettFocus = restoreJackettFocus,
                                restoreTorrentFocus = restoreTorrentFocus, refreshRevision = catalogReloads,
                                jackettEnabled = saved.jackettEnabled, initialUserList = list, closeUserList = { libraryReturnList = list; libraryList = null })
                        }
                    }
                    "Search" -> {
                        val catalog: CatalogViewModel = viewModel(factory = factory)
                        val catalogState by catalog.state.collectAsStateWithLifecycle()
                        CatalogScreen(catalogState, catalog, contentFocus, torrentSearch = { searchCatalogItem(it) }, jackettSearch = { searchCatalogItem(it, TorrentSearchSource.Jackett) }, restoreJackettFocus = restoreJackettFocus,
                            restoreTorrentFocus=restoreTorrentFocus,refreshRevision=catalogReloads,
                            homeRequest=catalogHomeRequest, homeHandled={ catalogHomeRequest=0 },
                            showFavorites=saved.catalogNetwork.showFavorites,showWatchLater=saved.catalogNetwork.showWatchLater,jackettEnabled=saved.jackettEnabled)
                    }
                }
            }
        }
        if (diagnosticsVisible) tv.torrframe.features.developer.DiagnosticPanel(graph.networkDiagnostics)
        if (saved.showNavigationHints) Row(Modifier.fillMaxWidth().testTag("app:footer"), horizontalArrangement = Arrangement.SpaceBetween) {
            val hintStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 9.sp, lineHeight = 12.sp)
            Text("↑ ↓ ← →  Navigate     OK  Select     BACK  Return", color = Muted, style = hintStyle)
            Text("Android TV", color = Muted.copy(alpha = .7f), style = hintStyle, translate = false)
        }
    }
    developerNotice?.let { notice ->
        Text(notice, translate = false, maxLines = 2,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 24.dp)
                .background(SurfaceSecondary, androidx.compose.foundation.shape.CircleShape)
                .padding(horizontal = 20.dp, vertical = 10.dp).testTag("developer:notice")
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
    }
    if (settingsOpen && !startupCovered) SettingsScreen(set, server, settings, torrSet, connection, torrSettings,
        initialCategory = settingsCategory,
        onSetup = { settingsOpen = false; graph.setup.restart() },
        dismiss = { settingsOpen = false; restoreSettingsFocus = true },
        phoneRemote = { tv.torrframe.phoneRemote.ui.PhoneRemoteSettings(graph) }) {
        UpdatesPanel(update, set.draft, { value -> settings.edit { it.copy(updateRepository = value) } }, updates) {
            updates.installer()?.let { intent -> runCatching { context.startActivity(intent) }.onFailure { updates.installerFailed() } }
        }
    }
    LaunchedEffect(restoreSettingsFocus) {
        if (restoreSettingsFocus) { withFrameNanos { }; settingsFocus.requestFocus(); restoreSettingsFocus = false }
    }
    if (phoneQr && !startupCovered) tv.torrframe.phoneRemote.ui.PhoneRemoteQrDialog(graph.phoneRemote) { phoneQr = false }
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
