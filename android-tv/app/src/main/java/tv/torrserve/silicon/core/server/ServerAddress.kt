package tv.torrserve.silicon.core.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    const val DEFAULT = "http://localhost:8090"
    fun local(port: Int) = "http://localhost:$port"
    fun parse(value: String): HttpUrl {
        val trimmed = value.trim()
        require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))
        require(trimmed.none { it.isWhitespace() || it.isISOControl() })
        val url = requireNotNull(trimmed.toHttpUrlOrNull())
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        return url
    }
    fun normalize(value: String) = parse(value).toString().trimEnd('/')
    fun isLocal(value: String, port: Int): Boolean = runCatching {
        val url = parse(value)
        url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1") &&
            url.port == port && url.encodedPath == "/"
    }.getOrDefault(false)
    fun endpoint(address: String, path: String): HttpUrl = parse(address).newBuilder()
        .encodedPath(parse(address).encodedPath.trimEnd('/') + "/")
        .addPathSegments(path).build()
}
