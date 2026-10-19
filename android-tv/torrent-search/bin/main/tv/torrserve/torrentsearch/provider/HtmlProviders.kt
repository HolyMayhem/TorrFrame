/* Search URL construction and selectors adapted from prajwalch/TorrentSearch,
 * commit 661c5adf3493add41ed2a6bda422b22d8aa73533. MIT, Copyright (c) 2026 Prajwal Chapagain.
 * Only search and lazy magnet resolution are retained; no upstream UI, browser or challenge solver. */
package tv.torrserve.torrentsearch.provider

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tv.torrserve.torrentsearch.model.*

internal fun html(data: ByteArray, url: String): Document {
    val doc = Jsoup.parse(data.inputStream(), null, url)
    require(!doc.title().contains("Just a moment",true) && !doc.title().contains("Attention Required",true)) { "Source challenge" }
    return doc
}
internal fun Element.value(selector: String) = selectFirst(selector)?.text()?.trim().orEmpty()
internal fun Element.count(selector: String) = value(selector).replace(",", "").replace(" ", "").toLongOrNull()?.takeIf { it >= 0 }
internal fun Element.url(selector: String) = selectFirst(selector)?.attr("abs:href")?.let(TorrentIdentity::web)
internal fun Element.magnet(selector: String) = selectFirst(selector)?.attr("href")?.takeIf { TorrentIdentity.hash(it) != null }

