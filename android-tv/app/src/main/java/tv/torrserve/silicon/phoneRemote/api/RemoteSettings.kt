package tv.torrserve.silicon.phoneRemote.api

import org.json.JSONObject
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.silicon.features.player.*

/** Only explicitly editable preferences cross the API. Server changes use TorrServerActions. */
internal object RemoteSettings {
    private val fields = setOf("jackettEnabled", "jackettUrl", "jackettKey", "metadataOrder", "aniListEnabled", "language", "resume",
        "audioLanguage", "subtitleLanguage", "updateRepository", "uiLanguage", "uiTheme", "uiAccent",
        "uiScalePercent", "animationsEnabled", "showNavigationHints", "playerMode", "externalPlayer", "builtInPlayer", "torrentSearch", "catalogNetwork")
    fun edit(current: Settings, edits: JSONObject): Settings {
        require(edits.keys().asSequence().all { it in fields })
        fun text(key: String, old: String, limit: Int) = if (edits.has(key)) edits.getString(key).also { require(it.length <= limit) } else old
        fun flag(key: String, old: Boolean): Boolean {
            if (!edits.has(key)) return old
            require(edits.get(key) is Boolean)
            return edits.getBoolean(key)
        }
        val player = merge(JSONObject(current.builtInPlayer.encode()), if (edits.has("builtInPlayer")) edits.getJSONObject("builtInPlayer") else null)
        require(player.getDouble("speed").toFloat() in PlayerPreferences.SPEEDS)
        require(player.getInt("subtitleSize") in PlayerPreferences.SUBTITLE_SIZES)
        require(player.getString("aspect") in VideoAspect.entries.map { it.name })
        require(player.getString("decoder") in AudioDecoder.entries.map { it.name })
        require(player.getString("subtitleColor") in SubtitleColor.entries.map { it.name })
        val search = merge(JSONObject(TorrentSearchSettingsCodec.encode(current.torrentSearch)), if (edits.has("torrentSearch")) edits.getJSONObject("torrentSearch") else null, setOf("sources"))
        val options = TorrentSearchSettingsCodec.decode(search.toString())
        require(search.getLong("minBytes") == options.minBytes && search.getLong("maxBytes") == options.maxBytes &&
            search.getLong("minSeeds") == options.minSeeds && search.getLong("timeout") == options.sourceTimeoutMs &&
            search.getString("titleMode") == options.titleMode.name)
        for ((key, allowed) in listOf("resolutions" to tv.torrserve.torrentsearch.model.TorrentSearchOptions.RESOLUTIONS,
            "formats" to tv.torrserve.torrentsearch.model.TorrentSearchOptions.FORMATS)) {
            val values = search.getJSONArray(key)
            require((0 until values.length()).all { values.getString(it) in allowed })
        }
        val language = text("language", current.language, 5); require(language in listOf("en-US", "ru-RU"))
        val uiLanguage = text("uiLanguage", current.uiLanguage, 2); require(uiLanguage in listOf("en", "ru"))
        val theme = text("uiTheme", current.uiTheme, 5); require(theme in listOf("dark", "light"))
        val accent = text("uiAccent", current.uiAccent, 10); require(accent in UiAccent.colors)
        val scale = edits.optInt("uiScalePercent", current.uiScalePercent); require(scale == UiScale.normalized(scale))
        val mode = text("playerMode", current.playerMode.name, 10); require(mode in PlayerMode.entries.map { it.name })
        val order = edits.optJSONArray("metadataOrder")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: current.metadataOrder
        val network = catalogNetwork(current.catalogNetwork, if (edits.has("catalogNetwork")) edits.getJSONObject("catalogNetwork") else null)
        return current.copy(jackettEnabled = flag("jackettEnabled", current.jackettEnabled), jackettUrl = text("jackettUrl", current.jackettUrl, 4096), jackettKey = text("jackettKey", current.jackettKey, 4096),
            metadataOrder = MetadataPriority.normalized(order), aniListEnabled = flag("aniListEnabled", current.aniListEnabled), language = language,
            resume = flag("resume", current.resume), audioLanguage = text("audioLanguage", current.audioLanguage, 40),
            subtitleLanguage = text("subtitleLanguage", current.subtitleLanguage, 40), updateRepository = text("updateRepository", current.updateRepository, 100),
            uiLanguage = uiLanguage, uiTheme = theme, uiAccent = accent, uiScalePercent = scale,
            animationsEnabled = flag("animationsEnabled", current.animationsEnabled), showNavigationHints = flag("showNavigationHints", current.showNavigationHints),
            playerMode = PlayerMode.valueOf(mode), externalPlayer = text("externalPlayer", current.externalPlayer, 500),
            builtInPlayer = PlayerPreferences.decode(player.toString()), torrentSearch = options, catalogNetwork = network)
    }
    private fun catalogNetwork(current: tv.torrserve.silicon.core.network.CatalogNetworkSettings, edits: JSONObject?): tv.torrserve.silicon.core.network.CatalogNetworkSettings {
        if (edits == null) return current
        require(edits.keys().asSequence().all { it in setOf("route", "source", "apiProxy", "imageMirrors", "cubUrl", "cubToken", "showFavorites", "showWatchLater") })
        fun text(key: String, old: String) = if (edits.has(key)) edits.getString(key) else old
        fun flag(key: String, old: Boolean): Boolean {
            if (!edits.has(key)) return old
            require(edits.get(key) is Boolean)
            return edits.getBoolean(key)
        }
        val mirrors = if (edits.has("imageMirrors")) edits.getJSONArray("imageMirrors").let { a ->
            require(a.length() <= 3); (0 until a.length()).map { a.getString(it) }
        } else current.imageMirrors
        return current.copy(route = tv.torrserve.silicon.core.network.CatalogRoute.valueOf(text("route", current.route.name)),
            source = tv.torrserve.silicon.core.network.CatalogSource.valueOf(text("source", current.source.name)),
            apiProxy = text("apiProxy", current.apiProxy), imageMirrors = mirrors,
            cubUrl = text("cubUrl", current.cubUrl), cubToken = text("cubToken", current.cubToken),
            showFavorites = flag("showFavorites", current.showFavorites), showWatchLater = flag("showWatchLater", current.showWatchLater)).validated()
    }
    private fun merge(base: JSONObject, edits: JSONObject?, optional: Set<String> = emptySet()): JSONObject {
        edits?.keys()?.asSequence()?.forEach { key ->
            require(base.has(key) || key in optional)
            if (edits.isNull(key) && key in optional) base.remove(key) else base.put(key, edits.get(key))
        }
        return base
    }
}
