package tv.torrserve.silicon.core.performance

import android.os.SystemClock
import android.util.Log
import android.view.Window
import android.view.FrameMetrics
import android.os.Handler
import android.os.HandlerThread
import tv.torrserve.silicon.BuildConfig
import java.util.concurrent.atomic.AtomicLong

object Diagnostics {
    fun mark() = SystemClock.elapsedRealtime()
    fun elapsed(name: String, start: Long) {
        if(BuildConfig.DEBUG) Log.i("TorrServeTiming", "${name}_ms=${SystemClock.elapsedRealtime()-start}")
    }
    suspend fun <T> timed(name: String, operation: suspend () -> T): T {
        val start = mark(); return try { operation() } finally { elapsed(name,start) }
    }
}
class FrameMonitor(private val window: Window, private val budgetNanos: Long) {
    private val thread = HandlerThread("frame-metrics")
    private val frames = AtomicLong(); private val slow = AtomicLong(); private val longest = AtomicLong()
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        frames.incrementAndGet(); if(duration > budgetNanos) slow.incrementAndGet()
        longest.updateAndGet { it.coerceAtLeast(duration) }
    }
    init { if(BuildConfig.DEBUG) { thread.start(); window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper)) } }
    fun close() {
        if(BuildConfig.DEBUG) {
            window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely()
            Log.i("TorrServeTiming", "frames=${frames.get()} over_budget=${slow.get()} longest_frame_ms=${longest.get()/1_000_000}")
        }
    }
}
