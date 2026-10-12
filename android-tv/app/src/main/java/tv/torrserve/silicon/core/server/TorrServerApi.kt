package tv.torrserve.silicon.core.server

import okhttp3.HttpUrl.Companion.toHttpUrl
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

class TorrServerApi(private val http: Http, private val port: () -> Int) {
    private fun url(path: String) = "http://127.0.0.1:${port()}/$path".toHttpUrl()
    private suspend fun command(action: String, hash: String? = null, extra: JSONObject? = null): ByteArray {
        val j = extra ?: JSONObject(); j.put("action", action); hash?.let { j.put("hash", it) }
        return http.post(url("torrents"), j, "TorrServer")
    }
    suspend fun version() = http.get(url("echo"), "TorrServer").toString(Charsets.UTF_8).trim().take(80)
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
    suspend fun configureCache(megabytes: Int): Unit = withContext(Dispatchers.Default) {
        val current = JSONObject(http.post(url("settings"), JSONObject().put("action", "get"), "TorrServer").toString(Charsets.UTF_8))
        current.put("CacheSize", megabytes * 1024L * 1024).put("EnableDebug", false)
            .put("EnableDLNA", false).put("EnableBonjour", false)
        http.post(url("settings"), JSONObject().put("action", "set").put("sets", current), "TorrServer")
        Unit
    }
    fun stream(hash: String, file: TorrentFile) = url("stream").newBuilder().addPathSegment(file.path.substringAfterLast('/'))
        .addQueryParameter("link", hash).addQueryParameter("index", file.id.toString()).addQueryParameter("play", null).build().toString()
}
