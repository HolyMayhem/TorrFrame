package tv.torrserve.silicon.core.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import tv.torrserve.silicon.TorrServeApplication

class ServerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val graph = (context.applicationContext as TorrServeApplication).graph
        graph.scope.launch {
            try {
                withTimeout(8_000) {
                    graph.ready.await()
                    val settings = graph.settings.state.value
                    if (settings.startOnBoot && settings.useLocalServer) LocalServerService.start(context)
                }
            } catch (_: Exception) {
                graph.server.backgroundError(context.getString(tv.torrserve.silicon.R.string.boot_restricted))
            } finally { pending.finish() }
        }
    }
}
