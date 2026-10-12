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

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ready = CompletableDeferred<Unit>()
    val http = Http()
    val settings = SettingsStore(app)
    val store = LibraryStore(app)
    val api = TorrServerApi(http) { settings.state.value.port }
    val server = ServerController(app, api, scope, { settings.state.value.port }, { settings.state.value.cacheMb })
    val jackett = JackettClient(http)
    val metadata = MetadataResolver(MetadataProviders(http), store)
    val library = LibraryRepository(store, api, server, jackett, metadata, settings, scope)
    init {
        scope.launch {
            try { settings.load(); ready.complete(Unit) }
            catch (e: Exception) { ready.completeExceptionally(IllegalStateException("Could not load encrypted settings.")) }
        }
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
            .build() }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(activity: Activity) { visible++; scope.launch { runCatching { ready.await(); if(visible > 0) server.foreground(true) } } }
            override fun onActivityStopped(activity: Activity) { visible--; if(visible == 0) server.foreground(false) }
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
