package tv.torrframe.features.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrframe.R
import tv.torrframe.core.server.ApplicationActionFailure

data class PlaybackTrack(val id: String, val type: String, val label: String, val selected: Boolean)
data class PlaybackSnapshot(val active: Boolean = false, val identity: String = "", val title: String = "",
    val playing: Boolean = false, val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val tracks: List<PlaybackTrack> = emptyList(), val error: Boolean = false,
    val playWhenReady: Boolean = false, val ended: Boolean = false, val seekable: Boolean = false)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCoordinator(private val context: Context, private val settings: () -> tv.torrframe.core.storage.Settings,
    private val visible: () -> Boolean) {
    private var player: Player? = null
    private var checkpoint: (() -> Unit)? = null
    private val mutable = MutableStateFlow(PlaybackSnapshot())
    val state = mutable.asStateFlow()
    private val closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closePlayer = closed.asSharedFlow()
    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { refresh() } }
    fun attach(player: Player, checkpoint: () -> Unit) { this.player = player; this.checkpoint = checkpoint; player.addListener(listener); refresh() }
    fun detach() { player?.removeListener(listener); player = null; checkpoint = null; mutable.value = PlaybackSnapshot() }
    fun refresh() {
        val p = player ?: return
        val tracks = p.playbackTracks()
        mutable.value = PlaybackSnapshot(p.currentMediaItem != null, p.currentMediaItem?.mediaId.orEmpty(),
            p.mediaMetadata.title?.toString().orEmpty(), p.isPlaying, p.playbackState == Player.STATE_BUFFERING,
            p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), tracks, p.playerError != null, p.playWhenReady, p.playbackState == Player.STATE_ENDED, p.isCurrentMediaItemSeekable)
    }
    suspend fun command(type: String, position: Long? = null, track: String? = null, identity: String? = null) = withContext(Dispatchers.Main.immediate) {
        val p = player ?: throw ApplicationActionFailure(R.string.phone_no_playback)
        if (identity != null && p.currentMediaItem?.mediaId != identity) throw ApplicationActionFailure(R.string.phone_playback_changed)
        when (type) {
            "play" -> { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            "pause" -> p.pause()
            "stop" -> { checkpoint?.invoke(); p.pause(); p.stop(); p.clearMediaItems(); closed.tryEmit(Unit); context.stopService(Intent(context, PlaybackService::class.java)) }
            "seek" -> { require(p.isCurrentMediaItemSeekable && position != null && position >= 0); p.seekTo(position.coerceAtMost(p.duration.coerceAtLeast(0))) }
            "track" -> p.selectPlaybackTrack(track)
            else -> error("Unknown playback command")
        }
        refresh()
    }
    suspend fun open(stream: String, identity: String, title: String) = withContext(Dispatchers.Main.immediate) {
        if (!visible()) throw ApplicationActionFailure(R.string.phone_open_tv)
        val activity = if (settings().playerMode == PlayerMode.Internal) PlayerActivity::class.java else PlayerChoiceActivity::class.java
        context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stream", stream).putExtra("identity", identity).putExtra("title", title))
    }
    fun stopInternal() {
        checkpoint?.invoke()
        player?.pause(); player?.stop(); player?.clearMediaItems()
        closed.tryEmit(Unit)
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
