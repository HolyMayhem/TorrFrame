package tv.torrserve.silicon.features.player

import android.content.Context
import tv.torrserve.silicon.core.localization.withUiLanguage
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import tv.torrserve.silicon.TorrFrameApplication

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as TorrFrameApplication).graph
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.withUiLanguage()) }
    override fun onCreate() {
        super.onCreate(); graph.server.playbackOwned = true
        val s = graph.settings.state.value
        val source = DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(60_000)))
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(if (s.builtInPlayer.decoder == AudioDecoder.Ffmpeg)
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(source).setHandleAudioBecomingNoisy(true).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) {
                    android.util.Log.i("TorrFramePlayback", "Audio decoder: $decoderName")
                }
            })
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(s.audioLanguage.ifBlank { null })
                .setPreferredTextLanguage(s.subtitleLanguage.ifBlank { null }).build()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) checkpoint() }
            })
        }
        graph.playback.attach(player, ::checkpoint)
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                if (controller.packageName == packageName || controller.isTrusted)
                    super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
        }).build()
        scope.launch { try { graph.ready.await(); graph.server.requireRunning() } catch (e: Exception) { if (e is CancellationException) throw e; stopSelf() } }
        scope.launch {
            graph.settings.state.map { it.builtInPlayer }.distinctUntilChanged().collect { options ->
                player.setPlaybackSpeed(options.speed)
                player.skipSilenceEnabled = options.skipSilence
                player.setVideoChangeFrameRateStrategy(if (options.matchFrameRate)
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            }
        }
        scope.launch { while (isActive) { delay(30_000); checkpoint() } }
    }
    private fun checkpoint() {
        val player = session?.player ?: return
        val identity = player.currentMediaItem?.mediaId?.split(':') ?: return
        val hash = identity.getOrNull(0) ?: return
        // Only library torrent identities have a history row. Preview/invalid media
        // IDs must not cause a foreign-key failure when controls stop the session.
        if (!hash.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) return
        val file = identity.getOrNull(1)?.toIntOrNull() ?: return
        val position = player.currentPosition; val duration = player.duration.coerceAtLeast(0)
        graph.scope.launch { graph.store.checkpoint(hash, file, position, duration) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        checkpoint(); graph.playback.detach(); session?.player?.release(); session?.release(); session = null
        scope.cancel(); graph.server.playbackOwned = false; graph.server.scheduleIdleStop()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!isPlaybackOngoing) { checkpoint(); stopSelf() } }
}
