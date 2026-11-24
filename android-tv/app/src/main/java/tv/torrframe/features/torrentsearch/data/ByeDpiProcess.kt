package tv.torrframe.features.torrentsearch.data

import android.content.Context
import android.os.Process.myPid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class DpiStrategy(val arguments: List<String>) {
    Split(listOf("--split", "1")),
    TlsRecord(listOf("--tlsrec", "1+s", "--disorder", "1"))
}

enum class DpiProcessState { Idle, Starting, Running, Failed }
internal class DpiProcessFailure : IOException()
internal class PrivateSearchAddress : IOException()

internal fun InetAddress.isPublicSearchAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    val first = address[0].toInt() and 0xff
    val second = address[1].toInt() and 0xff
    return if (address.size == 4) {
        first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
            !(first == 198 && second in 18..19)
    } else {
        (first and 0xfe) != 0xfc
    }
}

class ByeDpiProcess(context: Context, private val scope: CoroutineScope) {
    private data class Worker(val process: Process, val strategy: DpiStrategy, val client: OkHttpClient)
    private val executable = context.applicationInfo.nativeLibraryDir + "/libtorrframe_dpi.so"
    private val state = MutableStateFlow(DpiProcessState.Idle)
    val status = state.asStateFlow()
    private val lock = Mutex()
    private var worker: Worker? = null
    private var idle: Job? = null
    private val random by lazy { SecureRandom() }

    /** Keep at most one child and one SOCKS request in flight. */
    suspend fun <T> use(strategy: DpiStrategy, base: OkHttpClient, block: suspend (OkHttpClient) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                idle?.cancel()
                var current = worker
                if (current?.strategy != strategy || !current.process.isAlive) {
                    stop()
                    state.value = DpiProcessState.Starting
                    try {
                        current = start(strategy, base)
                        worker = current
                        state.value = DpiProcessState.Running
                    } catch (error: CancellationException) {
                        state.value = DpiProcessState.Idle
                        throw error
                    } catch (error: Exception) {
                        state.value = DpiProcessState.Failed
                        throw error
                    }
                }
                try {
                    block(current.client)
                } finally {
                    if (!current.process.isAlive) {
                        stop()
                        state.value = DpiProcessState.Failed
                    } else {
                        // Reuse the worker briefly for the next provider or title.
                        idle = scope.launch {
                            delay(900)
                            lock.withLock {
                                if (worker === current) {
                                    stop()
                                    state.value = DpiProcessState.Idle
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun stop() {
        worker?.let {
            it.client.connectionPool.evictAll()
            it.process.destroyForcibly()
        }
        worker = null
    }

    private suspend fun start(strategy: DpiStrategy, base: OkHttpClient): Worker {
        val token = ByteArray(24).also(random::nextBytes)
        val encoded = android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)
        val child = try {
            ProcessBuilder(listOf(executable, "-i", "127.0.0.1", "-p", "0", "-N", "-U", "-c", "16") + strategy.arguments)
                .redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                .apply {
                    environment()["TORRFRAME_PROXY_TOKEN"] = encoded
                    environment()["TORRFRAME_PROXY_PARENT"] = myPid().toString()
                }.start()
        } catch (_: IOException) {
            throw DpiProcessFailure()
        }
        var started = false
        try {
            val port = withTimeoutOrNull(1_200) {
                val line = StringBuilder()
                val input = child.inputStream
                while (child.isAlive || input.available() > 0) {
                    if (input.available() == 0) {
                        delay(15)
                        continue
                    }
                    val byte = input.read()
                    if (byte == 10 || byte == -1) break
                    if (byte !in '0'.code..'9'.code || line.length == 5) throw DpiProcessFailure()
                    line.append(byte.toChar())
                }
                line.toString().toIntOrNull()?.takeIf { it in 1..65535 }
            } ?: throw DpiProcessFailure()
            val factory = ProxySockets(port, encoded.toByteArray(StandardCharsets.US_ASCII))
            val client = base.newBuilder().proxy(Proxy.NO_PROXY).socketFactory(factory)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(2_800, TimeUnit.MILLISECONDS).readTimeout(3_800, TimeUnit.MILLISECONDS)
                .callTimeout(4_500, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
                .followSslRedirects(false).cache(null).build()
            return Worker(child, strategy, client).also { started = true }
        } finally {
            if (!started) child.destroyForcibly()
            child.inputStream.close()
            child.outputStream.close()
        }
    }
}

private class ProxySockets(private val proxyPort: Int, private val token: ByteArray) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException()
            val address = target.address ?: throw IOException()
            if (!address.isPublicSearchAddress()) throw PrivateSearchAddress()
            val previousTimeout = soTimeout
            val wait = timeout.takeIf { it > 0 } ?: 2_800
            try {
                super.connect(InetSocketAddress("127.0.0.1", proxyPort), wait)
                soTimeout = wait
                val input = DataInputStream(getInputStream())
                val output = getOutputStream()
                output.write(byteArrayOf(5, 33, 0x80.toByte()) + token)
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0x80) throw DpiProcessFailure()
                val ip = address.address
                output.write(byteArrayOf(5, 1, 0, if (ip.size == 4) 1 else 4) + ip +
                    byteArrayOf((target.port shr 8).toByte(), target.port.toByte()))
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException()
                input.readUnsignedByte()
                val addressBytes = when (input.readUnsignedByte()) {
                    1 -> 4
                    4 -> 16
                    else -> throw IOException()
                }
                input.readFully(ByteArray(addressBytes + 2))
                soTimeout = previousTimeout
            } catch (failure: IOException) {
                runCatching { close() }
                throw failure
            }
        }
    }

    // OkHttp creates an unconnected socket so its own TLS layer can use the original hostname.
    override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()
}
