/* Search endpoints and field mappings adapted from prajwalch/TorrentSearch,
 * commit 661c5adf3493add41ed2a6bda422b22d8aa73533. MIT, Copyright (c) 2026 Prajwal Chapagain.
 * See THIRD_PARTY_NOTICES.md and LICENSE-TorrentSearch.txt. */
package tv.torrserve.torrentsearch.provider

import org.json.JSONArray
import org.json.JSONObject
import tv.torrserve.torrentsearch.model.*

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.text(key: String) = opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
internal fun JSONObject.number(key: String) = text(key).toLongOrNull()?.takeIf { it >= 0 }

class TorrentsCsvProvider(private val network: SearchTransport, private val base: String = "https://torrents-csv.com") : SearchProvider {
    override val id = "torrentscsv"; override val name = "TorrentsCSV"
    override suspend fun search(query: String, type: MediaType) = parse(network.get("$base/service/search?q=${TorrentIdentity.encode(query)}"))
    fun parse(data: ByteArray): List<TorrentResult> = requireNotNull(JSONObject(data.toString(Charsets.UTF_8)).optJSONArray("torrents")).objects().mapNotNull { j ->
        TorrentIdentity.magnet(j.text("infohash"), j.text("name"))?.let { magnet ->
            TorrentResult(id,name,j.text("name"),j.number("seeders"),j.number("leechers"),j.number("size_bytes"),magnet=magnet)
        }
    }
}
class PirateBayProvider(private val network: SearchTransport, private val base: String = "https://apibay.org") : SearchProvider {
    override val id = "thepiratebay"; override val name = "ThePirateBay"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val category = 200 // Upstream video category includes HD/UHD movies and TV.
        return parse(network.get("$base/q.php?q=${TorrentIdentity.encode(query)}&cat=$category"))
    }
    fun parse(data: ByteArray) = JSONArray(data.toString(Charsets.UTF_8)).objects().mapNotNull { j ->
        if (j.text("name") == "No results returned") null else TorrentIdentity.magnet(j.text("info_hash"),j.text("name"))?.let { magnet ->
            TorrentResult(id,name,j.text("name"),j.number("seeders"),j.number("leechers"),j.number("size"),magnet=magnet)
        }
    }
}
class KnabenProvider(private val network: SearchTransport, private val base: String = "https://api.knaben.org") : SearchProvider {
    override val id = "knaben"; override val name = "Knaben"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val body = JSONObject().put("query",query).put("size",150).put("order_by","seeders").put("order_direction","desc")
            .put("hide_unsafe",true).put("hide_xxx",true).put("categories",JSONArray().put(if(type==MediaType.Movie)3000000 else 2000000))
        return parse(network.post("$base/v1",body.toString(),"application/json"))
    }
    fun parse(data: ByteArray) = requireNotNull(JSONObject(data.toString(Charsets.UTF_8)).optJSONArray("hits")).objects().mapNotNull { j ->
        j.text("magnetUrl").takeIf { TorrentIdentity.hash(it) != null }?.let { magnet ->
            TorrentResult(id,name,j.text("title"),j.number("seeders"),j.number("peers"),j.number("bytes"),magnet=magnet,
                detailsUrl=TorrentIdentity.web(j.text("details")))
        }
    }
}
class YtsProvider(private val network: SearchTransport, private val base: String = "https://movies-api.accel.li/api/v2") : SearchProvider {
    override val id = "ytsmx"; override val name = "YTS"
    override val types = setOf(MediaType.Movie)
    override suspend fun search(query: String, type: MediaType) = parse(network.get("$base/list_movies.json?query_term=${TorrentIdentity.encode(query)}&limit=50"))
    fun parse(data: ByteArray): List<TorrentResult> {
        val root = JSONObject(data.toString(Charsets.UTF_8))
        require(root.optString("status") != "error")
        val movies = requireNotNull(root.optJSONObject("data"))
        if (movies.optInt("movie_count",-1) == 0) return emptyList()
        return requireNotNull(movies.optJSONArray("movies")).objects().flatMap { movie ->
            movie.optJSONArray("torrents")?.objects().orEmpty().mapNotNull { j ->
                val title = "${movie.text("title_long")} [${j.text("quality")}] [${j.text("type")}] [${j.text("video_codec")}]"
                TorrentIdentity.magnet(j.text("hash"),title)?.let { magnet ->
                    TorrentResult(id,name,title,j.number("seeds"),j.number("peers"),j.number("size_bytes"),j.text("size"),magnet,
                        TorrentIdentity.web(j.text("url")),TorrentIdentity.web(movie.text("url")))
                }
            }
        }
    }
}
