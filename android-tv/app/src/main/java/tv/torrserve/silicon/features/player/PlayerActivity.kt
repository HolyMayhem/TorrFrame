package tv.torrserve.silicon.features.player

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import tv.torrserve.silicon.TorrServeApplication
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import tv.torrserve.silicon.core.performance.Diagnostics
import tv.torrserve.silicon.core.storage.UiScale
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val began = Diagnostics.mark()
        lifecycleScope.launch { (application as TorrServeApplication).graph.playback.closePlayer.collect { finish() } }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = object : FrameLayout(this@PlayerActivity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleHiddenKey(event) || super.dispatchKeyEvent(event)
        }
        // Scale the controller's resource dimensions; the video surface stays full-screen.
        val scale = UiScale.factor((application as TorrServeApplication).graph.settings.state.value.uiScalePercent)
        val controlsContext = createConfigurationContext(Configuration(resources.configuration).apply {
            densityDpi = (resources.displayMetrics.densityDpi * scale).roundToInt()
        })
        playerView = PlayerView(controlsContext).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setShowSubtitleButton(true) }
        root.addView(playerView, FrameLayout.LayoutParams(-1,-1))
        status = TextView(controlsContext).apply {
            text = "Loading…"; setTextColor(Color.WHITE); setBackgroundColor(0xCC101114.toInt()); textSize = 18f
            val inset = (12 * controlsContext.resources.displayMetrics.density).roundToInt()
            setPadding(inset, inset, inset, inset)
        }
        root.addView(status); setContentView(root)
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync().also { f ->
            f.addListener({
                if(isDestroyed) return@addListener
                runCatching { f.get() }.onSuccess { player ->
                    controller = player; playerView.player = player
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { Diagnostics.elapsed("playback_first_frame", began) }
                        override fun onPlaybackStateChanged(state: Int) { status.text = when(state) { Player.STATE_BUFFERING -> "Buffering…"; Player.STATE_IDLE -> "Loading…"; else -> "" }; status.visibility = if(status.text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE }
                        override fun onPlayerError(error: PlaybackException) { status.visibility = android.view.View.VISIBLE; status.text = "Playback failed. The file, connection or codec may be unavailable. Press Back to try another file." }
                    })
                    val uri = intent.getStringExtra("stream") ?: return@onSuccess
                    val identity = intent.getStringExtra("identity") ?: return@onSuccess
                    if(player.currentMediaItem?.mediaId != identity) lifecycleScope.launch {
                        val graph = (application as TorrServeApplication).graph; graph.ready.await()
                        val split = identity.split(':'); val position = if(graph.settings.state.value.resume) graph.store.progress(split[0], split[1].toInt()) else 0
                        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(identity)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra("title")).build()).build(), position)
                        player.prepare(); player.play()
                    }
                    playerView.requestFocus()
                }.onFailure { status.text = "Could not start the player. Press Back to return." }
            }, ContextCompat.getMainExecutor(this))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { controller?.pause(); controller?.stop(); controller?.clearMediaItems(); stopService(Intent(this@PlayerActivity, PlaybackService::class.java)); finish() }
        })
    }
    private fun handleHiddenKey(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { controller?.seekBack(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { controller?.seekForward(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { playerView.showController(); return true }
            }
        }; return false
    }
    override fun onDestroy() { playerView.player = null; future?.let(MediaController::releaseFuture); controller = null; super.onDestroy() }
}
