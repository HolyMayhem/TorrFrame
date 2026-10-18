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
