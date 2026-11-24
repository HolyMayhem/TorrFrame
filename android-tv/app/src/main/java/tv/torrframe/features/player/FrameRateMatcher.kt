package tv.torrframe.features.player

import android.view.Window
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.round

/** Same-resolution display-mode matching, adapted from Just Player's Utils.handleFrameRate (Unlicense).
 * Modern seamless Surface frame-rate hints remain Media3's responsibility. No network re-probing. */
class FrameRateMatcher(private val window: Window, private val onConfirmed: () -> Unit = {}) {
    private val displays = window.context.getSystemService(DisplayManager::class.java)
    private var pending: Pair<Int, Int>? = null
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (pending?.first == displayId) stopObserving() }
        override fun onDisplayChanged(displayId: Int) {
            val match = pending ?: return
            if (match.first == displayId && displays?.getDisplay(displayId)?.mode?.modeId == match.second) {
                stopObserving()
                onConfirmed()
            }
        }
    }
    private fun stopObserving() {
        if (pending != null) displays?.unregisterDisplayListener(listener)
        pending = null
    }
    private val originalMode = window.attributes.preferredDisplayModeId
    private var lastRate = -1f
    private var applied = false
    fun update(enabled: Boolean, frameRate: Float) {
        if (!enabled) { restore(); return }
        if (!frameRate.isFinite() || frameRate <= 0 || abs(frameRate - lastRate) < .01f) return
        lastRate = frameRate
        val display = window.decorView.display ?: return
        val current = display.mode
        val mode = bestRefreshRate(frameRate, display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.map { it.modeId to it.refreshRate }, current.modeId) ?: return
        if (mode != current.modeId) {
            stopObserving()
            pending = display.displayId to mode
            displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode }
            applied = true
        }
    }
    fun restore() {
        stopObserving()
        if (applied) window.attributes = window.attributes.apply { preferredDisplayModeId = originalMode }
        applied = false; lastRate = -1f
    }
}
/** Prefer exact integer cadence; never switch to an unrelated refresh rate or another resolution. */
fun bestRefreshRate(fps: Float, modes: List<Pair<Int, Float>>, currentId: Int): Int? {
    if (!fps.isFinite() || fps <= 0) return null
    val exact = modes.filter { (_, hz) -> hz >= fps - .02f && abs(hz / fps - round(hz / fps)) < .002f }
    if (exact.any { it.first == currentId }) return currentId
    return exact.maxByOrNull { it.second }?.first
}
