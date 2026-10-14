package tv.torrserve.silicon.features.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.server.ApplicationActionFailure

data class PlaybackTrack(val id: String, val type: String, val label: String, val selected: Boolean)
data class PlaybackSnapshot(val active: Boolean = false, val identity: String = "", val title: String = "",
    val playing: Boolean = false, val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val tracks: List<PlaybackTrack> = emptyList(), val error: Boolean = false)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCoordinator(private val context: Context, private val visible: () -> Boolean) {
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
        val tracks = p.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type !in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) emptyList()
            else (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val format = group.getTrackFormat(index)
                PlaybackTrack("$groupIndex:$index", if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "subtitle",
                    format.label ?: format.language ?: "${index + 1}", group.isTrackSelected(index))
            }
        }
        mutable.value = PlaybackSnapshot(p.currentMediaItem != null, p.currentMediaItem?.mediaId.orEmpty(),
            p.mediaMetadata.title?.toString().orEmpty(), p.isPlaying, p.playbackState == Player.STATE_BUFFERING,
            p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), tracks, p.playerError != null)
    }
    suspend fun command(type: String, position: Long? = null, track: String? = null, identity: String? = null) = withContext(Dispatchers.Main.immediate) {
        val p = player ?: throw ApplicationActionFailure(R.string.phone_no_playback)
        if (identity != null && p.currentMediaItem?.mediaId != identity) throw ApplicationActionFailure(R.string.phone_playback_changed)
        when (type) {
            "play" -> p.play()
            "pause" -> p.pause()
            "stop" -> { checkpoint?.invoke(); p.pause(); p.stop(); p.clearMediaItems(); closed.tryEmit(Unit); context.stopService(Intent(context, PlaybackService::class.java)) }
            "seek" -> { require(position != null && position >= 0); p.seekTo(position.coerceAtMost(p.duration.coerceAtLeast(0))) }
            "track" -> {
                if (track == "subtitle:off") p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                else {
                    val parts = requireNotNull(track).split(':').map { it.toInt() }; require(parts.size == 2)
                    val group = p.currentTracks.groups[parts[0]]; val index = parts[1]
                    require(group.type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT) && index in 0 until group.length && group.isTrackSupported(index))
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(group.type)
                        .setTrackTypeDisabled(group.type, false).addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                }
            }
            else -> error("Unknown playback command")
        }
        refresh()
    }
    suspend fun open(stream: String, identity: String, title: String) = withContext(Dispatchers.Main.immediate) {
        if (!visible()) throw ApplicationActionFailure(R.string.phone_open_tv)
        context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stream", stream).putExtra("identity", identity).putExtra("title", title))
    }
}
