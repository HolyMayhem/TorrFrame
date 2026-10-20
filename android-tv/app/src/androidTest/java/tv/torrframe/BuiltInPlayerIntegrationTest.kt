package tv.torrframe

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrframe.core.models.Torrent
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.storage.SettingsStore
import tv.torrframe.core.storage.UiAccent
import tv.torrframe.features.player.*
import java.io.File
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BuiltInPlayerIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TorrFrameApplication
    private val graph get() = app.graph
    private lateinit var original: Settings
    private var activity: android.app.Activity? = null
    private var media: MediaController? = null
    private val hash = "c".repeat(40)
    @Before fun setup() = runBlocking {
        graph.ready.await(); original = graph.settings.state.value
        graph.settings.update { it.copy(uiLanguage = "en", animationsEnabled = false, resume = true,
            builtInPlayer = PlayerPreferences(decoder = AudioDecoder.Ffmpeg)) }
        graph.store.put(Torrent(hash, "Player test", emptyList()))
        val file = File(app.cacheDir, "player-multitrack.mkv")
        instrumentation.context.assets.open("player-multitrack.mkv").use { input -> file.outputStream().use(input::copyTo) }
        launch(file)
    }
    private suspend fun launch(file: File) {
        activity = instrumentation.startActivitySync(Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stream", android.net.Uri.fromFile(file).toString()).putExtra("identity", "$hash:1").putExtra("title", "Player test"))
        val connected = CompletableDeferred<MediaController>()
        instrumentation.runOnMainSync {
            val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            future.addListener({ connected.complete(future.get()) }, ContextCompat.getMainExecutor(app))
        }
        media = withTimeout(15000) { connected.await() }
        withTimeout(30000) {
            while (true) {
                var ready = false
                instrumentation.runOnMainSync { assertNull(media!!.playerError); ready = media!!.currentPosition > 1000 && media!!.isPlaying }
                if (ready) break
                delay(100)
            }
        }
        instrumentation.runOnMainSync { media!!.pause() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("player:play").fetchSemanticsNodes().isNotEmpty() }
        // The Activity owns a different MediaController; wait for its pause acknowledgement too.
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("player:play") and hasText("Play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @After fun teardown() = runBlocking {
        graph.developer.showDiagnostics(false)
        instrumentation.runOnMainSync { media?.release(); media = null; graph.playback.stopInternal(); activity?.finish() }
        delay(300)
        graph.store.remove(hash)
        if (::original.isInitialized) graph.settings.save(original)
    }
    private fun remote(key: Int) { instrumentation.sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focus(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle() }
    private fun selected(type: Int, label: String): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = media!!.currentTracks.groups.any { group -> group.type == type && (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).label == label } } }
        return found
    }
    @Test fun diagnosticsStayBelowVideoAndKeepPlayerControlsFocused() {
        instrumentation.runOnMainSync { graph.developer.showDiagnostics(true) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("developer:diagnostics").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("developer:diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("player:play").assertIsFocused()
        val panel=compose.onNodeWithTag("developer:diagnostics").fetchSemanticsNode().boundsInRoot
        val player=compose.onNodeWithTag("player:screen").fetchSemanticsNode().boundsInRoot
        assertTrue("Diagnostics covers video or transport controls",panel.top>=player.bottom)
        remote(KeyEvent.KEYCODE_DPAD_LEFT);compose.onNodeWithTag("player:rewind").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("player:play").assertIsFocused()
        instrumentation.runOnMainSync { graph.developer.showDiagnostics(false) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("developer:diagnostics").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("player:play").assertIsFocused()
    }
    @Test fun ffmpegLoadsAndBothAudioAndSubtitleTracksCanBeSelectedWithRemote() {
        assertTrue(FfmpegLibrary.isAvailable())
        listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS).forEach { assertTrue(it, FfmpegLibrary.supportsFormat(it)) }
        compose.onNodeWithTag("player:play").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_LEFT); compose.onNodeWithTag("player:rewind").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("player:play").assertIsFocused()
        focus("player:audio"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player:menu:Audio").assertExists()
        remote(KeyEvent.KEYCODE_DPAD_DOWN); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { selected(C.TRACK_TYPE_AUDIO, "Russian") }
        remote(KeyEvent.KEYCODE_BACK); compose.onNodeWithTag("player:audio").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player:subtitle:off").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        var textSelected = false
        instrumentation.runOnMainSync { textSelected = media!!.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
        assertTrue(textSelected)
        focus("player:subtitle:off"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.runOnMainSync { assertTrue(media!!.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) }
    }
    @Test fun externalSubtitlesKeepIdentityPauseAndProgress() = runBlocking {
        val server = ServerSocket(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val body = "1\n00:00:00,000 --> 00:00:29,000\nExternal subtitle test\n".toByteArray()
        val task = scope.launch { while (isActive) runCatching { server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) { }
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
        } } }
        try {
            instrumentation.runOnMainSync { media!!.seekTo(10_000) }
            focus("player:subtitles"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
            focus("player:subtitle:url"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("dialog:editor").performTextInput("http://127.0.0.1:${server.localPort}/external.srt")
            compose.onNodeWithTag("dialog:done").performClick()
            compose.waitUntil(10000) { selected(C.TRACK_TYPE_TEXT, "external.srt") }
            instrumentation.runOnMainSync {
                assertEquals("$hash:1", media!!.currentMediaItem!!.mediaId)
                assertFalse(media!!.playWhenReady)
                assertTrue(media!!.currentPosition in 9500..10500)
            }
        } finally { task.cancel(); server.close(); scope.cancel() }
    }
    @Test fun speedScalingAndSubtitleAppearancePersist() = runBlocking {
        focus("player:options"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("player:speed"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("player:speed:1.5"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { graph.settings.state.value.builtInPlayer.speed == 1.5f }
        compose.waitUntil(5000) {
            var applied = false
            instrumentation.runOnMainSync { applied = media!!.playbackParameters.speed == 1.5f }
            applied
        }
        instrumentation.runOnMainSync { assertEquals(1.5f, media!!.playbackParameters.speed) }
        remote(KeyEvent.KEYCODE_BACK)
        focus("player:aspect"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("player:aspect:Zoom"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        remote(KeyEvent.KEYCODE_BACK); remote(KeyEvent.KEYCODE_BACK)
        focus("player:subtitles"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("player:subtitle:style"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focus("player:subtitle:larger"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { graph.settings.state.value.builtInPlayer.subtitleSize == 125 }
        val store = SettingsStore(app); store.load()
        assertEquals(1.5f, store.state.value.builtInPlayer.speed)
        assertEquals(VideoAspect.Zoom, store.state.value.builtInPlayer.aspect)
        assertEquals(125, store.state.value.builtInPlayer.subtitleSize)
    }
    @Test fun themesAccentAndScaleUseApplicationSettings() = runBlocking {
        var smallHeight = 0f
        for ((index, appearance) in listOf("dark" to "green", "light" to "blue", "light" to "white", "dark" to "pink").withIndex()) {
            graph.settings.update { it.copy(uiTheme = appearance.first, uiAccent = appearance.second, uiScalePercent = if (index == 0) 70 else 110) }
            compose.waitForIdle(); focus("player:play")
            val node = compose.onNodeWithTag("player:play")
            val pixels = node.captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, 5]
            val expected = Color(UiAccent.colors.getValue(appearance.second))
            assertEquals(expected.red, actual.red, .06f); assertEquals(expected.green, actual.green, .06f); assertEquals(expected.blue, actual.blue, .06f)
            val height = node.fetchSemanticsNode().boundsInRoot.height
            if (index == 0) smallHeight = height else assertTrue("UI scales using the global setting", height > smallHeight * 1.3)
        }
    }
    @Test fun hiddenControlsRespondToRemoteWithoutLosingFocusOrPausing() = runBlocking {
        focus("player:play"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) {
            var playing=false
            instrumentation.runOnMainSync { playing=media!!.isPlaying }
            playing
        }
        compose.mainClock.advanceTimeBy(5500)
        compose.waitUntil(8000) { compose.onAllNodesWithTag("player:hidden").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player:hidden").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("player:play").assertIsFocused()
        instrumentation.runOnMainSync { assertTrue(media!!.playWhenReady) }
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("player:seek").assertIsFocused()
        var before=0L
        instrumentation.runOnMainSync { before=media!!.currentPosition }
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        // Compose's virtual idle time does not advance the native decoder's media clock.
        compose.waitUntil(5000) {
            var moved=false
            instrumentation.runOnMainSync { moved=media!!.currentPosition>=before+9500 }
            moved
        }
    }
    @Test fun playbackErrorKeepsRetryFocusedAndBackExits() {
        instrumentation.runOnMainSync { media!!.setMediaItem(MediaItem.fromUri("file:///does-not-exist.mp4")); media!!.prepare() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("player:retry").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("player:retry").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { activity?.isFinishing == true || activity?.isDestroyed == true }
    }
    @Test fun returnSavesProgressAndNextSessionResumes() = runBlocking {
        instrumentation.runOnMainSync { media!!.seekTo(12_000) }
        delay(500)
        focus("player:back"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        withTimeout(5000) { while (graph.store.progress(hash, 1) < 11000) delay(100) }
        instrumentation.runOnMainSync { media?.release(); media = null }
        delay(500)
        launch(File(app.cacheDir, "player-multitrack.mkv"))
        instrumentation.runOnMainSync { assertTrue(media!!.currentPosition > 11000) }
    }
    @Test fun phoneRemoteUsesPlayIntentDuringBufferingAndRestartsEndedVideo() = runBlocking {
        graph.playback.command("play", identity = "$hash:1")
        instrumentation.runOnMainSync {
            graph.playback.refresh()
            assertTrue(graph.playback.state.value.playWhenReady)
            media!!.seekTo(media!!.duration)
            media!!.play()
        }
        withTimeout(10_000) {
            while (true) {
                var ended = false
                instrumentation.runOnMainSync { ended = media!!.playbackState == androidx.media3.common.Player.STATE_ENDED }
                if (ended) break
                delay(50)
            }
        }
        graph.playback.command("play", identity = "$hash:1")
        withTimeout(10_000) {
            while (true) {
                var resumed = false
                instrumentation.runOnMainSync { resumed = media!!.currentPosition < 5000 && media!!.isPlaying }
                if (resumed) break
                delay(50)
            }
        }
        graph.playback.command("pause", identity = "$hash:1")
        instrumentation.runOnMainSync { assertFalse(media!!.playWhenReady) }
    }
}
