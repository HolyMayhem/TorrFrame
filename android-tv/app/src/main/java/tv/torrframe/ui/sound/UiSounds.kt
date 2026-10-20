package tv.torrframe.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import tv.torrframe.R

enum class UiSound(val resource: Int) {
    Select(R.raw.ux_select), Back(R.raw.ux_back),
    ToggleOn(R.raw.ux_toggle_on), ToggleOff(R.raw.ux_toggle_off),
    Success(R.raw.ux_success), Unavailable(R.raw.ux_unavailable)
}

/** One short effect at a time; key repeats never queue sounds for later. */
internal class UiSoundGate {
    private var last: Long? = null
    fun accept(now: Long): Boolean {
        if (last?.let { now - it < 120 } == true) return false
        last = now
        return true
    }
    fun reset() { last = null }
}

/** App-context-only owner. SoundPool decodes asynchronously; nothing waits for audio. */
class UiSounds(context: Context, private val enabled: () -> Boolean) {
    private val app = context.applicationContext
    private val gate = UiSoundGate()
    private var foreground = false
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<UiSound, Int>()
    private val loaded = mutableSetOf<Int>()
    private var stream = 0

    @Synchronized fun foreground(value: Boolean) {
        foreground = value
        settingsChanged()
    }

    @Synchronized fun settingsChanged() {
        if (!foreground || !enabled()) { release(); return }
        if (pool != null) return
        // Device audio failures must never prevent an action or application startup.
        try {
            val next = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = next
            next.setOnLoadCompleteListener { owner, id, status ->
                synchronized(this) { if (pool === owner && status == 0) loaded.add(id) }
            }
            UiSound.entries.forEach { samples[it] = next.load(app, it.resource, 1) }
        } catch (_: RuntimeException) { release() }
    }

    @Synchronized fun play(sound: UiSound): Boolean {
        if (!foreground || !enabled()) return false
        val owner = pool ?: return false
        val id = samples[sound]?.takeIf { it in loaded } ?: return false
        if (!gate.accept(SystemClock.elapsedRealtime())) return false
        return try {
            if (stream != 0) owner.stop(stream)
            // The approved WAVs already have quiet levels; no normalization or audio focus request.
            stream = owner.play(id, 1f, 1f, 1, 0, 1f)
            stream != 0
        } catch (_: RuntimeException) { false }
    }

    private fun release() {
        val previous = pool
        pool = null
        samples.clear(); loaded.clear(); stream = 0; gate.reset()
        runCatching { previous?.setOnLoadCompleteListener(null) }
        runCatching { previous?.release() }
    }
}
