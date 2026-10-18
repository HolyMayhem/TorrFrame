package tv.torrserve.silicon

import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.torrentsearch.model.*

class TorrentSearchSettingsTests {
    @Test fun defaultsAndMalformedPreferencesAreSafe() {
        assertEquals(TorrentSearchOptions(),TorrentSearchSettingsCodec.decode(null))
        assertEquals(TorrentSearchOptions(),TorrentSearchSettingsCodec.decode("broken"))
        assertEquals(5_000,TorrentSearchSettingsCodec.decode("{\"timeout\":-1}").sourceTimeoutMs)
    }
    @Test fun policyRoundTripsWithoutLosingEmptyOrMultipleSelections() {
        val options=TorrentSearchOptions(setOf("rutorinfo","knaben"),setOf("1080p","2160p"),setOf("WEB-DL"),
            TorrentSearchOptions.GIB,10*TorrentSearchOptions.GIB,5,true,30_000,SearchTitleMode.Russian)
        assertEquals(options,TorrentSearchSettingsCodec.decode(TorrentSearchSettingsCodec.encode(options)))
        val none=TorrentSearchOptions(enabledProviders=emptySet())
        assertEquals(none,TorrentSearchSettingsCodec.decode(TorrentSearchSettingsCodec.encode(none)))
        assertNull(TorrentSearchSettingsCodec.decode(TorrentSearchSettingsCodec.encode(TorrentSearchOptions())).enabledProviders)
    }
    @Test fun rebasingPreservesPendingSearchFieldsAndUnrelatedRemoteChanges() {
        val base=Settings()
        val draft=base.copy(torrentSearch=base.torrentSearch.copy(resolutions=setOf("1080p")))
        val current=base.copy(uiLanguage="ru",torrentSearch=base.torrentSearch.copy(minSeeds=10))
        val result=SettingsMerge.rebase(draft,base,current)
        assertEquals("ru",result.uiLanguage);assertEquals(10,result.torrentSearch.minSeeds)
        assertEquals(setOf("1080p"),result.torrentSearch.resolutions)
    }
}
