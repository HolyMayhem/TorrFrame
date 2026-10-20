package tv.torrframe

import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.features.player.*
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.storage.SettingsMerge

class PlayerPreferencesTests {
    @Test fun settingsRoundTripAndCorruptValuesFallBackSafely() {
        val p = PlayerPreferences(speed = 1.5f, aspect = VideoAspect.Zoom, decoder = AudioDecoder.Ffmpeg,
            matchFrameRate = true, skipSilence = true, subtitleSize = 150, subtitleColor = SubtitleColor.Yellow,
            subtitleBackground = true, subtitleBold = true, embeddedSubtitleStyle = false)
        assertEquals(p, PlayerPreferences.decode(p.encode()))
        assertEquals(PlayerPreferences(), PlayerPreferences.decode("invalid"))
        assertEquals(PlayerPreferences(), PlayerPreferences.decode("{\"speed\":-1,\"subtitleSize\":999,\"aspect\":\"invalid\",\"decoder\":\"invalid\"}"))
    }
    @Test fun concurrentPlayerEditsPreserveAppearanceAndOtherPlaybackChanges() {
        val base = Settings()
        val draft = base.copy(builtInPlayer = base.builtInPlayer.copy(speed = 1.25f))
        val current = base.copy(uiAccent = "blue", uiLanguage = "ru", builtInPlayer = base.builtInPlayer.copy(subtitleSize = 125))
        val merged = SettingsMerge.rebase(draft, base, current)
        assertEquals(1.25f, merged.builtInPlayer.speed)
        assertEquals(125, merged.builtInPlayer.subtitleSize)
        assertEquals("blue", merged.uiAccent)
        assertEquals("ru", merged.uiLanguage)
    }
    @Test fun refreshRateSelectionPreservesResolutionCandidateAndExactCadence() {
        assertEquals(2, bestRefreshRate(23.976f, listOf(1 to 60f, 2 to 23.976f), 1))
        assertEquals(3, bestRefreshRate(24f, listOf(1 to 60f, 2 to 24f, 3 to 120f), 1))
        assertEquals(1, bestRefreshRate(30f, listOf(1 to 60f, 2 to 120f), 1))
        assertNull(bestRefreshRate(24f, listOf(1 to 60f), 1))
        assertNull(bestRefreshRate(Float.NaN, emptyList(), 1))
        assertNull(bestRefreshRate(-1f, emptyList(), 1))
    }
    @Test fun subtitleFormatsAndEncodingAreHandledWithoutExtraDependencies() {
        assertEquals("application/x-subrip", ExternalSubtitles.mime("movie.RU.SRT?token=test"))
        assertEquals("text/x-ssa", ExternalSubtitles.mime("movie.ass"))
        assertNull(ExternalSubtitles.mime("movie.exe"))
        val text = "1\n00:00:01,000 --> 00:00:03,000\nПроверка субтитров"
        assertEquals(text, ExternalSubtitles.decode(text.toByteArray(Charsets.UTF_8), true))
        assertEquals(text, ExternalSubtitles.decode(text.toByteArray(charset("windows-1251")), true))
        assertEquals(text, ExternalSubtitles.decode(text.toByteArray(Charsets.UTF_16), false))
    }
    @Test fun timelineHandlesUnknownAndLongDurations() {
        assertEquals("00:00", playbackTime(-1))
        assertEquals("01:05", playbackTime(65_000))
        assertEquals("2:01:05", playbackTime(7_265_000))
    }
}
