package tv.torrframe.phoneRemote.server

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidWebAssets(private val assets: AssetManager) : WebAssets {
    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (path.length > 200 || path.contains("..") || path.startsWith('/') || path.contains('\\')) return@withContext null
        if (path != "index.html" && path != "app-icon.png" && path != "brand-mark.png" && !path.matches(Regex("assets/[A-Za-z0-9_.-]+\\.(js|css|svg|png)"))) return@withContext null
        try { assets.open("phone-remote/$path").use { it.readBytes() } } catch (_: java.io.IOException) { null }
    }
}