class RutorProvider(private val network: SearchTransport, private val base: String = "https://rutor.info") : SearchProvider {
    override val id = "rutorinfo"; override val name = "Rutor"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val url = "$base/search/0/${if(type==MediaType.Movie)1 else 4}/010/2/${TorrentIdentity.encode(query)}"
        return parse(network.get(url),url)
    }
    fun parse(data: ByteArray, url: String) = html(data,url).select("div#index > table > tbody > tr").drop(1).mapNotNull { row ->
        row.magnet("td:nth-child(2) > a:nth-child(2)")?.let { magnet ->
            TorrentResult(id,name,row.value("td:nth-child(2) > a:nth-child(3)"),row.count("td:nth-last-child(1) > span:nth-child(1)"),
                row.count("td:nth-last-child(1) > span:nth-child(3)"),sizeText=row.value("td:nth-last-child(2)"),magnet=magnet,
                torrentUrl=row.url("td:nth-child(2) > a:nth-child(1)"),detailsUrl=row.url("td:nth-child(2) > a:nth-child(3)"))
        }
    }
}
class NyaaProvider(private val network: SearchTransport, private val base: String = "https://nyaa.si") : SearchProvider {
    override val id = "nyaasi"; override val name = "Nyaa"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val url = "$base/?f=0&c=1_0&q=${TorrentIdentity.encode(query)}&s=seeders&o=desc"
        return parse(network.get(url),url)
    }
    fun parse(data: ByteArray, url: String) = html(data,url).select("table.torrent-list > tbody > tr").mapNotNull { row ->
        row.magnet("td:nth-child(3) > a:nth-child(2)")?.let { magnet ->
            TorrentResult(id,name,row.value("td:nth-child(2) > a:not(.comments)"),row.count("td:nth-child(6)"),row.count("td:nth-child(7)"),
                sizeText=row.value("td:nth-child(4)"),magnet=magnet,torrentUrl=row.url("td:nth-child(3) > a:nth-child(1)"),
                detailsUrl=row.url("td:nth-child(2) > a:not(.comments)"))
        }
    }
}
class EztvProvider(private val network: SearchTransport, private val base: String = "https://eztvx.to") : SearchProvider {
    override val id = "eztvx"; override val name = "EZTV"
    override val types = setOf(MediaType.Series)
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val url = "$base/search/${TorrentIdentity.encode(query)}"
        return parse(network.get(url,mapOf("Cookie" to "layout=def_wlinks")),url)
    }
    fun parse(data: ByteArray, url: String) = html(data,url).select("table:last-of-type > tbody > tr").drop(2).mapNotNull { row ->
        row.magnet("td:nth-child(3) > a.magnet")?.let { magnet ->
            TorrentResult(id,name,row.value("td:nth-child(2) > a.epinfo"),row.count("td:nth-child(6)"),null,
                sizeText=row.value("td:nth-child(4)"),magnet=magnet,torrentUrl=row.url("td:nth-child(3) > a:nth-child(2)"),
                detailsUrl=row.url("td:nth-child(2) > a.epinfo"))
        }
    }
}
class X1337Provider(private val network: SearchTransport, private val base: String = "https://1337x.to") : SearchProvider {
    override val id = "1337x"; override val name = "1337x"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val url = "$base/category-search/${TorrentIdentity.encode(query)}/${if(type==MediaType.Movie)"Movies" else "TV"}/1/"
        return parse(network.get(url),url)
    }
    fun parse(data: ByteArray, url: String) = html(data,url).select("table.table-list > tbody > tr").mapNotNull { row ->
        row.url("td.name > a:nth-child(2)")?.let { details ->
            TorrentResult(id,name,row.value("td.name > a:nth-child(2)"),row.count("td.seeds"),row.count("td.leeches"),
                sizeText=row.selectFirst("td.size")?.ownText()?.trim(),detailsUrl=details)
        }
    }
    override suspend fun resolve(result: TorrentResult): TorrentLink {
        val url = requireNotNull(result.detailsUrl)
        require(java.net.URI(url).host == java.net.URI(base).host)
        val magnet = html(network.get(url),url).selectFirst("a[href^=magnet:]")?.attr("href")
        require(magnet != null && TorrentIdentity.hash(magnet) != null) { "Magnet unavailable" }
        return TorrentLink.Magnet(magnet)
    }
}
class NoNameClubProvider(private val network: SearchTransport, private val base: String = "https://nnmclub.to") : SearchProvider {
    override val id = "nonameclub"; override val name = "NoNameClub"
    override suspend fun search(query: String, type: MediaType): List<TorrentResult> {
        val fields = mapOf("f[]" to if(type==MediaType.Movie)"216" else "768","o" to "10","s" to "2","tm" to "-1",
            "shf" to "1","ta" to "-1","sns" to "-1","sds" to "4","nm" to query,"submit" to "Поиск")
        val body = fields.entries.joinToString("&") { "${TorrentIdentity.encode(it.key)}=${TorrentIdentity.encode(it.value)}" }
        val url = "$base/forum/tracker.php"
        return parse(network.post(url,body,"application/x-www-form-urlencoded"),url)
    }
    fun parse(data: ByteArray, url: String) = html(data,url)
        .select("table.forumline.tablesorter > tbody > tr:has(a[href^=viewtopic.php?t=]):has(a[href^=download.php?id=])").mapNotNull { row ->
            row.url("a[href^=viewtopic.php?t=]")?.let { details ->
                TorrentResult(id,name,row.value("a[href^=viewtopic.php?t=] > b"),row.count("td.seedmed > b"),row.count("td.leechmed > b"),
                    sizeBytes=row.value("td:nth-child(6) > u").toLongOrNull(),torrentUrl=row.url("a[href^=download.php?id=]"),detailsUrl=details)
            }
        }
    override suspend fun resolve(result: TorrentResult): TorrentLink {
        val url = requireNotNull(result.detailsUrl)
        require(java.net.URI(url).host == java.net.URI(base).host)
        val magnet = html(network.get(url),url).selectFirst("a[href^=magnet:]")?.attr("href")
        if (magnet != null && TorrentIdentity.hash(magnet) != null) return TorrentLink.Magnet(magnet)
        return super.resolve(result)
    }
}

fun builtinProviders(network: SearchTransport): List<SearchProvider> = listOf(
    RutorProvider(network),NoNameClubProvider(network),TorrentsCsvProvider(network),KnabenProvider(network),
    PirateBayProvider(network),YtsProvider(network),NyaaProvider(network),X1337Provider(network),EztvProvider(network))
