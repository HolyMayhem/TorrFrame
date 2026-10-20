package tv.torrframe.features.search

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import tv.torrframe.core.network.ServiceFailure

data class SearchResult(val id: String, val title: String, val link: String, val hash: String,
    val size: Long, val seeds: Int, val peers: Int, val tracker: String, val date: Long,
    val categories: Set<Int>) {
    val kind get() = when { categories.any { it in 2000..2999 } -> "Movies"; categories.any { it in 5000..5999 } -> "TV Shows"; else -> "Other" }
}
data class SearchPage(val results: List<SearchResult>, val partial: Boolean = false)
data class Capabilities(val search: Boolean, val movie: Boolean, val tv: Boolean)

object TorznabParser {
    private fun parser(xml: String, factory: () -> XmlPullParser): XmlPullParser {
        if (xml.length > 4 * 1024 * 1024 || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw ServiceFailure("Jackett", -2)
        return factory().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }
    }
    private fun error(p: XmlPullParser): Nothing = throw ServiceFailure("Jackett",
        if (p.getAttributeValue(null, "code") in setOf("100", "101", "102")) 401 else -2)
    private fun defaultParser() = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    fun caps(xml: String, factory: () -> XmlPullParser = ::defaultParser): Capabilities = try {
        val p = parser(xml, factory); var caps = false; var search = false; var movie = false; var tv = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when(p.name) {
                "error" -> error(p)
                "caps" -> caps = true
                "search" -> search = p.getAttributeValue(null, "available") == "yes"
                "movie-search" -> movie = p.getAttributeValue(null, "available") == "yes"
                "tv-search" -> tv = p.getAttributeValue(null, "available") == "yes"
            }; p.next()
        }
        if (!caps) throw ServiceFailure("Jackett", -2)
        Capabilities(search, movie, tv)
    } catch (e: ServiceFailure) { throw e } catch (e: Exception) { throw ServiceFailure("Jackett", -2) }
    fun results(xml: String, factory: () -> XmlPullParser = ::defaultParser): SearchPage {
        val p = parser(xml, factory); val items = mutableListOf<SearchResult>(); var fields: MutableMap<String, String>? = null
        var rss = false; var closed = false; var partial = false; var field: String? = null; var text = StringBuilder()
        try {
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when(p.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = p.name
                        when(name) {
                            "rss" -> rss = true
                            "error" -> { if (items.isEmpty()) error(p) else partial = true }
                            "item" -> { fields = mutableMapOf(); field = null }
                            "enclosure" -> fields?.let {
                                it["enclosure"] = p.getAttributeValue(null, "url") ?: ""
                                it["size"] = p.getAttributeValue(null, "length") ?: "0"
                            }
                            "attr" -> fields?.let {
                                val attr = p.getAttributeValue(null, "name")?.lowercase() ?: ""
                                val value = p.getAttributeValue(null, "value") ?: ""
                                it[attr] = if (attr == "category") (it[attr].orEmpty() + "," + value) else value
                            }
                            else -> { field = name; text = StringBuilder() }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if(p.name == "rss") closed = true
                        if (p.name == field) { fields?.let {
                            val value = text.toString().trim()
                            it[p.name] = if (p.name == "category") it[p.name].orEmpty() + "," + value else value
                        }; field = null }
                        if (p.name == "item") {
                            fields?.let { f ->
                                val link = f["magneturl"].orEmpty().ifBlank { f["enclosure"].orEmpty().ifBlank { f["link"].orEmpty() } }
                                if (link.startsWith("magnet:?", true) || link.startsWith("https://", true) || link.startsWith("http://", true)) {
                                    val hash = magnetHash("magnet:?xt=urn:btih:" + f["infohash"].orEmpty()).ifBlank { magnetHash(link) }
                                    val id = hash.ifBlank {
                                        val identity = f["guid"].orEmpty().takeIf { it.isNotBlank() }?.let { f["jackettindexer"].orEmpty() + "|" + it } ?: link
                                        java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                                    }
                                    if (items.size < 200) items += SearchResult(id, f["title"].orEmpty().ifBlank { "Torrent" }, link, hash,
                                        f["size"]?.toLongOrNull() ?: 0, f["seeders"]?.toIntOrNull() ?: 0,
                                        f["peers"]?.toIntOrNull() ?: 0, f["jackettindexer"].orEmpty(), parseDate(f["pubDate"].orEmpty()),
                                        f["category"].orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet())
                                }
                            }; fields = null
                        }
                    }
                }; p.next()
            }
        } catch (e: ServiceFailure) { throw e } catch (e: Exception) {
            if (items.isEmpty()) throw ServiceFailure("Jackett", -2) else partial = true
        }
        if (!rss) throw ServiceFailure("Jackett", -2)
        if(!closed) { if(items.isEmpty()) throw ServiceFailure("Jackett", -2); partial = true }
        return SearchPage(items.sortedByDescending { it.seeds }.distinctBy { it.id }, partial)
    }
    fun magnetHash(link: String): String {
        val raw = Regex("(?:[?&])xt=urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1) ?: return ""
        if (raw.matches(Regex("[a-fA-F0-9]{40}"))) return raw.lowercase()
        if (!raw.matches(Regex("[A-Z2-7]{32}", RegexOption.IGNORE_CASE))) return ""
        var buffer = 0; var bits = 0; val bytes = mutableListOf<Byte>()
        raw.uppercase(Locale.ROOT).forEach { c ->
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c); bits += 5
            if (bits >= 8) { bits -= 8; bytes += (buffer shr bits and 255).toByte() }
        }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun parseDate(value: String) = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value)?.time ?: 0
    }.getOrDefault(0)
}
