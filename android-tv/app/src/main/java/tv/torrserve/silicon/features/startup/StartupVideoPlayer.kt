package tv.torrserve.silicon.features.startup

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import tv.torrserve.silicon.BuildConfig
import tv.torrserve.silicon.R

/** One local, muted, hardware-only player. Independent of the movie playback service. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StartupVideoPlayer(private val state: StartupSplashState, private val mediaUri: Uri? = null) {
    private var player: ExoPlayer? = null
    private var texture: TextureView? = null
    private var aspect: AspectRatioFrameLayout? = null
    private var released = false
    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0)
                aspect?.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
        }
        override fun onRenderedFirstFrame() { state.firstVideoFrame() }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) complete(false)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (BuildConfig.DEBUG) Log.w("TorrFrameStartup", "video_error_code=${error.errorCode}")
            complete(true)
        }
    }
    fun prepare(context: Context, target: View, play: Boolean) {
        if (!state.claimVideo()) { state.videoEnded(); return }
        try {
            texture = target.findViewById(R.id.startup_video_texture)
            aspect = target.findViewById(R.id.startup_video_aspect)
            val renderers = DefaultRenderersFactory(context.applicationContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { it.hardwareAccelerated }
                }
            val p = ExoPlayer.Builder(context.applicationContext, renderers).build()
            player = p
            p.addListener(listener)
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) {
                    state.decodedBy(decoderName)
                    if (BuildConfig.DEBUG) Log.i("TorrFrameStartup", "hardware_decoder=$decoderName")
                }
            })
            p.volume = 0f
            p.setAudioAttributes(AudioAttributes.DEFAULT, false)
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            // No audio focus, media session, playback service, or movie preferences are involved.
            p.setVideoTextureView(requireNotNull(texture))
            p.setMediaItem(MediaItem.fromUri(mediaUri ?: "android.resource://${context.packageName}/${R.raw.welcome_clip}".toUri()))
            p.createMessage { _, _ -> if (!released) state.checkReadinessAt1900ms() }
                .setLooper(Looper.getMainLooper()).setPosition(StartupSplashState.READINESS_CHECK_MS).send()
            p.createMessage { _, _ ->
                if (!released && state.freezeAt2000ms()) {
                    p.pause()
                    // Hold the video itself at two seconds, including on a busy UI thread.
                    p.seekTo(StartupSplashState.FREEZE_POSITION_MS)
                }
            }.setLooper(Looper.getMainLooper()).setPosition(StartupSplashState.FREEZE_POSITION_MS).send()
            p.prepare()
            p.playWhenReady = play
        } catch (_: Exception) { complete(true) }
    }
    fun play() { if (!released) runCatching { if (state.state.value.phase == StartupPhase.Frozen) player?.pause() else player?.play() }.onFailure { complete(true) } }
    private fun complete(failed: Boolean) { release(); state.videoEnded(failed) }
    fun release() {
        if (released) return
        released = true
        val p = player; player = null
        if (p != null) {
            runCatching { p.removeListener(listener) }
            runCatching { texture?.let(p::clearVideoTextureView) }
            runCatching { p.release() }
            if (BuildConfig.DEBUG) Log.i("TorrFrameStartup", "video_player_released")
        }
        texture = null; aspect = null
    }
}
