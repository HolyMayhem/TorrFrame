package tv.torrframe.core.telemetry

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TelemetryTests {
    private class Storage(var current: TelemetryState = TelemetryState()) : TelemetryPersistence {
        override fun read() = current
        override fun write(state: TelemetryState) { current = state }
    }
    private class Time {
        var now = TelemetryTime(Instant.parse("2026-11-24T23:50:00Z").toEpochMilli(), 1)
        fun jump(millis: Long) { now = TelemetryTime(now.wall + millis, now.elapsed + millis) }
    }
    private val device = DeviceSnapshot(36, "Google", "Android TV Emulator", "arm64-v8a", "2to3", "Android TV")
    private fun TestScope.controller(storage: Storage, time: Time, send: TelemetryTransport? = null) =
        TelemetryController(backgroundScope, storage, send, { device }, { "ru" to "dark" }, { time.now }, 10,
            StandardTestDispatcher(testScheduler))
    private fun TestScope.tick() { advanceTimeBy(11); runCurrent() }

    @Test fun optInIssuesIdentityAndBackgroundUseOrPreviewCannotSend() = runTest {
        val store = Storage()
        val clock = Time()
        var calls = 0
        val client = controller(store, clock) { calls++; TelemetryReceipt(clock.now.day) }
        client.initialize()
        client.foreground(true)
        tick()
        client.record(TelemetryFeature.BuiltinPlayer)
        assertTrue(JSONObject(client.preview()).isNull("installationID"))
        assertNull(store.current.id)
        assertEquals(0, calls)
        client.foreground(false)
        assertTrue(client.choose(true))
        assertEquals(4, UUID.fromString(store.current.id).version())
        val first = store.current.id
        tick()
        assertEquals(0, calls)
        val restarted = controller(store, clock) { calls++; TelemetryReceipt(clock.now.day) }
        restarted.initialize()
        assertEquals(first, JSONObject(restarted.preview()).getString("installationID"))
        tick()
        assertEquals(0, calls)
        repeat(12) { restarted.foreground(true) }
        tick()
        assertEquals(1, calls)
        restarted.foreground(false)
        restarted.foreground(true)
        tick()
        assertEquals(1, calls)
    }

    @Test fun revokeCancelsInFlightImmediatelyAndReenableAndResetClearIdentityAndUsage() = runTest {
        val store = Storage()
        val clock = Time()
        var started = 0
        var cancelled = false
        val client = controller(store, clock) {
            started++
            try { awaitCancellation() } finally { cancelled = true }
        }
        client.initialize()
        client.foreground(true)
        client.choose(true)
        client.record(TelemetryFeature.Cub)
        tick()
        assertEquals(1, started)
        val old = store.current.id
        assertTrue(client.choose(false))
        runCurrent()
        assertTrue(cancelled)
        assertNull(store.current.id)
        assertTrue(store.current.days.isEmpty())
        client.record(TelemetryFeature.Cub)
        tick()
        assertEquals(1, started)
        assertTrue(client.choose(true))
        val second = store.current.id
        assertNotEquals(old, second)
        client.record(TelemetryFeature.PhoneRemote)
        runCurrent()
        assertTrue(client.reset())
        assertNotEquals(second, store.current.id)
        assertTrue(store.current.days.values.all { it == 0 })
    }

    @Test fun sendsCompletedUtcDaysAndDeduplicatesFeatureBitsUntilExplicitAcknowledgment() = runTest {
        val store = Storage()
        val clock = Time()
        val reports = mutableListOf<UsageReport?>()
        val client = controller(store, clock) {
            reports += it.usage
            TelemetryReceipt(clock.now.day, it.usage?.date)
        }
        client.initialize()
        client.foreground(true)
        client.choose(true)
        tick()
        repeat(3) { client.record(TelemetryFeature.Tmdb) }
        client.record(TelemetryFeature.Favorites)
        runCurrent()
        assertEquals(listOf<UsageReport?>(null), reports)
        clock.jump(3_600_000)
        client.foreground(false)
        client.foreground(true)
        tick()
        val completed = LocalDate.parse("2026-11-24")
        assertEquals(UsageReport(completed, TelemetryFeature.Tmdb.bit or TelemetryFeature.Favorites.bit), reports.last())
        assertFalse(completed in store.current.days)
        assertEquals(clock.now.day, store.current.successDay)
    }

    @Test fun retryIsPersistedAndRateLimitUsesElapsedTimeAcrossClockAdjustments() = runTest {
        val store = Storage()
        val clock = Time()
        var attempts = 0
        val client = controller(store, clock) { attempts++; TelemetryReceipt(null, retryMillis = 1_800_000) }
        client.initialize()
        client.choose(true)
        fun TestScope.start() { client.foreground(false); client.foreground(true); tick() }
        start()
        clock.now = clock.now.copy(wall = clock.now.wall + 4 * 86_400_000L)
        start()
        assertEquals(1, attempts)
        clock.now = clock.now.copy(wall = clock.now.wall - 4 * 86_400_000L)
        clock.jump(1_800_000)
        start()
        assertEquals(2, attempts)
        clock.jump(3_600_000)
        start()
        assertEquals(3, attempts)
        clock.jump(15_000)
        start()
        assertEquals(3, attempts)
    }

    @Test fun offerOnceAndNoIdentifierForDecline() = runTest {
        val store = Storage()
        val client = controller(store, Time())
        client.initialize()
        assertTrue(client.claimOffer())
        assertFalse(controller(store, Time()).also { it.initialize() }.claimOffer())
        assertTrue(client.choose(false))
        assertNull(store.current.id)
    }

    @Test fun endpointIsExplicitAndDebugCanOnlyUseLiteralLoopback() {
        assertNull(telemetryEndpoint("", true))
        assertNull(telemetryEndpoint("https://analytics.example.com/v1/heartbeat", true))
        assertNull(telemetryEndpoint("http://10.0.2.2:8787/v1/heartbeat", true))
        assertNull(telemetryEndpoint("http://localhost:8787/v1/heartbeat", true))
        assertNotNull(telemetryEndpoint("http://127.0.0.1:8787/v1/heartbeat", true))
        assertNotNull(telemetryEndpoint("http://[::1]:8787/v1/heartbeat", true))
        assertNull(telemetryEndpoint("http://analytics.example.com/v1/heartbeat", false))
        assertNull(telemetryEndpoint("https://127.0.0.1/v1/heartbeat", false))
        assertNull(telemetryEndpoint("https://user:password@analytics.example.com/v1/heartbeat", false))
        assertNull(telemetryEndpoint("https://analytics.example.com/v1/heartbeat?value=1", false))
        assertNotNull(telemetryEndpoint("https://analytics.example.com/v1/heartbeat", false))
    }

    @Test fun normalizationKeepsOnlyAllowedValuesAndRamUsesHalfOpenIntervals() {
        assertEquals("Other", DeviceSnapshot.model("ABC-serial-61412"))
        assertEquals("Unknown", DeviceSnapshot.manufacturer(null))
        assertEquals("NVIDIA", DeviceSnapshot.manufacturer("NvIdIa"))
        val gib = 1L shl 30
        assertEquals(listOf("Unknown","lt1","1to2","2to3","3to4","4to6","6to8","gte8"),
            listOf(0, gib-1, gib, 2*gib, 3*gib, 4*gib, 6*gib, 8*gib).map(DeviceSnapshot::memory))
    }

    @Test fun reportUsesOnlyWhitelistedKeysAndHttpDoesNotFollowRedirects() = runTest {
        MockWebServer().use { source ->
            MockWebServer().use { destination ->
                source.start(); destination.start()
                source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/redirect")))
                val url = source.url("/v1/heartbeat")
                val report = TelemetryPayload(UUID.randomUUID().toString(), device, "ru", "dark",
                    UsageReport(LocalDate.parse("2026-11-24"), TelemetryFeature.Afr.bit))
                assertNull(HttpTelemetryTransport(url).send(report).day)
                assertEquals(0, destination.requestCount)
                val sent = source.takeRequest()
                assertNull(sent.getHeader("Cookie"))
                assertNull(sent.getHeader("Authorization"))
                val json = JSONObject(sent.body.readUtf8())
                assertEquals(setOf("schemaVersion","installationID","appVersion","appVersionCode","androidApiLevel",
                    "manufacturer","deviceModel","cpuArchitecture","ramBucket","deviceType","interfaceLanguage","theme","usage"),
                    json.keys().asSequence().toSet())
                assertEquals(TelemetryFeature.entries.map { it.field }.toSet(),
                    json.getJSONObject("usage").getJSONObject("features").keys().asSequence().toSet())
            }
        }
    }
}
