package tv.torrframe.features.torrentsearch.data

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.coroutineContext
import tv.torrframe.R
import tv.torrframe.core.network.Http
import tv.torrframe.core.network.ServiceFailure
import tv.torrframe.core.server.ApplicationActionFailure
import tv.torrframe.core.storage.JacRedMode
import tv.torrframe.core.storage.JacRedSettings
import tv.torrserve.torrentsearch.model.MediaType
import tv.torrserve.torrentsearch.model.SearchTarget
import tv.torrserve.torrentsearch.model.SearchTitleMode
import tv.torrserve.torrentsearch.model.TorrentIdentity
import tv.torrserve.torrentsearch.model.TorrentResult
import tv.torrserve.torrentsearch.provider.SearchProvider

class JacRedSearchProvider(
    private val http: Http,
    private val settings: () -> JacRedSettings,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val automaticUrls: List<String> = JacRedMode.publicUrls.shuffled(),
    private val preferredUrl: () -> String? = { null },
    private val rememberUrl: (String) -> Unit = {},
    private val adaptive: AdaptiveSearchNetwork? = null
) : SearchProvider {
    override val id = ID
    override val name = "JacRed"
    override val configured get() = settings().configured

    private data class Query(val title: String, val alternate: String? = null, val year: Int? = null, val exact: Boolean = false)
    private data class Cached(val savedAt: Long, val rows: List<TorrentResult>) {
        fun fresh(now: Long) = now - savedAt < if (rows.isEmpty()) 60_000L else 300_000L
    }
    private data class Backoff(val startedAt: Long, val duration: Long, val status: Int)
    private val lock = Mutex()
    private val cache = LinkedHashMap<Pair<Query, String>, Cached>()
    private val backoffs = LinkedHashMap<String, Backoff>()
    private val publicOrigins = automaticUrls.map { it.toHttpUrl() }
    private var connection: JacRedSettings? = null

    override suspend fun search(query: String, type: MediaType) =
        search(SearchTarget(query, type = type), SearchTitleMode.Original, 18_000)

    override fun searchBatches(target: SearchTarget, mode: SearchTitleMode, timeoutMs: Long) = flow {
        emit(search(target, mode, timeoutMs))
    }

    private suspend fun search(target: SearchTarget, mode: SearchTitleMode, timeoutMs: Long): List<TorrentResult> {
        val measurement = coroutineContext[SearchMeasurement]
        val settings = measurement?.jacRed ?: settings()
        val titles = target.titles(mode)
        if (!settings.configured || titles.isEmpty()) return emptyList()
        // Series search also accepts releases tagged with a later season's year.
        val query = Query(titles[0], titles.getOrNull(1), target.year?.takeIf { target.type == MediaType.Movie })
        return lock.withLock {
            val cacheKey = query to adaptive?.cacheIdentity().orEmpty()
            if (measurement == null) {
                useConnection(settings)
                cache[cacheKey]?.takeIf { it.fresh(now()) }?.let { return@withLock it.rows }
            }
            val json = fetch(settings, query, timeoutMs)
            val rows = (0 until minOf(json.length(), 2_000)).mapNotNull { json.optJSONObject(it)?.let(::result) }
            if (measurement == null && cacheKey.second == adaptive?.cacheIdentity().orEmpty()) {
                cache.remove(cacheKey)
                if (cache.size >= 24) cache.remove(cache.keys.first())
                cache[cacheKey] = Cached(now(), rows)
            }
            rows
        }
    }

    suspend fun test(settings: JacRedSettings) {
        if (!settings.configured) throw ApplicationActionFailure(R.string.jacred_invalid_url)
        try {
            val response = withTimeoutOrNull(5_000) {
                lock.withLock {
                    useConnection(settings)
                    fetch(settings, Query("TorrFrame", exact = true), 5_000)
                }
            }
            if (response == null) throw ApplicationActionFailure(R.string.jacred_timeout)
        } catch (failure: ServiceFailure) {
            throw ApplicationActionFailure(when (failure.status) {
                401, 403 -> R.string.jacred_access_denied
                408 -> R.string.jacred_timeout
                429 -> R.string.jacred_rate_limited
                301, 302, 303, 307, 308, 404, -2 -> R.string.jacred_api_unavailable
                else -> R.string.jacred_connection_failed
            })
        }
    }

    private fun useConnection(settings: JacRedSettings) {
        if (connection == settings) return
        connection = settings
        cache.clear()
    }

    private suspend fun fetch(settings: JacRedSettings, query: Query, timeoutMs: Long): JSONArray {
        val measurement = coroutineContext[SearchMeasurement]
        val preferred = when {
            settings.mode == JacRedMode.Custom -> null
            measurement != null -> measurement.jacRedPreferred
            else -> preferredUrl()
        }
        val addresses = when (settings.mode) {
            JacRedMode.Auto -> automaticUrls.sortedBy { it != preferred }
            JacRedMode.Custom -> listOf(settings.url)
            else -> listOf(requireNotNull(settings.mode.publicUrl))
        }
        val key = settings.apiKey.takeIf { settings.mode == JacRedMode.Custom }.orEmpty()
        for ((index, address) in addresses.withIndex()) {
            val hasAlternate = index < addresses.lastIndex
            val attemptTimeout = if (hasAlternate) minOf(5_000L, timeoutMs / 2) else timeoutMs
            try {
                val response = withTimeoutOrNull(attemptTimeout) { request(key, endpoint(address, query), settings.mode == JacRedMode.Custom) }
                    ?: throw ServiceFailure(name, 408)
                if (measurement == null && settings.mode != JacRedMode.Custom && address != preferred) rememberUrl(address)
                return response
            } catch (failure: ServiceFailure) {
                if (!hasAlternate || failure.retryAfterMillis != null || !canFailOver(failure.status)) throw failure
            }
        }
        error("No JacRed endpoint configured")
    }

    private fun canFailOver(status: Int) = status == 0 || status == 408 || status in 500..599

    private fun endpoint(address: String, query: Query) = address.toHttpUrl().newBuilder()
        .addPathSegments("api/v1.0/torrents").addQueryParameter("search", query.title).apply {
            query.alternate?.let { addQueryParameter("altname", it) }
            query.year?.let { addQueryParameter("relased", it.toString()) }
            if (query.exact) addQueryParameter("exact", "true")
        }.build()

    private suspend fun request(key: String, url: HttpUrl, allowLocal: Boolean): JSONArray {
        // The public mirrors can share quota. Switching addresses must honor the same pause.
        val bucket = if (publicOrigins.any { it.host == url.host && it.port == url.port }) "public" else "${url.host}:${url.port}"
        backoffs[bucket]?.let { limit ->
            val remaining = limit.duration - (now() - limit.startedAt)
            if (remaining > 0) throw ServiceFailure(name, limit.status, retryAfterMillis = remaining)
            backoffs.remove(bucket)
        }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "TorrFrame-TorrentSearch/1.0").apply {
                if (key.isNotEmpty()) header("X-Api-Key", key)
            }.build()
        val reply = try {
            // The custom API-key header must stay on the configured endpoint.
            adaptive?.exchange(request, name, 3 * 1024 * 1024, manualRedirects = true, allowLocal = allowLocal)
                ?: http.exchange(request, name, 3 * 1024 * 1024, manualRedirects = true)
        } catch (failure: ServiceFailure) {
            if (failure.status == 429 || (failure.status in 500..599 && failure.retryAfterMillis != null)) {
                backoffs.remove(bucket)
                if (backoffs.size >= 24) backoffs.remove(backoffs.keys.first())
                backoffs[bucket] = Backoff(now(), (failure.retryAfterMillis ?: 60_000L).coerceAtLeast(1_000L), failure.status)
            }
            throw failure
        }
        if (reply.status !in 200..299) throw ServiceFailure(name, reply.status)
        return try {
            when (val result = JSONTokener(reply.data.toString(Charsets.UTF_8)).nextValue()) {
                is JSONArray -> result
                is JSONObject -> {
                    // JacBlack returns an empty object when the search finds no releases.
                    if (result.length() != 0) throw JSONException("Expected torrent results")
                    JSONArray()
                }
                else -> throw JSONException("Expected torrent results")
            }
        } catch (error: JSONException) {
            throw ServiceFailure(name, -2, cause = error)
        }
    }

    private fun result(row: JSONObject): TorrentResult? {
        fun text(field: String) = (row.opt(field) as? String)?.trim()?.takeIf(String::isNotEmpty)
        fun count(primary: String, alternate: String) = row.optLong(primary, -1).takeIf { it >= 0 }
            ?: row.optLong(alternate, -1).takeIf { it >= 0 }
        val title = text("title")?.take(500) ?: return null
        val magnet = text("magnet")?.takeIf { TorrentIdentity.hash(it) != null } ?: return null
        return TorrentResult(id, name, title, seeds = count("sid", "seeders"), peers = count("pir", "peers"),
            sizeBytes = row.optLong("size", 0).takeIf { it > 0 }, sizeText = text("sizeName"),
            magnet = magnet, detailsUrl = TorrentIdentity.web(text("url")),
            trackers = setOf(text("tracker")?.take(100) ?: name))
    }

    companion object { const val ID = "jacred" }
}
