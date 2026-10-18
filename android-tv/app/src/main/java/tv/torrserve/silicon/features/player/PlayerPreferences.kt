package tv.torrserve.silicon.features.player

import org.json.JSONObject

/** Playback preferences only. Appearance always comes from the application's Settings. */
data class PlayerPreferences(
    val speed: Float = 1f,
    val aspect: VideoAspect = VideoAspect.Fit,
    val decoder: AudioDecoder = AudioDecoder.Device,
    val matchFrameRate: Boolean = false,
    val skipSilence: Boolean = false,
    val subtitleSize: Int = 100,
    val subtitleColor: SubtitleColor = SubtitleColor.White,
    val subtitleBackground: Boolean = false,
    val subtitleBold: Boolean = false,
    val embeddedSubtitleStyle: Boolean = true,
) {
    fun normalized() = copy(speed = speed.takeIf { it in SPEEDS } ?: 1f,
        subtitleSize = subtitleSize.takeIf { it in SUBTITLE_SIZES } ?: 100)
    fun encode(): String = JSONObject().put("speed", speed).put("aspect", aspect.name)
        .put("decoder", decoder.name).put("matchFrameRate", matchFrameRate).put("skipSilence", skipSilence)
        .put("subtitleSize", subtitleSize).put("subtitleColor", subtitleColor.name)
        .put("subtitleBackground", subtitleBackground).put("subtitleBold", subtitleBold)
        .put("embeddedSubtitleStyle", embeddedSubtitleStyle).toString()

    companion object {
        val SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val SUBTITLE_SIZES = listOf(75, 100, 125, 150, 175)
        fun decode(value: String?): PlayerPreferences = runCatching {
            val j = JSONObject(value ?: "{}")
            PlayerPreferences(j.optDouble("speed", 1.0).toFloat(),
                VideoAspect.entries.firstOrNull { it.name == j.optString("aspect") } ?: VideoAspect.Fit,
                AudioDecoder.entries.firstOrNull { it.name == j.optString("decoder") } ?: AudioDecoder.Device,
                j.optBoolean("matchFrameRate"), j.optBoolean("skipSilence"), j.optInt("subtitleSize", 100),
                SubtitleColor.entries.firstOrNull { it.name == j.optString("subtitleColor") } ?: SubtitleColor.White,
                j.optBoolean("subtitleBackground"), j.optBoolean("subtitleBold"), j.optBoolean("embeddedSubtitleStyle", true)
            ).normalized()
        }.getOrDefault(PlayerPreferences())

        /** Player and Settings screens may edit different fields concurrently. */
        fun rebase(draft: PlayerPreferences, base: PlayerPreferences, current: PlayerPreferences) = current.copy(
            speed = if (draft.speed != base.speed) draft.speed else current.speed,
            aspect = if (draft.aspect != base.aspect) draft.aspect else current.aspect,
            decoder = if (draft.decoder != base.decoder) draft.decoder else current.decoder,
            matchFrameRate = if (draft.matchFrameRate != base.matchFrameRate) draft.matchFrameRate else current.matchFrameRate,
            skipSilence = if (draft.skipSilence != base.skipSilence) draft.skipSilence else current.skipSilence,
            subtitleSize = if (draft.subtitleSize != base.subtitleSize) draft.subtitleSize else current.subtitleSize,
            subtitleColor = if (draft.subtitleColor != base.subtitleColor) draft.subtitleColor else current.subtitleColor,
            subtitleBackground = if (draft.subtitleBackground != base.subtitleBackground) draft.subtitleBackground else current.subtitleBackground,
            subtitleBold = if (draft.subtitleBold != base.subtitleBold) draft.subtitleBold else current.subtitleBold,
            embeddedSubtitleStyle = if (draft.embeddedSubtitleStyle != base.embeddedSubtitleStyle) draft.embeddedSubtitleStyle else current.embeddedSubtitleStyle)
    }
}
enum class VideoAspect { Fit, Zoom, Fill }
enum class AudioDecoder { Device, Ffmpeg }
enum class SubtitleColor { White, Yellow }

fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
