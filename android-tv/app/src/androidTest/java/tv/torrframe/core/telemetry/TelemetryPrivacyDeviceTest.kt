package tv.torrframe.core.telemetry

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import tv.torrframe.MainActivity
import tv.torrframe.TorrFrameApplication
import java.io.File
import java.util.UUID

class TelemetryStorageDeviceTest {
    @Test fun debugAndProductionAreStoredSeparatelyInNoBackupDirectory() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val sandbox = File(target.noBackupFilesDir, "telemetry-test-${System.nanoTime()}").apply { mkdir() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = sandbox }
        try {
            val debug = TelemetryPreferences(context,true)
            val release = TelemetryPreferences(context,false)
            val debugId = UUID.randomUUID().toString()
            val releaseId = UUID.randomUUID().toString()
            debug.write(TelemetryState(TelemetryConsent.Enabled,true,debugId))
            assertNull(release.read().id)
            release.write(TelemetryState(TelemetryConsent.Enabled,true,releaseId))
            assertEquals(debugId,TelemetryPreferences(context,true).read().id)
            assertEquals(releaseId,TelemetryPreferences(context,false).read().id)
            assertEquals(setOf("telemetry-debug.json","telemetry-production.json"), sandbox.list()!!.toSet())
            debug.write(TelemetryState(TelemetryConsent.Disabled,true))
            assertNull(debug.read().id)
            assertFalse(File(sandbox,"telemetry-debug.json").readText().contains(debugId))
            assertEquals(releaseId,release.read().id)
        } finally { sandbox.deleteRecursively() }
    }
}

class TelemetryPrivacyDeviceTest {
    @get:Rule val tv = createAndroidComposeRule<MainActivity>()

    private fun node(tag: String) = tv.onNodeWithTag(tag)
    private fun exists(tag: String) = tv.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun focusAndClick(tag: String) {
        node(tag).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        tv.waitForIdle()
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(screenshot)
        File(context.cacheDir,name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle()
    }

    @Test fun aboutShowsPrivacyAndPreviewWithoutChangingConsent() {
        tv.waitUntil(20_000) { listOf("setup:skip","telemetry:offer:decline","nav:Settings").any(::exists) }
        if (exists("setup:skip")) focusAndClick("setup:skip")
        tv.waitUntil(16_000) { exists("telemetry:offer:decline") || exists("nav:Settings") }
        if (exists("telemetry:offer:decline")) {
            node("telemetry:offer:decline").performClick()
            tv.waitUntil(5000) { !exists("telemetry:offer:decline") }
        }
        tv.waitUntil(16_000) { exists("nav:Settings") }
        focusAndClick("nav:Settings")
        val category = "settings:category:About"
        node("settings:categories").performScrollToNode(hasTestTag(category))
        focusAndClick(category)
        val graph = (tv.activity.application as TorrFrameApplication).graph
        node("settings:setup").performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        repeat(10) {
            if (tv.onAllNodes(hasTestTag("telemetry:toggle") and isFocused()).fetchSemanticsNodes().isEmpty()) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        node("telemetry:toggle").assertIsDisplayed().assertIsFocused()
        val consent = graph.telemetry.consent.value
        if (consent == TelemetryConsent.Enabled) node("telemetry:reset").assertIsEnabled()
        else node("telemetry:reset").assertIsNotEnabled()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        node("telemetry:preview").assertIsFocused()
        capture("telemetry-about.png")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        tv.waitUntil(5000) { tv.onAllNodes(hasText("installationID", substring=true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(consent, graph.telemetry.consent.value)
        if (consent != TelemetryConsent.Enabled) {
            tv.onNode(hasText("\"installationID\": null",substring=true)).assertExists()
        }
    }
}

/** Explicit opt-in to a localhost fixture. The test never uses the app's real ID or preferences. */
class TelemetryLocalWorkerDeviceTest {
    @Test fun sendsTypedPayloadOnlyAfterConsentAndReceivesD1Receipt() = kotlinx.coroutines.runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val configured = args.getString("telemetryLocalUrl").orEmpty()
        org.junit.Assume.assumeTrue(configured.isNotBlank())
        val endpoint = requireNotNull(telemetryEndpoint(configured, true))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureState = java.util.concurrent.atomic.AtomicReference(TelemetryState())
        val fixture = object : TelemetryPersistence {
            override fun read() = fixtureState.get()
            override fun write(state: TelemetryState) { fixtureState.set(state) }
        }
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val transport = HttpTelemetryTransport(endpoint)
        val now = System.currentTimeMillis()
        var clock = TelemetryTime(now - 86_400_000, 1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val telemetry = TelemetryController(owner, fixture, TelemetryTransport { payload ->
            calls.incrementAndGet()
            File(target.cacheDir, "telemetry-heartbeat.json").writeText(payload.json().toString(2))
            transport.send(payload)
        }, { DeviceSnapshot(36,"Google","Android TV Emulator","arm64-v8a","2to3","Android TV") },
            { "ru" to "dark" }, { clock }, 50)
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                telemetry.initialize()
                telemetry.foreground(true)
                kotlinx.coroutines.delay(120)
                assertEquals(0,calls.get())
                telemetry.foreground(false)
                assertTrue(telemetry.choose(true))
                telemetry.record(TelemetryFeature.BuiltinPlayer)
                kotlinx.coroutines.withTimeout(3_000) {
                    while (fixtureState.get().days[clock.day] != TelemetryFeature.BuiltinPlayer.bit) kotlinx.coroutines.delay(10)
                }
                clock = TelemetryTime(now, 86_400_001)
                telemetry.foreground(true)
                kotlinx.coroutines.withTimeout(8_000) {
                    while (fixtureState.get().successDay != clock.day) kotlinx.coroutines.delay(20)
                }
                assertEquals(1,calls.get())
                assertFalse(clock.day.minusDays(1) in fixtureState.get().days)
            }
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
