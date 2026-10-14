package tv.torrserve.silicon.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import tv.torrserve.silicon.features.metadata.BuiltInMetadataKeys
import kotlin.random.Random
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Shared TMDB auth, cancellation and rate budget for metadata and catalog requests. */
class TmdbClient(
    private val http: Http,
    private val key: () -> String = { BuiltInMetadataKeys.forProvider("TMDB") },
    private val baseUrl: String = "https://api.themoviedb.org/3/",
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Long = { Random.nextLong(100, 350) },
) {
    private val permits = Semaphore(2)
    @Volatile private var nextAllowedAt = 0L
    suspend fun get(path: String, parameters: Map<String, String>): JSONObject = permits.withPermit {
        val url = (baseUrl.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl().newBuilder()
        parameters.forEach { (k, v) -> url.addQueryParameter(k, v) }
        url.addQueryParameter("api_key", key())
        val request = Request.Builder().url(url.build()).build()
        for (attempt in 0..2) {
            val wait = (nextAllowedAt - now()).coerceAtLeast(0)
            // Long Retry-After values remain a shared cooldown, rather than tying up a screen.
            if (wait > 30_000) throw ServiceFailure("TMDB", 429, wait)
            if (wait > 0) pause(wait)
            try {
                return@withPermit JSONObject(http.bytes(request, "TMDB").toString(Charsets.UTF_8))
            } catch (e: ServiceFailure) {
                if (e.status != 429) throw e
                val backoff = maxOf(e.retryAfterMillis ?: 0L, (1000L shl attempt) + jitter())
                synchronized(this) { nextAllowedAt = maxOf(nextAllowedAt, now() + backoff) }
                if (attempt == 2 || backoff > 30_000) throw e
            }
        }
        error("Unreachable")
    }
}

internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    value.trim().toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
    return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
}
