package tv.torrframe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.ui.sound.UiSound
import tv.torrframe.ui.sound.UiSounds

@RunWith(AndroidJUnit4::class)
class UiSoundsIntegrationTest {
    @Test fun samplesLoadAndMuteBackgroundReleaseAndResumeNeverQueueFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var enabled = true
        val sounds = UiSounds(instrumentation.targetContext) { enabled }
        fun ready(): Boolean {
            val end = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < end) {
                var played = false
                instrumentation.runOnMainSync { played = sounds.play(UiSound.Select) }
                if (played) return true
                SystemClock.sleep(25)
            }
            return false
        }
        try {
            instrumentation.runOnMainSync { sounds.foreground(true) }
            assertTrue("SoundPool must accept a loaded sample", ready())
            instrumentation.runOnMainSync {
                assertFalse("Rapid second action is dropped", sounds.play(UiSound.Back))
                enabled = false; sounds.settingsChanged()
                assertFalse(sounds.play(UiSound.ToggleOn))
                enabled = true; sounds.settingsChanged()
            }
            assertTrue("Re-enable reloads released samples", ready())
            instrumentation.runOnMainSync {
                sounds.foreground(false)
                assertFalse(sounds.play(UiSound.Success))
                sounds.foreground(true)
            }
            assertTrue("Return to foreground reloads samples", ready())
            // Each of the six approved files must decode and play on this device.
            for (cue in UiSound.entries) {
                SystemClock.sleep(300)
                instrumentation.runOnMainSync { assertTrue(cue.name, sounds.play(cue)) }
            }
        } finally { instrumentation.runOnMainSync { sounds.foreground(false) } }
    }
}
