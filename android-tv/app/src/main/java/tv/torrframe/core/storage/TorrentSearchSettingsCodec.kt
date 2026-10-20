package tv.torrframe.core.storage

import org.json.JSONArray
import org.json.JSONObject
import tv.torrserve.torrentsearch.model.*

internal object TorrentSearchSettingsCodec {
    fun encode(value: TorrentSearchOptions): String = JSONObject().apply {
        value.enabledProviders?.let { put("sources",JSONArray(it.sorted())) }
        put("resolutions",JSONArray(value.resolutions.sorted()));put("formats",JSONArray(value.formats.sorted()))
        put("minBytes",value.minBytes);put("maxBytes",value.maxBytes);put("minSeeds",value.minSeeds)
        put("hideZero",value.hideZeroSeeds);put("timeout",value.sourceTimeoutMs);put("titleMode",value.titleMode.name)
    }.toString()
    fun decode(text: String?): TorrentSearchOptions = runCatching {
        val j=JSONObject(text.orEmpty())
        fun strings(key:String)=j.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet() }
        TorrentSearchOptions(strings("sources"),strings("resolutions").orEmpty(),strings("formats").orEmpty(),
            j.optLong("minBytes",0),j.optLong("maxBytes",0),j.optLong("minSeeds",0),j.optBoolean("hideZero",false),
            j.optLong("timeout",18_000),SearchTitleMode.entries.firstOrNull { it.name==j.optString("titleMode") } ?: SearchTitleMode.Both).normalized()
    }.getOrDefault(TorrentSearchOptions())
}
