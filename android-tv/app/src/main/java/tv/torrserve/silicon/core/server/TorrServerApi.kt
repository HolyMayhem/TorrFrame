package tv.torrserve.silicon.core.server

import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TorrServerApi(private val http: Http, private val address: () -> String) {
    private fun url(path: String) = ServerAddress.endpoint(address(), path)
    fun at(address: String) = TorrServerApi(http) { address }
    private suspend fun command(action: String, hash: String? = null, extra: JSONObject? = null): ByteArray {
        val j = extra ?: JSONObject(); j.put("action", action); hash?.let { j.put("hash", it) }
        return http.post(url("torrents"), j, "TorrServer")
    }
    suspend fun version(): String {
        val data = http.bytes(Request.Builder().url(url("echo")).header("Cache-Control", "no-cache").build(), "TorrServer", 256)
        val version = data.toString(Charsets.UTF_8).trim()
        if (!Regex("(?:MatriX|TorrServer)[A-Za-z0-9._+ -]{0,72}").matches(version))
            throw tv.torrserve.silicon.core.network.ServiceFailure("TorrServer", -2)
        return version
    }
    suspend fun list(): List<Torrent> = withContext(Dispatchers.Default) {
        JSONArray(command("list").toString(Charsets.UTF_8)).let { a -> (0 until a.length()).map { a.getJSONObject(it).torrent() } }
    }
    suspend fun get(hash: String) = withContext(Dispatchers.Default) { JSONObject(command("get", hash).toString(Charsets.UTF_8)).torrent() }
    suspend fun addMagnet(magnet: String, title: String): Torrent = withContext(Dispatchers.Default) {
        require(magnet.startsWith("magnet:?")) { "Enter a magnet link." }
        JSONObject(command("add", extra = JSONObject().put("link", magnet).put("title", title)
            .put("save_to_db", true)).toString(Charsets.UTF_8)).torrent()
    }
    suspend fun upload(bytes: ByteArray, title: String): Torrent = withContext(Dispatchers.Default) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("save", "true")
            .addFormDataPart("title", title).addFormDataPart("file", "download.torrent",
                bytes.toRequestBody("application/x-bittorrent".toMediaType())).build()
        val data = http.bytes(Request.Builder().url(url("torrent/upload")).post(body).build(), "TorrServer").toString(Charsets.UTF_8)
        if (data.trimStart().startsWith("[")) JSONArray(data).getJSONObject(0).torrent() else JSONObject(data).torrent()
    }
    suspend fun remove(hash: String) { command("rem", hash) }
    suspend fun settings(): JSONObject = withContext(Dispatchers.Default) {
        JSONObject(http.post(url("settings"), JSONObject().put("action", "get"), "TorrServer").toString(Charsets.UTF_8))
    }
    suspend fun setSettings(settings: JSONObject) {
        http.post(url("settings"), JSONObject().put("action", "set").put("sets", settings), "TorrServer")
    }
    suspend fun resetSettings() { http.post(url("settings"), JSONObject().put("action", "def"), "TorrServer") }
    suspend fun downloadTest(): Int = http.bytes(Request.Builder().url(url("download/8"))
        .header("Accept-Encoding", "identity").header("Cache-Control", "no-store").build(), "TorrServer", 8 * 1024 * 1024).size
    suspend fun configureCache(megabytes: Int): Unit = withContext(Dispatchers.Default) {
        val current = settings()
        current.put("CacheSize", megabytes * 1024L * 1024).put("EnableDebug", false)
            .put("EnableDLNA", false).put("EnableBonjour", false)
        setSettings(current)
    }
    fun stream(hash: String, file: TorrentFile) = url("stream").newBuilder().addPathSegment(file.path.substringAfterLast('/'))
        .addQueryParameter("link", hash).addQueryParameter("index", file.id.toString()).addQueryParameter("play", null).build().toString()
}
