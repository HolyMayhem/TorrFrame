package tv.torrserve.silicon.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ServiceFailure(val service: String, val status: Int = 0) : IOException(
    when (status) {
        401, 403 -> "$service: invalid API key or access denied."
        429 -> "$service: too many requests. Try again later."
        408 -> "$service: request timed out."
        -1 -> "$service: response is too large."
        -2 -> "$service: invalid response."
        0 -> "$service: cannot connect or request timed out. Check address and connection."
        else -> "$service returned HTTP $status."
    }
)
data class HttpReply(val status: Int, val location: String?, val data: ByteArray)
class Http(val client: OkHttpClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS).build()) {
    suspend fun bytes(request: Request, service: String, limit: Int = 4 * 1024 * 1024): ByteArray = exchange(request, service, limit).data
    suspend fun exchange(request: Request, service: String, limit: Int = 4 * 1024 * 1024, manualRedirects: Boolean = false): HttpReply =
        suspendCancellableCoroutine { continuation ->
            val call = (if(manualRedirects) client.newBuilder().followRedirects(false).build() else client).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(ServiceFailure(service,
                        if(e is java.io.InterruptedIOException) 408 else 0)))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { r ->
                            if(manualRedirects && r.code in setOf(301,302,303,307,308)) return@use HttpReply(r.code,r.header("Location"),ByteArray(0))
                            if (!r.isSuccessful) throw ServiceFailure(service, r.code)
                            val body = r.body
                            if (body.contentLength() > limit) throw ServiceFailure(service, -1)
                            body.byteStream().use { stream ->
                                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                while (true) {
                                    val n = stream.read(buffer); if (n == -1) break
                                    if (out.size() + n > limit) throw ServiceFailure(service, -1)
                                    out.write(buffer, 0, n)
                                }; HttpReply(r.code,null,out.toByteArray())
                            }
                        }
                    }
                    val mapped = result.fold({ Result.success(it) }, { error ->
                        Result.failure<HttpReply>(if (error is ServiceFailure) error else if (error is IOException)
                            ServiceFailure(service, if (error is java.io.InterruptedIOException) 408 else 0) else error)
                    })
                    if (continuation.isActive) continuation.resumeWith(mapped)
                }
            })
        }
    suspend fun get(url: HttpUrl, service: String) = bytes(Request.Builder().url(url).build(), service)
    suspend fun post(url: HttpUrl, json: JSONObject, service: String) = bytes(Request.Builder()
        .url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build(), service)
}
