package tv.torrserve.silicon.core.server

import tv.torrserve.silicon.core.localization.uiString
import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data object Stopping : ServerState
    data class Running(val version: String, val pid: Long) : ServerState
    data class Error(val message: String) : ServerState
}
class ServerController(private val context: Context, private val api: TorrServerApi,
    private val scope: CoroutineScope, private val settings: tv.torrserve.silicon.core.storage.SettingsStore) {
    private fun port() = settings.state.value.port
    private fun cacheMb() = settings.state.value.cacheMb
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libtorrserver.so")
    private val directory = File(context.filesDir, "torrserver").apply { mkdirs() }
    private val marker = File(directory, "managed.pid")
    private val lock = Mutex()
    private var process: Process? = null
    private var idleJob: Job? = null
    @Volatile var foreground = false
    @Volatile var playbackOwned = false
    private val mutableExternalPlayback = MutableStateFlow(false)
    val externalPlayback = mutableExternalPlayback.asStateFlow()
    val hasPlayback get() = playbackOwned || externalPlayback.value
    fun externalPlayback(active: Boolean) {
        mutableExternalPlayback.value = active
        scheduleIdleStop()
    }
    @Volatile var backgroundOwned = false
    private val mutable = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state = mutable.asStateFlow()
    private val backgroundFailure = MutableStateFlow("")
    val backgroundError = backgroundFailure.asStateFlow()
    fun foreground(visible: Boolean) {
        foreground = visible; idleJob?.cancel()
        if (visible) scope.launch { start() }
        else scheduleIdleStop()
    }
    fun scheduleIdleStop() {
        idleJob?.cancel()
        if (!foreground && !hasPlayback && !backgroundOwned) idleJob = scope.launch {
            delay(15_000); if (!foreground && !hasPlayback && !backgroundOwned) stop()
        }
    }
    suspend fun start() = scope.async(Dispatchers.IO) { lock.withLock {
        if (!settings.state.value.useLocalServer) return@withLock
        if (process?.isAlive == true && mutable.value is ServerState.Running) return@withLock
        mutable.value = ServerState.Starting
        val began = tv.torrserve.silicon.core.performance.Diagnostics.mark()
        try {
            check(executable.isFile && executable.canExecute()) { context.uiString(tv.torrserve.silicon.R.string.server_apk_missing) }
            recoverOrphan()
            val bindAddress = settings.state.value.serverBindAddress
            java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(bindAddress,port())) }
            // Android's java.lang.Process has no pid(). The shell records its own PID
            // then exec replaces it with the packaged ELF. Arguments stay positional.
            val child = ProcessBuilder("/system/bin/sh", "-c",
                "echo \$\$ > \"\$1\"; exec \"\$2\" --path \"\$3\" --port \"\$4\" --ip \"\$5\"",
                "torrframe", marker.absolutePath, executable.absolutePath, directory.absolutePath, port().toString(), bindAddress)
                .directory(directory).redirectErrorStream(true).redirectOutput(File("/dev/null"))
                .apply { environment()["GOMEMLIMIT"] = "${cacheMb()+128}MiB"; environment()["GOGC"] = "50" }.start()
            process = child
            val pid = withTimeout(3000) {
                var value: Long? = null
                while (value == null) { value = marker.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull(); if (value == null) delay(20) }
                value
            }
            val version = withTimeout(30_000) {
                var found: String? = null
                while (found == null) {
                    check(child.isAlive) { context.uiString(tv.torrserve.silicon.R.string.server_exited_early) }
                    found = try { api.version().takeIf { it.startsWith("MatriX") || it.startsWith("TorrServer") } }
                        catch (e: Exception) { if (e is CancellationException) throw e; null }
                    if (found == null) delay(250)
                }; found
            }
            if (!settings.state.value.engineSettingsInitialized) {
                api.configureCache(cacheMb())
                settings.update { it.copy(engineSettingsInitialized = true) }
            }
            mutable.value = ServerState.Running(version, pid)
            scheduleIdleStop()
            tv.torrserve.silicon.core.performance.Diagnostics.elapsed("server_start",began)
            scope.launch(Dispatchers.IO) {
                child.waitFor()
                lock.withLock {
                    if (process === child) {
                        process = null; marker.delete()
                        mutable.value = ServerState.Error(context.uiString(tv.torrserve.silicon.R.string.server_exited))
                    }
                }
            }
        } catch (e: Exception) {
            process?.destroyForcibly(); process = null; marker.delete()
            if (e is CancellationException) { mutable.value = ServerState.Stopped; throw e }
            mutable.value = ServerState.Error(context.uiString(tv.torrserve.silicon.R.string.server_start_failed))
        }
    } }.await()
    private fun recoverOrphan() {
        val pid = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
        if (pid > 1) {
            val cmd = runCatching { File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000') }.getOrNull()
            val owner = runCatching { Os.stat("/proc/$pid").st_uid == Os.getuid() }.getOrDefault(false)
            if (owner && cmd?.firstOrNull() == executable.absolutePath && cmd.contains(directory.absolutePath)) {
                runCatching { Os.kill(pid, OsConstants.SIGTERM) }
                repeat(20) { if (File("/proc/$pid").exists()) Thread.sleep(50) }
                if (File("/proc/$pid").exists() && runCatching { Os.stat("/proc/$pid").st_uid == Os.getuid() }.getOrDefault(false)) {
                    val current = runCatching { File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).split('\u0000') }.getOrNull()
                    if (current == cmd) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                }
            }
        }; marker.delete()
    }
    private fun stopManagedProcess() {
        check(!hasPlayback) { context.uiString(tv.torrserve.silicon.R.string.server_stop_playback) }
        mutable.value = ServerState.Stopping
        val child = process; process = null
        if (child == null) recoverOrphan()
        child?.destroy()
        if (child != null && !child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly(); child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        marker.delete(); mutable.value = ServerState.Stopped
    }
    suspend fun stop() = withContext(Dispatchers.IO) { lock.withLock { stopManagedProcess() } }
    suspend fun changeConnection(save: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock { stopManagedProcess(); save() }
    }
    fun backgroundError(message: String) { backgroundFailure.value = message }
    suspend fun restart() { stop(); start() }
    suspend fun requireRunning() {
        if (!settings.state.value.useLocalServer) {
            withTimeout(12_000) { api.at(settings.state.value.torrServerUrl).version() }
        } else {
            start(); check(state.value is ServerState.Running) { context.uiString(tv.torrserve.silicon.R.string.server_unavailable) }
        }
    }
}
