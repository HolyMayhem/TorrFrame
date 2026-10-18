package tv.torrserve.silicon

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.server.ServerState
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.features.player.*

@RunWith(AndroidJUnit4::class)
class ExternalPlayerIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (compose.activity.application as TorrFrameApplication).graph
    private lateinit var original: Settings
    private val stream = "http://127.0.0.1:8090/stream?link=test&index=1"
    private val fake get() = ComponentName(instrumentation.context.packageName, FakeExternalPlayerActivity::class.java.name).flattenToString()
    @Before fun setup() = runBlocking { graph.ready.await(); original = graph.settings.state.value; graph.settings.update { it.copy(uiLanguage = "en") } }
    @After fun restore() = runBlocking { graph.settings.save(original) }

    private fun remote(key: Int) { compose.waitForIdle(); instrumentation.sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); remote(KeyEvent.KEYCODE_DPAD_CENTER) }
    private fun settings() {
        click("nav:Settings")
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:Playback"))
        click("settings:category:Playback")
    }
    @Test fun videoHandlersAreDiscoveredAndReceiveTheOriginalStreamAndTitle() = runBlocking {
        val found = graph.externalPlayers.available(stream)
        assertTrue("Expected $fake in $found", found.any { it.component == fake })
        val player = found.single { it.component == fake }
        assertEquals("Test video player", player.label)
        assertEquals(found.size, found.map { it.component.substringBefore('/') }.distinct().size)
        val intent = graph.externalPlayers.intent(player, stream, "Film title")
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals("video/*", intent.type)
        assertEquals(stream, intent.dataString); assertEquals(fake, intent.component?.flattenToString())
        assertEquals("Film title", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("Film title", intent.getStringExtra("title"))
    }
    @Test fun selectionPersistsAndSettingsAreNavigableWithTheRemote() {
        settings()
        click("playback:mode:Ask")
        compose.waitUntil(5000) { graph.settings.state.value.playerMode == PlayerMode.Ask }
        val list = compose.onNodeWithTag("settings:detail:Playback")
        list.performScrollToNode(hasTestTag("playback:select")); click("playback:select")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("player:external:$fake").fetchSemanticsNodes().isNotEmpty() }
        click("player:external:$fake")
        compose.waitUntil(5000) { graph.settings.state.value.externalPlayer == fake && graph.settings.state.value.playerMode == PlayerMode.External }
        compose.onNodeWithTag("playback:select").assertIsFocused()
        runBlocking {
            val restored = SettingsStore(graph.app); restored.load()
            assertEquals(fake, restored.state.value.externalPlayer); assertEquals(PlayerMode.External, restored.state.value.playerMode)
        }
        list.performScrollToNode(hasTestTag("playback:mode:Internal")); click("playback:mode:Internal")
        compose.waitUntil(5000) { graph.settings.state.value.playerMode == PlayerMode.Internal }
    }
    @Test fun missingPreferredPlayerOffersFallbackAndBackCancelsWithoutStartingPlayback() = runBlocking {
        graph.settings.update { it.copy(playerMode = PlayerMode.External, externalPlayer = "missing.player/.Activity") }
        withContext(Dispatchers.Main) { graph.playback.open(stream, "test:1", "Test") }
        compose.onNodeWithTag("player:internal").assertIsFocused()
        compose.onNodeWithText("The selected player is no longer available. Choose another player or use the built-in player.").assertExists()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("player:choices").assertDoesNotExist()
        assertFalse(graph.server.externalPlayback.value)
        assertFalse(graph.playback.state.value.active)
    }
    @Test fun askModeAlwaysShowsThePickerEvenWithASavedPlayer() = runBlocking {
        graph.settings.update { it.copy(playerMode = PlayerMode.Ask, externalPlayer = fake) }
        withContext(Dispatchers.Main) { graph.playback.open(stream, "test:1", "Test") }
        compose.onNodeWithTag("player:internal").assertIsFocused()
        compose.onNodeWithTag("player:external:$fake").assertExists()
        assertFalse(graph.server.externalPlayback.value)
        remote(KeyEvent.KEYCODE_BACK)
    }
    @Test fun playerDisappearingAfterDiscoveryShowsAnErrorAndReleasesTheServer() = runBlocking {
        graph.settings.update { it.copy(playerMode = PlayerMode.Ask, externalPlayer = fake) }
        withContext(Dispatchers.Main) { graph.playback.open(stream, "test:1", "Test") }
        compose.onNodeWithTag("player:external:$fake").assertExists()
        // Reproduce removal between discovery and launch without uninstalling the
        // APK that also owns the running instrumentation process.
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.component?.flattenToString() == fake) throw android.content.ActivityNotFoundException()
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            click("player:external:$fake")
            compose.waitUntil(5000) {
                runCatching { compose.onAllNodesWithText("Could not open the external player. Choose another player or use the built-in player.").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            compose.onNodeWithText("Could not open the external player. Choose another player or use the built-in player.").assertExists()
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("player:internal") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player:internal").assertIsFocused()
            assertFalse(graph.server.externalPlayback.value)
            remote(KeyEvent.KEYCODE_BACK)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun internalModeStillOpensTheBuiltInPlayer() = runBlocking {
        graph.settings.update { it.copy(playerMode = PlayerMode.Internal, externalPlayer = fake) }
        withContext(Dispatchers.Main) { graph.playback.open(stream, "test:1", "Test") }
        compose.waitUntil(5000) { graph.server.playbackOwned }
        compose.onNodeWithTag("player:choices").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { !graph.server.playbackOwned }
        assertFalse(graph.server.externalPlayback.value)
    }
    @Test fun emptyPlayerListKeepsAClearFallbackAndInitialFocus() {
        compose.activity.setContentForEmptyPlayers()
        compose.onNodeWithTag("player:internal").assertIsFocused()
        compose.onNodeWithText("No compatible external players found. Install a player that supports network video.").assertExists()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("player:cancel").assertIsFocused()
    }
    @Test fun externalPlayerKeepsLoopbackServerAliveAndReleasesOwnershipOnReturn() = runBlocking {
        graph.settings.update { it.copy(playerMode = PlayerMode.External, externalPlayer = fake, useLocalServer = true,
            torrServerUrl = tv.torrserve.silicon.core.server.ServerAddress.local(it.port), lanAccess = false, startOnBoot = false) }
        graph.server.requireRunning()
        val pid = (graph.server.state.value as ServerState.Running).pid
        withContext(Dispatchers.Main) { graph.playback.open(stream, "test:1", "Test") }
        withTimeout(10_000) { while (!graph.server.externalPlayback.value || !graph.server.backgroundOwned) delay(50) }
        delay(17_000)
        assertTrue(graph.server.externalPlayback.value)
        assertTrue(graph.server.hasPlayback)
        assertEquals(pid, (graph.server.state.value as ServerState.Running).pid)
        withTimeout(15_000) { while (graph.server.externalPlayback.value) delay(50) }
        assertFalse(graph.server.hasPlayback)
        assertEquals(PlayerMode.External, graph.settings.state.value.playerMode)
    }
}

private fun MainActivity.setContentForEmptyPlayers() {
    setContent {
        tv.torrserve.silicon.ui.theme.TorrFrameTheme {
            tv.torrserve.silicon.ui.components.AppLanguage("en") {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PlayerChoices(emptyList(), includeInternal = true, choose = {}, dismiss = {})
                }
            }
        }
    }
}
