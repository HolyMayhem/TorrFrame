package tv.torrserve.silicon.features.updates

import tv.torrserve.silicon.core.localization.uiString
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import tv.torrserve.silicon.AppGraph
import tv.torrserve.silicon.BuildConfig
import tv.torrserve.silicon.R

data class UpdateAsset(val url: String, val digest: String, val version: String)
data class UpdatesUi(val busy: Boolean = false, val message: String = "", val serverVersion: String = "",
    val asset: UpdateAsset? = null, val downloaded: File? = null, val failed: Boolean = false)
class UpdateManager(private val app: Application, private val graph: AppGraph) {
    private val mutable = MutableStateFlow(UpdatesUi()); val state = mutable.asStateFlow()
    private fun text(id: Int, vararg values: Any) = app.uiString(id, *values)
    @Synchronized private fun action(block: suspend () -> Unit): Job? {
        if(state.value.busy) return null
        mutable.update { it.copy(busy = true, message = "", failed = false) }
        return graph.scope.launch {
            try { graph.ready.await(); block() }
            catch(e: Exception) { if(e is CancellationException) throw e; mutable.update { it.copy(failed = true, message = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: text(R.string.update_failed) else text(R.string.update_failed)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun release(repository: String) = withContext(Dispatchers.Default) { JSONObject(graph.http.bytes(Request.Builder()
        .url("https://api.github.com/repos/$repository/releases/latest".toHttpUrl())
        .header("Accept", "application/vnd.github+json").header("User-Agent", "TorrFrame-TV").build(), "GitHub").toString(Charsets.UTF_8)) }
    fun server() = action {
        val version = release("YouROK/TorrServer").getString("tag_name")
        mutable.update { it.copy(serverVersion = version, message = text(R.string.update_latest_server, version)) }
    }
    fun check() = action {
        val repository = graph.settings.state.value.updateRepository
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { text(R.string.update_set_repository) }
        val release = release(repository); val version = release.getString("tag_name")
        if(version.removePrefix("v") == BuildConfig.VERSION_NAME) {
            mutable.update { it.copy(asset = null, downloaded = null, message = text(R.string.update_current)) }; return@action
        }
        val assets = release.getJSONArray("assets")
        val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            val name = it.getString("name"); name.endsWith(".apk", true) && !name.contains("x86", true) && !name.contains("armeabi", true)
        }
        val asset = candidates.singleOrNull() ?: candidates.singleOrNull { it.getString("name").contains("arm64", true) }
        require(asset != null) { text(R.string.update_missing_apk) }
        val digest = asset.optString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { text(R.string.update_missing_digest) }
        mutable.update { it.copy(asset = UpdateAsset(asset.getString("browser_download_url"), digest.lowercase(), version), downloaded = null,
            message = text(R.string.update_available, version, BuildConfig.VERSION_NAME)) }
    }
    fun download() = action {
        val asset = state.value.asset ?: return@action
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }; val temporary = File(dir, "pending.apk")
            val call = graph.http.client.newCall(Request.Builder().url(asset.url).build())
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { text(R.string.update_download_failed) }
                    val digest = MessageDigest.getInstance("SHA-256")
                    response.body.byteStream().use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024); var size = 0L
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if(n < 0) break
                            size += n; check(size <= 160L * 1024 * 1024) { text(R.string.update_too_large) }
                            digest.update(buffer,0,n); output.write(buffer,0,n)
                        }
                    } }
                    check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.digest) { text(R.string.update_integrity_failed) }
                    verifySignature(temporary)
                    val final = File(dir, "verified.apk"); final.delete(); check(temporary.renameTo(final)) { text(R.string.update_save_failed) }; final
                }
            } catch(e: Exception) { call.cancel(); temporary.delete(); throw e }
        }
        mutable.update { it.copy(downloaded = file, message = text(R.string.update_verified)) }
    }
    @Suppress("DEPRECATION") private fun verifySignature(file: File) {
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = app.packageManager; val installed = pm.getPackageInfo(app.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error(text(R.string.update_invalid_apk))
        check(archive.packageName == app.packageName) { text(R.string.update_wrong_app) }
        fun hashes(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if(Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }.toSet()
        }
        check(hashes(installed).isNotEmpty() && hashes(installed) == hashes(archive)) { text(R.string.update_wrong_signature) }
        val current = if(Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val newer = if(Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(newer > current) { text(R.string.update_old_apk) }
    }
    fun installer(): Intent? = state.value.downloaded?.let { file ->
        if(Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            mutable.update { it.copy(message = text(R.string.update_allow_install)) }
            return@let Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${app.packageName}"))
        }
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(app, "${app.packageName}.updates", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun installerFailed() { mutable.update { it.copy(message = text(R.string.update_installer_missing)) } }
}
