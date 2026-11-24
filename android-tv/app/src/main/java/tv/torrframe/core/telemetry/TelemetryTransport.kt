package tv.torrframe.core.telemetry

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal fun telemetryEndpoint(value: String, debug: Boolean): HttpUrl? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        url.encodedPath != "/v1/heartbeat") return null
    if (debug) return url.takeIf { it.host == "127.0.0.1" || it.host == "::1" }
    return url.takeIf { it.isHttps && it.port == 443 && '.' in it.host &&
        it.host.substringAfterLast('.').matches(Regex("[a-z]{2,63}")) &&
        !it.host.endsWith(".local") && !it.host.endsWith(".localhost") }
}

internal data class TelemetryReceipt(val day: LocalDate?, val usage: LocalDate? = null, val retryMillis: Long = 0)

internal fun interface TelemetryTransport {
    suspend fun send(payload: TelemetryPayload): TelemetryReceipt
}

internal class HttpTelemetryTransport(private val endpoint: HttpUrl) : TelemetryTransport {
    private val client by lazy { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build() }

    override suspend fun send(payload: TelemetryPayload): TelemetryReceipt {
        val bytes = payload.json().toString().toByteArray(Charsets.UTF_8)
        if (payload.installationID == null || bytes.size > 2048) return TelemetryReceipt(null)
        val request = Request.Builder().url(endpoint).post(bytes.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resume(TelemetryReceipt(null)) }
                override fun onResponse(call: Call, response: Response) {
                    val receipt = response.use {
                        fun date(header: String) = runCatching { LocalDate.parse(it.header(header)) }.getOrNull()
                        if (it.code == 204) TelemetryReceipt(date("X-Telemetry-Day"), date("X-Telemetry-Usage"))
                        else TelemetryReceipt(null, retryMillis = if (it.code in listOf(429,503))
                            (it.header("Retry-After")?.toLongOrNull() ?: 0).coerceIn(0,86_400) * 1000 else 0)
                    }
                    continuation.resume(receipt)
                }
            })
        }
    }
}
