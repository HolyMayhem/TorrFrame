package tv.torrserve.silicon

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.player.*
import tv.torrserve.silicon.phoneRemote.api.RemoteSettings
import tv.torrserve.silicon.phoneRemote.api.RemoteJson

class RemoteSettingsTests {
    @Test fun phoneEditsOnlyRequestedFieldsAndKeepsPrivateKeys() {
        val base = Settings(jackettKey = "secret", port = 8099, lanAccess = false,
            builtInPlayer = PlayerPreferences(subtitleBold = true))
        val value = RemoteSettings.edit(base, JSONObject().put("uiAccent", "white").put("uiLanguage", "ru")
            .put("uiScalePercent", 80).put("playerMode", "Ask").put("builtInPlayer", JSONObject().put("speed", 1.5)))
        assertEquals("secret", value.jackettKey); assertEquals(8099, value.port); assertFalse(value.lanAccess)
        assertTrue(value.builtInPlayer.subtitleBold); assertEquals(1.5f, value.builtInPlayer.speed)
        assertEquals(PlayerMode.Ask, value.playerMode)
        val snapshot = RemoteJson.settings(value, 9)
        assertFalse(snapshot.toString().contains("secret")); assertFalse(snapshot.has("jackettKey"))
        for (key in listOf("uiTheme", "uiAccent", "uiScalePercent", "animationsEnabled", "showNavigationHints", "lanAccess", "playerMode", "externalPlayer", "builtInPlayer", "torrentSearch")) assertTrue(key, snapshot.has(key))
    }
    @Test fun searchAllSourcesCanReplaceAnExplicitSelectionAndPreserveOtherFilters() {
        val base = Settings(torrentSearch = tv.torrserve.torrentsearch.model.TorrentSearchOptions(enabledProviders = setOf("one"), minSeeds = 10))
        val value = RemoteSettings.edit(base, JSONObject().put("torrentSearch", JSONObject().put("sources", JSONObject.NULL)))
        assertNull(value.torrentSearch.enabledProviders); assertEquals(10, value.torrentSearch.minSeeds.toInt())
    }
    @Test fun invalidPreferencesAndServerMutationsCannotPassThroughSettings() {
        val invalid = listOf(JSONObject().put("lanAccess", true), JSONObject().put("uiAccent", "unknown"),
            JSONObject().put("uiScalePercent", 999), JSONObject().put("playerMode", "Other"),
            JSONObject().put("builtInPlayer", JSONObject().put("speed", 99)),
            JSONObject().put("torrentSearch", JSONObject().put("timeout", 1)),
            JSONObject().put("torrentSearch", JSONObject().put("resolutions", org.json.JSONArray(listOf("unknown")))))
        for (edit in invalid) try { RemoteSettings.edit(Settings(), edit); fail("Invalid settings accepted: $edit") } catch (_: IllegalArgumentException) { }
    }
}
