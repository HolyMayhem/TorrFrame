package tv.torrframe

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.storage.SettingsMerge
import tv.torrframe.phoneRemote.api.RemoteJson
import tv.torrframe.phoneRemote.api.RemoteSettings
import tv.torrframe.ui.sound.UiSoundGate

class UiSoundsTests {
    @Test fun rapidActionsAreDroppedWithoutExtendingTheQuietPeriod() {
        val gate = UiSoundGate()
        assertTrue(gate.accept(0))
        assertFalse(gate.accept(1))
        assertFalse(gate.accept(119))
        assertTrue(gate.accept(120))
        assertFalse(gate.accept(121))
        gate.reset()
        assertTrue(gate.accept(121))
    }

    @Test fun phoneAndTvEditsKeepTheSoundPreferenceWithoutOverwritingOtherFields() {
        val base = Settings(jackettKey = "private", uiAccent = "pink")
        assertTrue(base.uiSoundsEnabled)
        val remote = RemoteSettings.edit(base, JSONObject().put("uiSoundsEnabled", false))
        assertFalse(remote.uiSoundsEnabled)
        assertFalse(RemoteJson.settings(remote, 1).getBoolean("uiSoundsEnabled"))
        val merged = SettingsMerge.rebase(base.copy(uiLanguage = "ru"), base, remote)
        assertFalse(merged.uiSoundsEnabled)
        assertEquals("ru", merged.uiLanguage)
        assertEquals("private", merged.jackettKey)
        assertEquals("pink", merged.uiAccent)
        assertFalse(SettingsMerge.rebase(base.copy(uiSoundsEnabled = false), base,
            base.copy(uiTheme = "light")).uiSoundsEnabled)
        try {
            RemoteSettings.edit(base, JSONObject().put("uiSoundsEnabled", "false"))
            fail("String must not enable or disable sounds")
        } catch (_: IllegalArgumentException) { }
    }
}
