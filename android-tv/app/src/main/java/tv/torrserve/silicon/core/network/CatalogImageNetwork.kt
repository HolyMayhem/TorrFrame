package tv.torrserve.silicon.core.network

import coil3.network.*
import coil3.network.okhttp.asNetworkClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded host health shared by thumbnails, posters and backdrops; never stores image URLs. */
class CatalogImageRoutes(
    initialPreferred: String = "",
    private val savePreferred: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var preferred = initialPreferred
    private val failedUntil = linkedMapOf<String, Long>()
    @Synchronized fun candidates(url: HttpUrl, config: CatalogNetworkSettings): List<HttpUrl> {
        val candidates = CatalogRouteInterceptor.destinations(url, config)
        failedUntil.entries.removeAll { it.value <= now() }
        val healthy = candidates.filter { it.host !in failedUntil }
        return (healthy.ifEmpty { candidates }).sortedBy { if (it.host == preferred) 0 else 1 }
    }
    @Synchronized fun failed(host: String) {
        failedUntil.remove(host); failedUntil[host] = now() + 60_000
        while (failedUntil.size > 16) failedUntil.remove(failedUntil.keys.first())
    }
    @Synchronized fun succeeded(host: String) {
        failedUntil.remove(host)
        if (preferred != host) { preferred = host; savePreferred(host) }
    }
}

/** Coil retains its normal disk/memory cache. Each attempt includes the complete response body. */
class CatalogImageNetwork(
    base: OkHttpClient,
    private val settings: () -> CatalogNetworkSettings,
    val routes: CatalogImageRoutes = CatalogImageRoutes(),
    private val diagnostics: NetworkDiagnostics? = null,
    attemptMillis: Long = 4_000,
    private val totalMillis: Long = 16_000,
) : NetworkClient {
    private val other = base.asNetworkClient()
    private val slots = Semaphore(3)
    private val transport = base.newBuilder().apply {
        interceptors().removeAll { it is CatalogRouteInterceptor }
    }.eventListener(EventListener.NONE).followRedirects(false).followSslRedirects(false)
        .connectTimeout(attemptMillis, TimeUnit.MILLISECONDS).readTimeout(attemptMillis, TimeUnit.MILLISECONDS)
        .callTimeout(attemptMillis, TimeUnit.MILLISECONDS).build()

    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        val original = request.url.toHttpUrlOrNull()
        if (original == null || !original.isHttps || original.host != "image.tmdb.org" || original.port != 443 || request.method != "GET")
            return other.executeRequest(request, block)
        return slots.withPermit {
            var failure: IOException = SocketTimeoutException("Image routes timed out")
            var lastHost = original.host
            var exhausted = false
            val response = withTimeoutOrNull(totalMillis) {
                for (url in routes.candidates(original, settings())) {
                    currentCoroutineContext().ensureActive()
                    lastHost = url.host
                    try {
                        val builder = Request.Builder().url(url)
                        request.headers.asMap().forEach { (name, values) ->
                            if (url.host == original.host || name.lowercase() !in PRIVATE_HEADERS)
                                values.forEach { builder.addHeader(name, it) }
                        }
                        val loaded = fetch(builder.build())
                        routes.succeeded(url.host)
                        diagnostics?.recovered(original.host); diagnostics?.recovered(url.host)
                        return@withTimeoutOrNull loaded
                    } catch (e: IOException) {
                        failure = e
                        // A missing file is not proof that its host is unhealthy.
                        if (e !is ServiceFailure || e.status != 404) routes.failed(url.host)
                    }
                }
                exhausted = true
                null
            } ?: run {
                if (!exhausted) failure = SocketTimeoutException("Image routing deadline exceeded")
                diagnostics?.failure(lastHost, failure)
                throw failure
            }
            // Do not retry a Coil cache/decode error as a network error or reuse its cache editor.
            try { block(response) } finally { response.body?.close() }
        }
    }

    private suspend fun fetch(request: Request): NetworkResponse = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val buffer = Buffer()
                try {
                    val loaded = response.use { r ->
                        if (!r.isSuccessful && r.code != 304) throw ServiceFailure("Images", r.code)
                        if (r.body.contentLength() > MAX_BYTES) throw ServiceFailure("Images", -1)
                        val mime = r.header("Content-Type").orEmpty().substringBefore(';')
                        if (r.code != 304 && mime.isNotBlank() && !mime.startsWith("image/") && mime != "application/octet-stream")
                            throw ServiceFailure("Images", -2)
                        val chunk = ByteArray(8192)
                        r.body.byteStream().use { input ->
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                if (buffer.size + count > MAX_BYTES) throw ServiceFailure("Images", -1)
                                buffer.write(chunk, 0, count)
                            }
                        }
                        if (r.code != 304 && buffer.size == 0L) throw ServiceFailure("Images", -2)
                        val headers = NetworkHeaders.Builder()
                        r.headers.forEach { (name, value) -> headers.add(name, value) }
                        NetworkResponse(code = r.code, requestMillis = r.sentRequestAtMillis,
                            responseMillis = r.receivedResponseAtMillis, headers = headers.build(),
                            body = NetworkResponseBody(buffer))
                    }
                    continuation.resume(loaded, onCancellation = { _, value, _ -> value.body?.close() })
                } catch (e: Exception) {
                    buffer.clear()
                    if (continuation.isActive) continuation.resumeWithException(if (e is IOException) e else IOException("Image download failed", e))
                }
            }
        })
    }
    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        private val PRIVATE_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "token")
    }
}
