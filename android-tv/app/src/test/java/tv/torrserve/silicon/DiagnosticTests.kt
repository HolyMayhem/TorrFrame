package tv.torrserve.silicon

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.performance.cpuPercent
import tv.torrserve.silicon.features.developer.DeveloperControls
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class DiagnosticTests {
    @Test fun errorsKeepTheirCategoryWithoutKeepingSecretsAndStorageIsBounded() {
        val diagnostics = NetworkDiagnostics(now = { 123 })
        for ((error, kind) in listOf(
            UnknownHostException("api_key=secret") to NetworkFailureKind.Dns,
            SSLHandshakeException("secret URL") to NetworkFailureKind.Tls,
            SocketTimeoutException() to NetworkFailureKind.Timeout,
            ConnectException() to NetworkFailureKind.Connection,
            ServiceFailure("TMDB", 403) to NetworkFailureKind.Http,
            ServiceFailure("TMDB", -1) to NetworkFailureKind.Response)) {
            diagnostics.failure("api.themoviedb.org", ServiceFailure("TMDB", cause = error))
            val issue = diagnostics.issues.value.single()
            assertEquals(kind, issue.kind); assertEquals(123L, issue.time)
            assertFalse(issue.toString().contains("secret"))
        }
        for (i in 0..100) diagnostics.failure("host$i.example", UnknownHostException())
        assertEquals(8, diagnostics.issues.value.size)
        assertEquals("host100.example", diagnostics.issues.value.first().host)
        diagnostics.recovered("host0.example"); assertEquals(8, diagnostics.issues.value.size)
        diagnostics.recovered("host100.example"); assertEquals(7, diagnostics.issues.value.size)
        val disabled = NetworkDiagnostics(false)
        disabled.failure("example.com", UnknownHostException()); assertTrue(disabled.issues.value.isEmpty())
    }

    @Test fun dnsFailureRetainsCauseAndUnrelatedServerSuccessCannotHideIt(): Unit = runBlocking {
        val diagnostics = NetworkDiagnostics()
        val http = Http(OkHttpClient.Builder().dns(Dns { throw UnknownHostException("private query") }).build(), diagnostics = diagnostics)
        val error = try { http.bytes(Request.Builder().url("https://unreachable.example/catalog?api_key=secret").build(), "TMDB"); null }
            catch (e: ServiceFailure) { e }
        assertTrue(error?.cause is UnknownHostException)
        assertEquals(NetworkFailureKind.Dns, diagnostics.issues.value.single().kind)
        assertFalse(diagnostics.issues.value.toString().contains("secret"))
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("healthy"))
            Http(diagnostics = diagnostics).get(server.url("/echo"), "TorrServer")
            assertEquals("unreachable.example", diagnostics.issues.value.single().host)
        }
    }

    @Test fun imageClientHttpFailuresAreVisibleAndSuccessClearsOnlyThatHost() {
        val diagnostics = NetworkDiagnostics()
        val client = Http(diagnostics = diagnostics).client
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(403))
            client.newCall(Request.Builder().url(server.url("/poster?api_key=secret")).build()).execute().use { it.body.bytes() }
            assertEquals(403, diagnostics.issues.value.single().status)
            server.enqueue(MockResponse().setBody("image"))
            client.newCall(Request.Builder().url(server.url("/poster")).build()).execute().use { it.body.bytes() }
            assertTrue(diagnostics.issues.value.isEmpty())
        }
    }

    @Test fun simulatedFailureIsMarkedWithoutSendingARequest(): Unit = runBlocking {
        val diagnostics = NetworkDiagnostics()
        val controls = DeveloperControls(true).apply { update { it.copy(missingKeys = setOf("TMDB")) } }
        val http = Http(beforeRequest = controls::beforeRequest, diagnostics = diagnostics)
        try { http.bytes(Request.Builder().url("https://api.themoviedb.org/3/movie/1?api_key=secret").build(), "TMDB"); fail() }
        catch (e: ServiceFailure) { assertEquals(401, e.status) }
        assertTrue(diagnostics.issues.value.single().simulated)
        assertEquals(401, diagnostics.issues.value.single().status)
    }

    @Test fun overallCallTimeoutIsShownEvenWhenOkHttpCancelsItsSocket(): Unit = runBlocking {
        val diagnostics = NetworkDiagnostics()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = Http(OkHttpClient.Builder().callTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build(), diagnostics = diagnostics)
            try { http.get(server.url("/slow"), "TMDB"); fail() }
            catch (e: ServiceFailure) { assertEquals(408, e.status) }
            assertEquals(NetworkFailureKind.Timeout, diagnostics.issues.value.single().kind)
        }
    }

    @Test fun userCancellationIsNotDisplayedAsANetworkFailure(): Unit = runBlocking {
        val diagnostics = NetworkDiagnostics()
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http = Http(diagnostics = diagnostics)
            val request = launch(Dispatchers.Default) { http.get(server.url("/cancel"), "TMDB") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) })
            request.cancelAndJoin()
            delay(100)
            assertTrue(diagnostics.issues.value.isEmpty())
        }
    }

    @Test fun cpuSamplesNormalizeAcrossCoresAndRejectInvalidBaselines() {
        assertNull(cpuPercent(null, null, 100, 1000, 4))
        assertNull(cpuPercent(100, 1000, 200, 1000, 4))
        assertNull(cpuPercent(100, 1000, 90, 2000, 4))
        assertEquals(25.0, cpuPercent(100, 1000, 1100, 2000, 4)!!, .001)
        assertEquals(100.0, cpuPercent(0, 0, 9000, 1000, 4)!!, .001)
        assertEquals(0.0, cpuPercent(100, 1000, 100, 2000, 4)!!, .001)
    }

    @Test fun overlayIsIndependentOfFaultInjectionAndDisabledInRelease() {
        val controls = DeveloperControls(true)
        assertFalse(controls.diagnosticsVisible.value)
        controls.showDiagnostics(true); assertTrue(controls.diagnosticsVisible.value)
        assertFalse(controls.state.value.active)
        controls.update { it.copy(metadataOffline = true) }; controls.reset()
        assertTrue(controls.diagnosticsVisible.value); assertFalse(controls.state.value.active)
        controls.showDiagnostics(false); assertFalse(controls.diagnosticsVisible.value)
        assertFalse(DeveloperControls(true).diagnosticsVisible.value)
        val release = DeveloperControls(false); release.showDiagnostics(true)
        assertFalse(release.diagnosticsVisible.value)
    }
}
