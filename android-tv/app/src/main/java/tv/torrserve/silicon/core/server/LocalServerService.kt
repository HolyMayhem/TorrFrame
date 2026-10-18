package tv.torrserve.silicon.core.server

import tv.torrserve.silicon.core.localization.uiString
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tv.torrserve.silicon.R
import tv.torrserve.silicon.MainActivity
import tv.torrserve.silicon.TorrFrameApplication

/** Keeps the packaged server owned by an Android service instead of an orphaned child. */
class LocalServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as TorrFrameApplication).graph
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(8090, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(8090, notification)
        } catch (_: RuntimeException) {
            graph.server.backgroundError(uiString(R.string.boot_restricted)); stopSelf(); return
        }
        scope.launch {
            graph.settings.state.map { it.uiLanguage }.distinctUntilChanged().collect {
                // Refresh the existing foreground notification without requesting another permission.
                if (Build.VERSION.SDK_INT >= 34) startForeground(8090, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(8090, notification())
            }
        }
        scope.launch {
            try {
                graph.ready.await()
                combine(graph.settings.state, graph.server.externalPlayback) { settings, external ->
                    settings.needsLocalService || (settings.useLocalServer && external)
                }.distinctUntilChanged().collectLatest { enabled ->
                    if (!enabled) { stopSelf(); return@collectLatest }
                    graph.server.backgroundOwned = true
                    graph.server.start()
                    if (graph.server.state.value !is ServerState.Running) stopSelf()
                    else {
                        graph.server.backgroundError("")
                        graph.server.state.collect { state ->
                            if (state is ServerState.Error) stopSelf()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                graph.server.backgroundError(uiString(R.string.boot_restricted)); stopSelf()
            }
        }
    }
    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, uiString(R.string.local_service_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(uiString(R.string.local_service_title)).setContentText(uiString(R.string.local_service_text))
            .setContentIntent(open).setOngoing(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onDestroy() {
        scope.cancel()
        graph.server.backgroundOwned = false
        graph.server.scheduleIdleStop()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "local-torrserver"
        fun start(context: Context) { context.startForegroundService(Intent(context, LocalServerService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, LocalServerService::class.java)) }
    }
}
