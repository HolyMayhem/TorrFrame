package tv.torrserve.silicon

import tv.torrserve.silicon.core.localization.uiString
import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.core.network.Http
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.features.search.JackettClient
import tv.torrserve.silicon.features.metadata.*
import tv.torrserve.silicon.features.library.LibraryRepository
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.disk.DiskCache
import coil3.network.NetworkFetcher
import okio.Path.Companion.toOkioPath
import androidx.core.content.edit
import android.app.Activity
import android.os.Bundle

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class AppGraph(val app: Application) {
    val startupSplash = tv.torrserve.silicon.features.startup.StartupSplashState(android.os.SystemClock::elapsedRealtime)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ready = CompletableDeferred<Unit>()
    val developer = tv.torrserve.silicon.features.developer.DeveloperControls(BuildConfig.DEBUG)
    val networkDiagnostics = tv.torrserve.silicon.core.network.NetworkDiagnostics(BuildConfig.DEBUG)
    val settings = SettingsStore(app)
    val http = Http(beforeRequest = developer::beforeRequest, diagnostics = networkDiagnostics.takeIf { BuildConfig.DEBUG },
        dnsOverride = tv.torrserve.silicon.core.network.TmdbDns(),
        catalogRouting = tv.torrserve.silicon.core.network.CatalogRouteInterceptor(settings = { settings.state.value.catalogNetwork }))
    private val imagePreferences = app.getSharedPreferences("image-routing", Application.MODE_PRIVATE)
    val imageNetwork = tv.torrserve.silicon.core.network.CatalogImageNetwork(http.client,
        { settings.state.value.catalogNetwork }, tv.torrserve.silicon.core.network.CatalogImageRoutes(
            imagePreferences.getString("preferredHost", "").orEmpty(),
            { imagePreferences.edit { putString("preferredHost", it) } }), networkDiagnostics.takeIf { BuildConfig.DEBUG })
    val serverWebAddress = ServerWebAddressMonitor(app, settings, scope)
    val store = LibraryStore(app)
    val api = TorrServerApi(http) { settings.state.value.torrServerUrl }
    // Keep process health checks real even while user-facing API failures are simulated.
    private val localApi = TorrServerApi(Http(http.client)) { "http://127.0.0.1:${settings.state.value.port}" }
    val connection = ServerConnection(api, settings.state, scope)
    val server = ServerController(app, localApi, scope, settings)
    val jackett = JackettClient(http)
    val jackettSearch = tv.torrserve.torrentsearch.engine.TorrentSearchEngine(listOf(
        tv.torrserve.silicon.features.search.JackettSearchProvider(jackett) { settings.state.value }))
    val torrentSearchNetwork = tv.torrserve.silicon.features.torrentsearch.data.TorrentSearchNetwork(http)
    val torrentSearch = tv.torrserve.torrentsearch.engine.TorrentSearchEngine(tv.torrserve.torrentsearch.provider.builtinProviders(torrentSearchNetwork))
    val tmdb = tv.torrserve.silicon.core.network.TmdbClient(http)
    val catalogCache = tv.torrserve.silicon.features.catalog.data.SqliteCatalogCache(app)
    val catalogUserLists = tv.torrserve.silicon.features.catalog.data.SqliteCatalogUserLists(app)
    val catalog = tv.torrserve.silicon.features.catalog.data.ConfiguredCatalogRepository(
        { settings.state.value.catalogNetwork }, http, tmdb,
        tv.torrserve.silicon.features.developer.DeveloperCatalogCache(catalogCache, developer), scope)
    val metadataProviders = MetadataProviders(http, tmdb)
    val metadata = MetadataResolver(metadataProviders, store) { developer.state.value.bypassMetadata(it) }
    val phonePreferences = tv.torrserve.silicon.phoneRemote.settings.PhoneRemotePreferences(app)
    val serverActions = TorrServerActions(app, settings, server, api, connection, scope) { phonePreferences.state.value.takeIf { it.enabled }?.port }
    val updates = tv.torrserve.silicon.features.updates.UpdateManager(app, this)
    val metadataEditor = MetadataEditor(store, metadataProviders, settings)
    val externalPlayers = tv.torrserve.silicon.features.player.ExternalPlayers(app)
    val playback = tv.torrserve.silicon.features.player.PlaybackCoordinator(app, { settings.state.value }) { server.foreground }
    val library = LibraryRepository(store, api, server, jackett, metadata, settings, scope)
    val phoneRemote by lazy { tv.torrserve.silicon.phoneRemote.server.PhoneRemoteManager(this) }
    init {
        scope.launch {
            developer.state.map { it.serverUnavailable }.distinctUntilChanged().drop(1).collectLatest {
                ready.await(); connection.check()
            }
        }
        scope.launch {
            try {
                settings.load(); ready.complete(Unit)
                if (settings.state.value.startOnBoot && settings.state.value.useLocalServer) {
                    try { LocalServerService.start(app) }
                    catch (_: RuntimeException) { server.backgroundError(app.uiString(tv.torrserve.silicon.R.string.boot_restricted)) }
                }
            }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                server.backgroundError(app.uiString(tv.torrserve.silicon.R.string.settings_load_failed))
                ready.completeExceptionally(IllegalStateException("Could not load encrypted settings."))
            }
        }
        scope.launch {
            try { ready.await() }
            catch (e: Exception) { if (e is CancellationException) throw e; return@launch }
            server.state.collect { state ->
                if (state is ServerState.Running) connection.check()
            }
        }
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context)
            .components { add(NetworkFetcher.Factory(networkClient = { imageNetwork })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
            .build() }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(activity: Activity) {
                visible++
                scope.launch {
                    runCatching {
                        ready.await()
                        if (visible > 0) {
                            server.foreground(true)
                            if (settings.state.value.needsLocalService) {
                                try { LocalServerService.start(app) }
                                catch (_: RuntimeException) { server.backgroundError(app.uiString(R.string.boot_restricted)) }
                            }
                            connection.foreground(true)
                            phoneRemote.initialize()
                            runCatching { library.sync() }
                        }
                    }
                }
            }
            override fun onActivityStopped(activity: Activity) { visible--; if(visible == 0) { server.foreground(false); connection.foreground(false) } }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }
}

class TorrFrameApplication : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
