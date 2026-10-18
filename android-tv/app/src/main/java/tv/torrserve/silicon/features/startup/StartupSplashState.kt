package tv.torrserve.silicon.features.startup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StartupPhase { Pending, Video, Black, Fading, Finished }
data class StartupFrame(val phase: StartupPhase = StartupPhase.Pending,
    val phaseStartedAt: Long = 0, val renderedVideo: Boolean = false,
    val decoder: String = "", val videoFailed: Boolean = false)

/** Process-owned timing only. Never holds an Activity, view, decoder, or player. */
class StartupSplashState(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(StartupFrame())
    val state = mutable.asStateFlow()
    private var videoClaimed = false
    val visible get() = mutable.value.phase != StartupPhase.Finished

    @Synchronized fun begin() {
        if (mutable.value.phase == StartupPhase.Pending) move(StartupPhase.Video)
    }
    @Synchronized fun claimVideo(): Boolean {
        if (mutable.value.phase != StartupPhase.Video || videoClaimed) return false
        videoClaimed = true
        return true
    }
    @Synchronized fun decodedBy(name: String) {
        if (mutable.value.phase == StartupPhase.Video) mutable.value = mutable.value.copy(decoder = name)
    }
    @Synchronized fun firstVideoFrame() {
        if (mutable.value.phase == StartupPhase.Video) mutable.value = mutable.value.copy(renderedVideo = true)
    }
    @Synchronized fun videoEnded(failed: Boolean = false) {
        if (mutable.value.phase != StartupPhase.Video) return
        mutable.value = mutable.value.copy(phase = StartupPhase.Black, phaseStartedAt = now(), videoFailed = failed)
    }
    fun videoRemainingMs() = (VIDEO_TIMEOUT_MS - (now() - mutable.value.phaseStartedAt)).coerceIn(0, VIDEO_TIMEOUT_MS)
    @Synchronized fun fadeWhenReady(ready: Boolean) {
        if (ready && mutable.value.phase == StartupPhase.Black) move(StartupPhase.Fading)
    }
    fun fadeProgress(): Float = when (mutable.value.phase) {
        StartupPhase.Finished -> 1f
        StartupPhase.Fading -> ((now() - mutable.value.phaseStartedAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
        else -> 0f
    }
    @Synchronized fun finish() { if (visible) move(StartupPhase.Finished) }
    @Synchronized fun stopped(changingConfiguration: Boolean) {
        // Recreation may finish the video, but never claims/decodes it again.
        if (changingConfiguration) videoEnded() else finish()
    }
    private fun move(phase: StartupPhase) { mutable.value = mutable.value.copy(phase = phase, phaseStartedAt = now()) }

    companion object {
        const val FADE_MS = 1_200L
        const val VIDEO_TIMEOUT_MS = 6_000L
    }
}
