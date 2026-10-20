package tv.torrserve.silicon

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.network.Http
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.silicon.features.library.LibraryRepository
import tv.torrserve.silicon.features.metadata.*
import tv.torrserve.silicon.features.search.JackettClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated real SQLite/Keystore work. The HTTP fixture never contacts the user's TorrServe. */
internal class StorageFixture {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val identity = "optimization-${UUID.randomUUID()}"
    val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$identity-$name", mode)
        override fun getFilesDir() = File(base.cacheDir, identity).apply { mkdirs() }
    }
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val settings = SettingsStore(context)
    val store = LibraryStore(context, "$identity.sqlite")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val listCalls = AtomicInteger()
    val versionCalls = AtomicInteger()
    var count = 100
    var listDelay = 250L
    var listGate: CountDownLatch? = null
    val listStarted = CountDownLatch(1)
    var listStatus = 200
    val http = Http(OkHttpClient.Builder().addInterceptor { chain ->
        val body = if (chain.request().url.encodedPath == "/echo") {
            versionCalls.incrementAndGet(); "MatriX.146.1"
        } else {
            listCalls.incrementAndGet(); listStarted.countDown()
            check(listGate?.await(5, TimeUnit.SECONDS) != false)
            Thread.sleep(listDelay)
            JSONArray((1..count).map { number -> JSONObject().put("hash", hash(number)).put("title", "Fixture $number")
                .put("file_stats", JSONArray().put(TorrentFile(1,"Fixture $number.mkv",1000).json())) }).toString()
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if(chain.request().url.encodedPath=="/echo")200 else listStatus).message("fixture")
            .body(body.toResponseBody()).build()
    }.build())
    val api = TorrServerApi(http) { settings.state.value.torrServerUrl }
    val repository = LibraryRepository(store, api, ServerController(context, api, scope, settings), JackettClient(http),
        MetadataResolver(object : MetadataSource {
            override suspend fun search(p: ParsedName, s: Settings, provider: String) = emptyList<MetadataMatch>()
            override suspend fun details(match: MetadataMatch, s: Settings) = match.value
        },store),settings,scope)
    suspend fun initialize() {
        settings.load()
        settings.update { it.copy(useLocalServer=false,torrServerUrl="http://fixture.example:8090",
            jackettKey="fixture-key",catalogNetwork=it.catalogNetwork.copy(cubToken="fixture-token")) }
    }
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        store.close(); context.deleteDatabase("$identity.sqlite")
        base.deleteSharedPreferences("$identity-settings")
        context.filesDir.deleteRecursively()
    }
    companion object { fun hash(number: Int) = number.toString(16).padStart(40,'0') }
}

@RunWith(AndroidJUnit4::class)
class StorageOptimizationProfileTest {
    @Test fun measureIsolatedStorageWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("optimizationProfile") == "true")
        val fixture=StorageFixture()
        try {
            fixture.initialize()
            val gate=CompletableDeferred<Unit>()
            val before=fixture.store.changes.value
            val began=SystemClock.elapsedRealtimeNanos()
            coroutineScope {
                val calls=List(4) { async(start=CoroutineStart.UNDISPATCHED) { gate.await();fixture.repository.sync() } }
                gate.complete(Unit);calls.awaitAll()
            }
            println("Optimization sync: rows=${fixture.store.list().size}; listCalls=${fixture.listCalls.get()}; versionCalls=${fixture.versionCalls.get()}; notifications=${fixture.store.changes.value-before}; elapsedMs=${(SystemClock.elapsedRealtimeNanos()-began)/1_000_000.0}")
            assertEquals(100,fixture.store.list().size)
            val oldCalls=fixture.listCalls.get()
            fixture.repository.sync()
            assertEquals(oldCalls+1,fixture.listCalls.get()) // Explicit later refresh still contacts the server.
            repeat(3) { round ->
                var rewrittenSecrets=0
                val start=SystemClock.elapsedRealtimeNanos()
                repeat(20) {
                    val secret=fixture.prefs.getString("jackettKey","")
                    fixture.settings.update { it.copy(uiScalePercent=if(it.uiScalePercent==85)90 else 85) }
                    if(secret!=fixture.prefs.getString("jackettKey",""))rewrittenSecrets++
                }
                println("Optimization settings: round=$round; writes=20; rewrittenSecrets=$rewrittenSecrets; elapsedMs=${(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0}")
            }
            val reopened=SettingsStore(fixture.context);reopened.load()
            assertEquals(fixture.settings.state.value,reopened.state.value)
            assertEquals(fixture.settings.revision.value,reopened.revision.value)
        } finally { fixture.close() }
    }
}
