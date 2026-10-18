package tv.torrserve.silicon.features.player

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A presentation adapter for the service's MediaController. Does not own an ExoPlayer. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerController {
    private var player: MediaController? = null
    private val mutable = MutableStateFlow(PlayerUiState())
    val state = mutable.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }
    fun attach(controller: MediaController) { player = controller; controller.addListener(listener); refresh() }
    fun detach() { player?.removeListener(listener); player = null }
    fun failed() { mutable.value = mutable.value.copy(error = true) }
    fun refresh() {
        val p = player ?: return
        val tracks = p.playbackTracks()
        val video = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
        mutable.value = PlayerUiState(connected = true, title = p.mediaMetadata.title?.toString().orEmpty(),
            playing = p.isPlaying, playWhenReady = p.playWhenReady,
            buffering = p.playbackState in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING),
            ended = p.playbackState == Player.STATE_ENDED, seekable = p.isCurrentMediaItemSeekable,
            position = p.currentPosition.coerceAtLeast(0), duration = p.duration.coerceAtLeast(0),
            buffered = p.bufferedPosition.coerceAtLeast(0), tracks = tracks, error = p.playerError != null,
            frameRate = video?.frameRate ?: -1f,
            videoInfo = video?.let { "${it.width} × ${it.height} · ${it.frameRate.takeIf { f -> f > 0 }?.let { f -> "%.3f fps".format(Locale.ROOT, f) }.orEmpty()} · ${it.sampleMimeType?.substringAfterLast('/').orEmpty()}" }.orEmpty())
    }
    fun toggle() { player?.let { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }; refresh() }
    fun seekBy(delta: Long) { player?.let { if (it.isCurrentMediaItemSeekable) it.seekTo((it.currentPosition + delta).coerceIn(0, it.duration.coerceAtLeast(0))) }; refresh() }
    fun seekTo(position: Long) { player?.let { if (it.isCurrentMediaItemSeekable) it.seekTo(position.coerceIn(0, it.duration.coerceAtLeast(0))) }; refresh() }
    fun retry() { player?.prepare(); player?.play(); refresh() }
    fun track(id: String?) {
        val p = player ?: return
        p.selectPlaybackTrack(id)
        refresh()
    }
    /** Replace only the subtitle attachment, preserving the stream, identity, progress and pause state. */
    fun subtitle(config: MediaItem.SubtitleConfiguration) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val position = p.currentPosition
        val play = p.playWhenReady
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(config.language ?: "und").build()
        p.setMediaItem(item.buildUpon().setSubtitleConfigurations(listOf(config)).build(), position)
        p.prepare(); p.playWhenReady = play
    }
}
data class PlayerUiState(val connected: Boolean = false, val title: String = "", val playing: Boolean = false,
    val playWhenReady: Boolean = false, val buffering: Boolean = true, val ended: Boolean = false,
    val seekable: Boolean = false, val position: Long = 0, val duration: Long = 0, val buffered: Long = 0,
    val tracks: List<PlaybackTrack> = emptyList(), val error: Boolean = false, val frameRate: Float = -1f, val videoInfo: String = "")
