package tv.torrserve.silicon.features.player

import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import tv.torrserve.silicon.TorrServeApplication

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as TorrServeApplication).graph
    override fun onCreate() {
        super.onCreate(); graph.server.playbackOwned = true
        val s = graph.settings.state.value
        val source = DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(60_000))
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(source).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(s.audioLanguage.ifBlank { null })
                .setPreferredTextLanguage(s.subtitleLanguage.ifBlank { null }).build()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) checkpoint() }
            })
        }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                if (controller.packageName == packageName || controller.isTrusted)
                    super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
        }).build()
        scope.launch { graph.ready.await(); graph.server.requireRunning() }
        scope.launch { while (isActive) { delay(30_000); checkpoint() } }
    }
    private fun checkpoint() {
        val player = session?.player ?: return
        val identity = player.currentMediaItem?.mediaId?.split(':') ?: return
        val hash = identity.getOrNull(0) ?: return; val file = identity.getOrNull(1)?.toIntOrNull() ?: return
        val position = player.currentPosition; val duration = player.duration.coerceAtLeast(0)
        graph.scope.launch { graph.store.checkpoint(hash, file, position, duration) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        checkpoint(); session?.player?.release(); session?.release(); session = null
        scope.cancel(); graph.server.playbackOwned = false; graph.server.scheduleIdleStop()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!isPlaybackOngoing) { checkpoint(); stopSelf() } }
}
