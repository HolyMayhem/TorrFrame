package tv.torrserve.silicon

import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.core.storage.SettingsMerge
import tv.torrserve.silicon.features.player.*

class PlayerSelectionTests {
    private val vlc = ExternalPlayer("org.videolan.vlc/.gui.video.VideoPlayerActivity", "VLC")
    @Test fun existingAndUnknownPreferencesKeepTheBuiltInPlayer() {
        assertEquals(PlayerMode.Internal, Settings().playerMode)
        assertEquals(PlayerMode.Internal, PlayerMode.from(null))
        assertEquals(PlayerMode.Internal, PlayerMode.from("unsupported"))
        assertEquals(PlayerMode.Ask, PlayerMode.from("Ask"))
        assertEquals(PlayerDestination.Internal, playerDestination(PlayerMode.Internal, vlc.component, listOf(vlc)))
    }
    @Test fun preferredPlayerMustStillBeAvailableAndAskNeverAutoLaunches() {
        assertEquals(PlayerDestination.External(vlc), playerDestination(PlayerMode.External, vlc.component, listOf(vlc)))
        assertEquals(PlayerDestination.Choose(true), playerDestination(PlayerMode.External, vlc.component, emptyList()))
        assertEquals(PlayerDestination.Choose(), playerDestination(PlayerMode.External, "", emptyList()))
        assertEquals(PlayerDestination.Choose(), playerDestination(PlayerMode.Ask, vlc.component, listOf(vlc)))
        assertEquals(PlayerDestination.Choose(), playerDestination(PlayerMode.Ask, "", emptyList()))
    }
    @Test fun settingsMergePreservesPlayerSelectionAndConcurrentUnrelatedChanges() {
        val base = Settings()
        val draft = base.copy(playerMode = PlayerMode.External, externalPlayer = vlc.component)
        val result = SettingsMerge.rebase(draft, base, base.copy(uiLanguage = "ru", resume = false))
        assertEquals(PlayerMode.External, result.playerMode)
        assertEquals(vlc.component, result.externalPlayer)
        assertEquals("ru", result.uiLanguage); assertFalse(result.resume)
        val remote = SettingsMerge.rebase(base.copy(audioLanguage = "en"), base, draft)
        assertEquals(PlayerMode.External, remote.playerMode); assertEquals(vlc.component, remote.externalPlayer)
        assertEquals("en", remote.audioLanguage)
    }
}
