package tv.torrserve.silicon.phoneRemote.server

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import tv.torrserve.silicon.MainActivity
import tv.torrserve.silicon.R
import tv.torrserve.silicon.TorrServeApplication

class PhoneRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> graph.phoneRemote.error(); stopSelf() })
    private var foregroundStarted = false
    private var serverJob: Job? = null
    private val graph get() = (application as TorrServeApplication).graph
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = "phone-remote"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, getString(R.string.phone_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 8765, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(getString(R.string.phone_notification)).setContentText(getString(R.string.phone_notification_text))
            .setContentIntent(open).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(8765, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(8765, notification)
            foregroundStarted = true
        } catch (_: RuntimeException) { graph.phoneRemote.error(); stopSelf(); return }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) { stopSelf(); return START_NOT_STICKY }
        serverJob?.cancel()
        serverJob = scope.launch {
            try { graph.ready.await(); graph.phoneRemote.serve(this) }
            catch (e: Exception) { if (e is CancellationException) throw e; graph.phoneRemote.error(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
