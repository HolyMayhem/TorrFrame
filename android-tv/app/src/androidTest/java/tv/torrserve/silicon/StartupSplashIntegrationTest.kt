package tv.torrserve.silicon

import android.net.Uri
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import tv.torrserve.silicon.features.startup.*

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@androidx.annotation.OptIn(UnstableApi::class)
class StartupSplashIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as TorrFrameApplication).graph

    @Test fun aColdLaunchRevealsPreparedUiAndOnlyUsesHardwareVideoDecoders() {
        val splash = graph.startupSplash
        compose.waitUntil(15_000) { splash.state.value.phase == StartupPhase.Finished }
        compose.onNodeWithTag("startup:cover").assertDoesNotExist()
        compose.onNodeWithTag("app:screen").assertIsDisplayed()
        compose.onNodeWithTag("nav:Settings").assertIsDisplayed()
        compose.onNodeWithTag("startup:preparing").assertDoesNotExist()
        assertTrue(graph.ready.isCompleted)
        val frame = splash.state.value
        if (!frame.videoFailed) {
            assertTrue("Video did not render", frame.renderedVideo)
            val hardware = MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
                .filter { it.hardwareAccelerated }.map { it.name }
            assertTrue("A software decoder was used: ${frame.decoder}", frame.decoder in hardware)
        }
    }
    @Test fun bUnreadyContentStaysCompletelyBlackUntilLocalInitializationSettles() {
        val splash = StartupSplashState(SystemClock::elapsedRealtime).apply { begin(); videoEnded() }
        val ready = mutableStateOf(false)
        compose.activity.setContent {
            StartupSplash(splash, ready.value, close = {}, revealed = {}) {
                Box(Modifier.fillMaxSize().background(Color.White))
            }
        }
        SystemClock.sleep(1_200)
        assertEquals(StartupPhase.Black, splash.state.value.phase)
        val pixel = compose.onRoot().captureToImage().toPixelMap()[10, 10]
        assertEquals(0f, pixel.red, .001f); assertEquals(0f, pixel.green, .001f); assertEquals(0f, pixel.blue, .001f)
        compose.runOnUiThread { ready.value = true }
        compose.waitUntil(5_000) { splash.state.value.phase == StartupPhase.Finished }
        compose.onNodeWithTag("startup:cover").assertDoesNotExist()
        val revealed = compose.onRoot().captureToImage().toPixelMap()[10, 10]
        assertEquals(1f, revealed.red, .001f)
    }
    @Test fun dVideoFrameHoldsWithoutChangingSurfaceOrPixelsThenContinuesTheClip() {
        val splash = StartupSplashState(SystemClock::elapsedRealtime)
        val ready = mutableStateOf(false)
        compose.activity.setContent {
            StartupSplash(splash, ready.value, close = {}, revealed = {}) {
                Box(Modifier.fillMaxSize().background(Color.White))
            }
        }
        compose.waitUntil(6_000) { splash.state.value.renderedVideo || splash.state.value.videoFailed }
        assertFalse("Hardware clip failed", splash.state.value.videoFailed)
        lateinit var originalTexture: TextureView
        compose.runOnUiThread { originalTexture = compose.activity.findViewById(R.id.startup_video_texture) }
        compose.waitUntil(6_000) { splash.state.value.phase == StartupPhase.Frozen || splash.state.value.videoFailed }
        assertFalse("Hardware clip failed during hold", splash.state.value.videoFailed)
        compose.onNodeWithTag("startup:freeze").assertIsDisplayed()
        val decoder = splash.state.value.decoder
        compose.onNodeWithTag("startup:preparing").assertDoesNotExist()
        // Let the seek to exactly 2 s settle before comparing the actual decoded surface.
        SystemClock.sleep(300)
        lateinit var held: android.graphics.Bitmap
        compose.runOnUiThread {
            assertSame(originalTexture, compose.activity.findViewById(R.id.startup_video_texture))
            held = requireNotNull(originalTexture.bitmap)
        }
        val pixels = IntArray(held.width * held.height)
        held.getPixels(pixels, 0, held.width, 0, 0, held.width, held.height)
        assertTrue("Frozen video surface is empty", pixels.any { android.graphics.Color.red(it) > 100 })
        SystemClock.sleep(6_200)
        compose.runOnUiThread {
            assertSame(originalTexture, compose.activity.findViewById(R.id.startup_video_texture))
            val later = requireNotNull(originalTexture.bitmap)
            try { assertTrue("Frozen pixels or brightness changed", held.sameAs(later)) }
            finally { held.recycle(); later.recycle() }
        }
        assertEquals(StartupPhase.Frozen, splash.state.value.phase)
        compose.onNodeWithTag("startup:freeze").assertIsDisplayed()
        // Wall-clock sleep above exercises the real player. Advance Compose's coroutine
        // test clock too, so its delayed hint can observe the elapsed monotonic deadline.
        compose.mainClock.advanceTimeBy(StartupSplashState.LOADING_HINT_DELAY_MS)
        compose.onNodeWithTag("startup:preparing").assertIsDisplayed()
        compose.onNodeWithTag("startup:preparing").assertTextEquals(
            if (graph.settings.state.value.uiLanguage == "ru") "Подготавливаем приложение…" else "Preparing the app…")
        compose.runOnUiThread {
            ready.value = true
            assertSame(originalTexture, compose.activity.findViewById(R.id.startup_video_texture))
        }
        compose.waitUntil(2_000) { splash.state.value.phase != StartupPhase.Frozen }
        compose.onNodeWithTag("startup:freeze").assertDoesNotExist()
        compose.onNodeWithTag("startup:preparing").assertDoesNotExist()
        compose.waitUntil(6_000) { splash.state.value.phase == StartupPhase.Finished }
        assertEquals(decoder, splash.state.value.decoder)
        assertFalse(splash.state.value.videoFailed)
        compose.onNodeWithTag("startup:video").assertDoesNotExist()
    }
    @Test fun cMissingClipFailsOpenAndDetachesItsVideoSurface() {
        val splash = StartupSplashState(SystemClock::elapsedRealtime).apply { begin() }
        val playback = StartupVideoPlayer(splash, Uri.parse("android.resource://tv.torrserve.silicon/raw/missing_startup_clip"))
        lateinit var texture: TextureView
        compose.runOnUiThread {
            @Suppress("InflateParams") val view = LayoutInflater.from(compose.activity).inflate(R.layout.startup_video, null)
            texture = view.findViewById(R.id.startup_video_texture)
            playback.prepare(compose.activity, view, true)
        }
        compose.waitUntil(6_000) { splash.state.value.phase == StartupPhase.Black }
        assertTrue(splash.state.value.videoFailed)
        compose.runOnUiThread { assertNull(texture.surfaceTextureListener); playback.release(); playback.release() }
    }
}
