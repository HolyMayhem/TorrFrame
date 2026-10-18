package tv.torrserve.silicon

import android.content.Intent
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import tv.torrserve.silicon.core.server.ServerState
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.features.player.*

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NativeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TorrFrameApplication
    @Test fun packagedExecutableApiPersistenceAndPlayback() = runBlocking {
        val graph = app.graph; graph.ready.await(); graph.server.requireRunning()
        assertTrue(graph.server.state.value is ServerState.Running)
        assertTrue(graph.api.version().contains("MatriX"))
        val pid = (graph.server.state.value as ServerState.Running).pid
        graph.server.start(); assertEquals(pid, (graph.server.state.value as ServerState.Running).pid)
        val bytes = instrumentation.context.assets.open("fixture.torrent").use { it.readBytes() }
        val t = graph.api.upload(bytes, "Original Gradient Test")
        var controller: MediaController? = null
        var activity: android.app.Activity? = null
        try {
            graph.store.put(t); graph.store.put(t)
            assertEquals(1, graph.store.list().count { it.hash == t.hash })
            val files = graph.library.files(t.hash)
            assertEquals(1,files.size); assertEquals(1,files.single().id)
            activity = instrumentation.startActivitySync(Intent(app, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("stream",graph.api.stream(t.hash,files.single()))
                .putExtra("identity","${t.hash}:1").putExtra("title","Original Gradient Test"))
            val connected = CompletableDeferred<MediaController>()
            instrumentation.runOnMainSync {
                val future = MediaController.Builder(app, SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()
                future.addListener({ connected.complete(future.get()) },ContextCompat.getMainExecutor(app))
            }
            controller = withTimeout(15_000) { connected.await() }
            withTimeout(60_000) {
                while(true) {
                    var position = 0L; var failure: androidx.media3.common.PlaybackException? = null
                    instrumentation.runOnMainSync { position = controller.currentPosition; failure = controller.playerError }
                    assertNull("Player error",failure)
                    if(position > 2000) break
                    delay(250)
                }
            }
            instrumentation.runOnMainSync { controller.pause() }
            delay(500)
            assertTrue(graph.store.progress(t.hash,1) > 1000)
            instrumentation.runOnMainSync { controller.play() }
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Exceed the server's 15-second idle grace period while playback owns it.
            delay(17_000)
            var playing = false
            instrumentation.runOnMainSync { playing = controller.isPlaying }
            assertTrue("Playback survives backgrounding",playing)
            assertTrue(graph.server.playbackOwned)
            assertEquals(pid,(graph.server.state.value as ServerState.Running).pid)
            // Simulate server-side record loss while keeping the local library row.
            instrumentation.runOnMainSync { controller.pause(); controller.stop(); controller.clearMediaItems() }
            graph.api.remove(t.hash)
            val restored = graph.library.add(tv.torrserve.silicon.features.search.SearchResult(
                t.hash,"Original Gradient Test","http://10.0.2.2:18765/fixture.torrent",t.hash,0,1,0,"Local fixture",0,setOf(2000)))
            assertEquals(t.hash,restored)
            assertTrue(graph.api.list().any { it.hash == t.hash })
            assertEquals(1,graph.store.list().count { it.hash == t.hash })
            assertTrue(graph.store.progress(t.hash,1) > 1000)
        } finally {
            instrumentation.runOnMainSync { controller?.pause(); controller?.stop(); controller?.clearMediaItems(); controller?.release(); activity?.finish() }
            app.stopService(Intent(app,PlaybackService::class.java)); delay(250)
            graph.api.remove(t.hash); graph.store.remove(t.hash)
        }
        assertFalse(graph.store.list().any { it.hash == t.hash })
    }
    @Test fun lateCheckpointDoesNotRecreateRemovedTitle() = runBlocking {
        val name = "player-checkpoint-test.sqlite"
        val store = tv.torrserve.silicon.core.database.LibraryStore(app, name)
        val hash = "d".repeat(40)
        try {
            store.put(Torrent(hash, "Checkpoint fixture", emptyList()))
            store.checkpoint(hash, 1, 5000, 30000)
            assertEquals(5000, store.progress(hash, 1))
            store.remove(hash)
            store.checkpoint(hash, 1, 6000, 30000)
            assertEquals(0, store.progress(hash, 1))
            assertTrue(store.list().isEmpty())
        } finally { store.close(); app.deleteDatabase(name) }
    }
    @Test fun credentialsAreEncryptedAndReloadable() = runBlocking {
        val original = app.graph.settings.state.value
        val store = SettingsStore(app)
        try {
            store.save(original.copy(jackettKey = "integration-private-key"))
            val disk = java.io.File(app.applicationInfo.dataDir,"shared_prefs/settings.xml").readText()
            assertFalse(disk.contains("integration-private-key"))
            val restored = SettingsStore(app); restored.load(); assertEquals("integration-private-key",restored.state.value.jackettKey)
        } finally { store.save(original) }
    }
    @Test fun metadataCacheAndHistorySurviveReopeningDatabase() = runBlocking {
        val context = instrumentation.targetContext
        val store = tv.torrserve.silicon.core.database.LibraryStore(context)
        val hash = "e".repeat(40)
        try {
            store.put(Torrent(hash,"Test",emptyList()))
            val meta = Metadata("TMDB","42","Test","Test",2024,MediaKind.Movie)
            store.metadata(hash,meta); store.cache("test-key",meta); store.cache("test-miss",null)
            store.checkpoint(hash,3,45_000,120_000); store.close()
            val reopened = tv.torrserve.silicon.core.database.LibraryStore(context)
            assertEquals("Test",reopened.list().find { it.hash==hash }?.metadata?.title)
            assertEquals(45_000,reopened.progress(hash,3))
            assertTrue(reopened.cached("test-miss").present); assertNull(reopened.cached("test-miss").value)
            assertEquals("42",reopened.cached("test-key").value?.id)
            reopened.remove(hash); reopened.clearCache(); reopened.close()
        } finally { runCatching { store.close() } }
    }
}
