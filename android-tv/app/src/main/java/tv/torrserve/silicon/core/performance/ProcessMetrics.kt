package tv.torrserve.silicon.core.performance

import android.os.Debug
import android.os.Process
import android.os.SystemClock

data class ProcessMetrics(val pssKb: Long?, val javaUsedBytes: Long, val javaMaxBytes: Long,
    val graphicsKb: Long?, val cpuPercent: Double?, val cores: Int)

/** One process; child processes such as the bundled TorrServer are not included. */
class ProcessMetricsSampler {
    private var previousCpu: Long? = null
    private var previousTime: Long? = null
    fun sample(): ProcessMetrics {
        val time = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val load = cpuPercent(previousCpu, previousTime, cpu, time, cores)
        previousCpu = cpu; previousTime = time
        val memory = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } }.getOrNull()
        val runtime = Runtime.getRuntime()
        return ProcessMetrics(memory?.totalPss?.toLong(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
            memory?.getMemoryStat("summary.graphics")?.toLongOrNull()?.takeIf { it >= 0 }, load, cores)
    }
}

/** 100% is all available cores; no sample until a positive wall-clock interval has elapsed. */
internal fun cpuPercent(previousCpu: Long?, previousTime: Long?, cpu: Long, time: Long, cores: Int): Double? {
    if (previousCpu == null || previousTime == null || time <= previousTime || cpu < previousCpu) return null
    return ((cpu - previousCpu).toDouble() / (time - previousTime) / cores.coerceAtLeast(1) * 100).coerceIn(0.0, 100.0)
}
