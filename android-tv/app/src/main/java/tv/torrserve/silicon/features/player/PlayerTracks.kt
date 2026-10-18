package tv.torrserve.silicon.features.player

import androidx.media3.common.*
import java.util.Locale

/** The service and its TV controller expose and select exactly the same supported tracks. */
internal fun Player.playbackTracks(): List<PlaybackTrack> = currentTracks.groups.flatMapIndexed { groupIndex, group ->
    if (group.type !in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) emptyList()
    else (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
        val f = group.getTrackFormat(index)
        val name = f.label?.takeIf(String::isNotBlank) ?: f.language?.takeIf { it != "und" }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        } ?: "${index + 1}"
        val detail = listOfNotNull(f.sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.ROOT),
            f.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ")
        PlaybackTrack("$groupIndex:$index", if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "subtitle",
            listOf(name, detail).filter(String::isNotBlank).joinToString(" · "), group.isTrackSelected(index))
    }
}

internal fun Player.selectPlaybackTrack(id: String?) {
    if (id == null || id == "subtitle:off") {
        trackSelectionParameters = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        return
    }
    val parts = id.split(':').mapNotNull(String::toIntOrNull)
    require(parts.size == 2)
    val group = requireNotNull(currentTracks.groups.getOrNull(parts[0]))
    require(group.type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT) &&
        parts[1] in 0 until group.length && group.isTrackSupported(parts[1]))
    trackSelectionParameters = trackSelectionParameters.buildUpon().clearOverridesOfType(group.type)
        .setTrackTypeDisabled(group.type, false).addOverride(TrackSelectionOverride(group.mediaTrackGroup, parts[1])).build()
}
