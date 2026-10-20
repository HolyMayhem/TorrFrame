package tv.torrframe.core.network

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** DNS recovery for built-in catalog/image hosts and TorrentsCSV; LAN/custom endpoints use system DNS. */
class TmdbDns(
    private val system: Dns = Dns.SYSTEM,
    private val secureClient: OkHttpClient = OkHttpClient.Builder().dns(bootstrapDns())
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build(),
    private val endpoint: HttpUrl = "https://dns.google/resolve".toHttpUrl(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Dns {
    private data class Cached(val addresses: List<InetAddress>, val expires: Long)
    private val hosts = setOf("api.themoviedb.org", "image.tmdb.org", "tmdb.cub.best", "torrents-csv.com") +
        CatalogNetworkSettings.PUBLIC_IMAGE_MIRRORS.map { it.toHttpUrl().host }
    private val cache = ConcurrentHashMap<String, Cached>()
    // A stalled lookup must not hold up another host, including its cached answer.
    // The fixed allowlist also bounds the number of locks and cache entries.
    private val lookups = hosts.associateWith { Any() }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        if (host !in hosts) return system.lookup(hostname)
        return synchronized(lookups.getValue(host)) { lookupTmdb(host, hostname) }
    }

    private fun lookupTmdb(host: String, hostname: String): List<InetAddress> {
        cache[host]?.takeIf { it.expires > now() }?.let { return it.addresses }
        cache.remove(host)
        val ordinary = try { system.lookup(hostname).filter(::publicAddress) } catch (_: UnknownHostException) { emptyList() }
        if (ordinary.isNotEmpty()) return ordinary

        // Separate synchronous client prevents recursion through this resolver and does not send API keys.
        val url = endpoint.newBuilder().addQueryParameter("name", host).addQueryParameter("type", "A")
            // Do not forward the client's subnet: ordinary ECS answers can redirect TMDB to loopback.
            .addQueryParameter("edns_client_subnet", "0.0.0.0/0").build()
        try {
            secureClient.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Secure DNS returned HTTP ${response.code}")
                if (response.body.contentLength() > 16384) throw IOException("Secure DNS response is too large")
                val payload = response.body.byteStream().use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > 16384) throw IOException("Secure DNS response is too large")
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
                val json = JSONObject(payload)
                if (json.optInt("Status", -1) != 0 || json.optBoolean("TC")) throw IOException("Secure DNS lookup failed")
                val answers = json.optJSONArray("Answer") ?: throw IOException("Secure DNS has no answers")
                val addresses = mutableListOf<InetAddress>()
                var ttl = 300L
                for (i in 0 until answers.length()) {
                    val answer = answers.getJSONObject(i)
                    ttl = minOf(ttl, answer.optLong("TTL", 30).coerceAtLeast(0))
                    // Only literal A records. Never resolve returned names through the poisoned system resolver.
                    if (answer.optInt("type") != 1) continue
                    val labels = answer.optString("data").split('.')
                    if (labels.size != 4) continue
                    val numbers = labels.map { it.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }?.toIntOrNull() }
                    if (numbers.any { it == null || it !in 0..255 }) continue
                    val address = InetAddress.getByAddress(host, numbers.map { it!!.toByte() }.toByteArray())
                    if (publicAddress(address)) {
                        addresses += address
                    }
                }
                if (addresses.isEmpty()) throw IOException("Secure DNS has no public addresses")
                val result = addresses.distinct()
                if (ttl > 0) cache[host] = Cached(result, now() + ttl * 1000)
                return result
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is org.json.JSONException) throw e
            throw UnknownHostException("Cannot resolve catalog host using system or secure DNS").apply { initCause(e) }
        }
    }

    companion object {
        // Connect to the existing DoH service without first depending on broken router DNS.
        // The HTTPS URL, SNI and certificate verification still use dns.google.
        // https://developers.google.com/speed/public-dns/docs/using#google_public_dns_ip_addresses
        internal fun bootstrapDns(system: Dns = Dns.SYSTEM): Dns = Dns { hostname ->
            if (hostname.lowercase(Locale.ROOT).trimEnd('.') == "dns.google") listOf(
                InetAddress.getByAddress("dns.google", byteArrayOf(8, 8, 8, 8)),
                InetAddress.getByAddress("dns.google", byteArrayOf(8, 8, 4, 4)))
            else system.lookup(hostname)
        }
    }

    private fun publicAddress(address: InetAddress) = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
}
