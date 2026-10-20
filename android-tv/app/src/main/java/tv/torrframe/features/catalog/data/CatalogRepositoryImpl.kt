package tv.torrframe.features.catalog.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import tv.torrframe.features.catalog.domain.*
import tv.torrframe.core.network.ServiceFailure

class CatalogRepositoryImpl(
    private val api: CatalogRemote, private val cache: CatalogCache, private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val namespace: String = "",
    private val provider: CatalogOrigin = CatalogOrigin.Tmdb,
) : CatalogRepository {
    private data class Request(val task: Deferred<String>, var users: Int)
    private val requests = mutableMapOf<String, Request>()
    private val lock = Mutex()
    private suspend fun cached(key: String): CatalogCache.Entry? = try { cache.read(key) }
        catch (e: Exception) { if (e is CancellationException) throw e; null }
    private suspend fun shared(key: String, action: suspend () -> String): String {
        val request = lock.withLock {
            requests[key]?.also { it.users++ } ?: Request(scope.async(start = CoroutineStart.LAZY) { action() }, 1).also { requests[key] = it }
        }
        try { return request.task.await() }
        finally { withContext(NonCancellable) { lock.withLock {
            request.users--
            if (request.users == 0) { requests.remove(key); if (!request.task.isCompleted) request.task.cancel() }
        } } }
    }
    private fun <T> load(rawKey: String, ttl: Long, retention: Long, force: Boolean, decode: (JSONObject) -> T, fetch: suspend () -> JSONObject) = flow {
        val key = namespace + rawKey
        var data: T? = null
        val entry = cached(key)
        if (entry != null) {
            data = runCatching { decode(JSONObject(entry.payload)) }.getOrNull()
            if (data == null) try { cache.remove(key) } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        val fresh = entry != null && data != null && entry.expiresAt > now()
        if (data != null) emit(CatalogLoad(data, loading = !fresh || force, stale = !fresh, source = provider))
        else emit(CatalogLoad(loading = true, source = provider))
        if (fresh && !force) return@flow
        try {
            val payload = shared(key) {
                // Another collector may have refreshed while this one was reading disk.
                val latest = cached(key)
                if (latest != null && latest.expiresAt > now() && (!force || latest.fetchedAt > (entry?.fetchedAt ?: 0)) && runCatching { decode(JSONObject(latest.payload)) }.isSuccess) latest.payload
                else {
                    val response = fetch(); decode(response)
                    val text = response.toString()
                    try { cache.write(key, text, ttl, retention) } catch (e: Exception) { if (e is CancellationException) throw e }
                    text
                }
            }
            emit(CatalogLoad(data = decode(JSONObject(payload)), source = provider))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emit(CatalogLoad(data, stale = data != null, error = true, failure = failure(e), source = provider))
        }
    }
    override fun section(section: CatalogSection, language: String, page: Int, force: Boolean) = load(
        "section:${section.name}:$language:$page", section.ttlHours * HOUR, 7 * DAY, force,
        { CatalogParser.page(it, section.type) }, { api.section(section, language, page) })
    override fun search(query: String, language: String, page: Int, force: Boolean) = load(
        "search:${digest(query.trim())}:$language:$page", HOUR, DAY, force,
        { CatalogParser.page(it) }, { api.search(query.trim(), language, page) })
    override fun details(ref: CatalogRef, language: String, force: Boolean) = load(
        "details:v2:${ref.key}:$language", 3 * DAY, 7 * DAY, force,
        { CatalogParser.details(it, ref.type) }, { api.details(ref, language) })
    override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) = load(
        "related:${source.path}:${ref.key}:$language:$page", DAY, 7 * DAY, force,
        { CatalogParser.page(it, ref.type).let { page -> page.copy(items = page.items.filterNot { it.ref == ref }) } },
        { api.related(ref, source, language, page) })
    override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean) = load(
        "discover:${filter.name}:${type.path}:$language:$page", 12 * HOUR, 7 * DAY, force,
        { CatalogParser.page(it, type) }, { api.discover(filter, type, language, page) })
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun failure(error: Exception): CatalogFailure {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        val status = causes.filterIsInstance<ServiceFailure>().firstOrNull { it.status != 0 }?.status
        val kind = when {
            causes.any { it is java.net.UnknownHostException } -> CatalogFailureKind.Dns
            causes.any { it is java.io.InterruptedIOException } || status == 408 -> CatalogFailureKind.Timeout
            causes.any { it is javax.net.ssl.SSLException } -> CatalogFailureKind.Tls
            status == 401 || status == 403 -> CatalogFailureKind.Denied
            status == 429 -> CatalogFailureKind.RateLimited
            status == -1 -> CatalogFailureKind.TooLarge
            status == -2 || causes.any { it is org.json.JSONException || it is IllegalArgumentException } -> CatalogFailureKind.InvalidResponse
            status != null && status > 0 -> CatalogFailureKind.Http
            causes.any { it is java.io.IOException } -> CatalogFailureKind.Connection
            else -> CatalogFailureKind.Unknown
        }
        return CatalogFailure(provider, kind, status?.takeIf { it > 0 })
    }
    companion object { const val HOUR = 3_600_000L; const val DAY = 24 * HOUR }
}
