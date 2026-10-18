package tv.torrserve.silicon.features.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Discover handlers for video streams, without requesting visibility of every installed app. */
class ExternalPlayers(private val context: Context) {
    suspend fun available(stream: String? = null): List<ExternalPlayer> = withContext(Dispatchers.IO) {
        val probes = stream?.let(::listOf) ?: listOf("http://127.0.0.1/video", "https://127.0.0.1/video")
        probes.flatMap { url ->
            @Suppress("DEPRECATION")
            val handlers = context.packageManager.queryIntentActivities(videoIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
            handlers.filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled &&
                it.activityInfo.packageName != context.packageName }
                .map { ExternalPlayer(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(),
                    it.loadLabel(context.packageManager).toString()) }
        }.distinctBy { it.component.substringBefore('/') }.sortedWith(compareBy({ it.label.lowercase() }, { it.component }))
    }

    fun intent(player: ExternalPlayer, stream: String, title: String): Intent = videoIntent(stream).apply {
        component = requireNotNull(ComponentName.unflattenFromString(player.component))
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra("title", title)
    }

    private fun videoIntent(stream: String) = Intent(Intent.ACTION_VIEW).apply {
        val uri = stream.toUri()
        require(uri.scheme in listOf("http", "https"))
        setDataAndType(uri, "video/*")
    }
}
