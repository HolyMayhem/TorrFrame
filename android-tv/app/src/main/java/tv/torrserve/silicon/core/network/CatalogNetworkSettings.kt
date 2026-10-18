package tv.torrserve.silicon.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class CatalogRoute { Direct, Auto, Proxy }
enum class CatalogSource { Tmdb, Cub, Auto }

/** Catalog selection and automatic poster recovery are independent. */
data class CatalogNetworkSettings(
    val route: CatalogRoute = CatalogRoute.Direct,
    val apiProxy: String = "",
    val imageMirrors: List<String> = emptyList(),
    val source: CatalogSource = CatalogSource.Tmdb,
    val cubUrl: String = "",
    val cubToken: String = "",
    val showFavorites: Boolean = true,
    val showWatchLater: Boolean = true,
) {
    val effectiveCubUrl get() = cubUrl.ifBlank { PUBLIC_CUB_URL }
    val cubConfigured get() = effectiveCubUrl.isNotBlank()
    /** Home layout changes must not invalidate network requests or caches. */
    fun connection() = copy(showFavorites = true, showWatchLater = true)
    fun validated(): CatalogNetworkSettings {
        require(imageMirrors.size <= 3 && imageMirrors.distinct().size == imageMirrors.size)
        listOf(apiProxy, cubUrl).filter(String::isNotBlank).forEach(::endpoint)
        imageMirrors.forEach(::endpoint)
        require(cubToken.length <= 4096 && cubToken.none { it.isISOControl() })
        require(route != CatalogRoute.Proxy || (apiProxy.isNotBlank() && imageMirrors.isNotEmpty())) {
            "Configure both the API proxy and an image mirror first."
        }
        return this
    }

    /** Secrets are persisted separately using SettingsStore's Android Keystore encryption. */
    fun encode(): String = JSONObject().put("route", route.name).put("apiProxy", apiProxy)
        .put("imageMirrors", JSONArray(imageMirrors)).put("source", source.name).put("cubUrl", cubUrl)
        .put("showFavorites", showFavorites).put("showWatchLater", showWatchLater).toString()

    companion object {
        const val PUBLIC_CUB_URL = "https://tmdb.cub.best/"
        const val PUBLIC_IMAGE_MIRROR = "https://imagetmdb.com/"
        val PUBLIC_IMAGE_MIRRORS = listOf(PUBLIC_IMAGE_MIRROR, "https://nl.imagetmdb.com/", "https://de.imagetmdb.com/")
        fun endpoint(value: String): HttpUrl {
            require(value.length <= 2048 && value == value.trim())
            val url = requireNotNull(value.toHttpUrlOrNull()) { "Invalid catalog endpoint." }
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Use an HTTPS base URL without credentials, query or fragment."
            }
            return url
        }
        fun decode(value: String?, token: String = ""): CatalogNetworkSettings = try {
            val j = JSONObject(value ?: "{}")
            val mirrors = j.optJSONArray("imageMirrors") ?: JSONArray()
            CatalogNetworkSettings(
                CatalogRoute.valueOf(j.optString("route", "Direct")), j.optString("apiProxy"),
                (0 until mirrors.length()).map { mirrors.getString(it) },
                CatalogSource.valueOf(j.optString("source", "Tmdb")), j.optString("cubUrl"), token,
                j.optBoolean("showFavorites", true), j.optBoolean("showWatchLater", true),
            ).validated()
        } catch (_: Exception) { CatalogNetworkSettings() }

        fun rebase(draft: CatalogNetworkSettings, base: CatalogNetworkSettings, current: CatalogNetworkSettings) = current.copy(
            route = if (draft.route != base.route) draft.route else current.route,
            apiProxy = if (draft.apiProxy != base.apiProxy) draft.apiProxy else current.apiProxy,
            imageMirrors = if (draft.imageMirrors != base.imageMirrors) draft.imageMirrors else current.imageMirrors,
            source = if (draft.source != base.source) draft.source else current.source,
            cubUrl = if (draft.cubUrl != base.cubUrl) draft.cubUrl else current.cubUrl,
            cubToken = if (draft.cubToken != base.cubToken) draft.cubToken else current.cubToken,
            showFavorites = if (draft.showFavorites != base.showFavorites) draft.showFavorites else current.showFavorites,
            showWatchLater = if (draft.showWatchLater != base.showWatchLater) draft.showWatchLater else current.showWatchLater,
        )
    }
}
