package tv.torrserve.silicon

import android.app.Application
import kotlinx.coroutines.*
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
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import android.app.Activity
import android.os.Bundle

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ready = CompletableDeferred<Unit>()
    val http = Http()
    val settings = SettingsStore(app)
    val serverWebAddress = ServerWebAddressMonitor(app, settings, scope)
    val store = LibraryStore(app)
    val api = TorrServerApi(http) { settings.state.value.torrServerUrl }
    private val localApi = TorrServerApi(http) { "http://127.0.0.1:${settings.state.value.port}" }
    val connection = ServerConnection(api, settings.state, scope)
    val server = ServerController(app, localApi, scope, settings)
    val jackett = JackettClient(http)
    val metadataProviders = MetadataProviders(http)
    val metadata = MetadataResolver(metadataProviders, store)
    val phonePreferences = tv.torrserve.silicon.phoneRemote.settings.PhoneRemotePreferences(app)
    val serverActions = TorrServerActions(app, settings, server, api, connection, scope) { phonePreferences.state.value.takeIf { it.enabled }?.port }
    val updates = tv.torrserve.silicon.features.updates.UpdateManager(app, this)
    val metadataEditor = MetadataEditor(store, metadataProviders, settings)
    val playback = tv.torrserve.silicon.features.player.PlaybackCoordinator(app) { server.foreground }
    val library = LibraryRepository(store, api, server, jackett, metadata, settings, scope)
    val phoneRemote by lazy { tv.torrserve.silicon.phoneRemote.server.PhoneRemoteManager(this) }
    init {
        scope.launch {
            try {
                settings.load(); ready.complete(Unit)
                if (settings.state.value.startOnBoot && settings.state.value.useLocalServer) {
                    try { LocalServerService.start(app) }
                    catch (_: RuntimeException) { server.backgroundError(app.getString(tv.torrserve.silicon.R.string.boot_restricted)) }
                }
            }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                server.backgroundError(app.getString(tv.torrserve.silicon.R.string.settings_load_failed))
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.client })) }
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
                                catch (_: RuntimeException) { server.backgroundError(app.getString(R.string.boot_restricted)) }
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

class TorrServeApplication : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
