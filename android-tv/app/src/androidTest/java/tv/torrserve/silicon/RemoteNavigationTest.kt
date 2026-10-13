package tv.torrserve.silicon

import android.view.KeyEvent
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.LocalContentColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.ui.theme.TextPrimary
import tv.torrserve.silicon.ui.theme.TorrServeTheme
import tv.torrserve.silicon.features.home.HomeScreen
import tv.torrserve.silicon.features.settings.MetadataPriorityControls
import tv.torrserve.silicon.core.storage.MetadataPriority
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun remote(key: Int) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
    private fun focused(tag: String) {
        try {
            compose.waitUntil(3000) {
                runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true }.getOrDefault(false)
            }
        } catch(e: Throwable) {
            val active = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().map { it.config.toString() }
            throw AssertionError("Expected focus on $tag; actual $active; activity window focus ${compose.activity.hasWindowFocus()}", e)
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    @Test fun dpadNavigatesTabsAndBackReturnsHome() {
        compose.onNodeWithTag("nav:Home").assertIsFocused().assertIsSelected()
        for(tab in listOf("Search", "Library", "Settings")) {
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("nav:$tab").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("nav:$tab").assertIsSelected().assertIsFocused()
        }
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("nav:Home").assertIsFocused().assertIsSelected()
    }
    @Test fun scaleReflowsContentPersistsAndKeepsRemoteFocusAtBothLimits() {
        val graph = (compose.activity.application as TorrServeApplication).graph
        val original = graph.settings.state.value.uiScalePercent
        fun waitScale(value: Int) {
            compose.waitUntil(5000) { graph.settings.state.value.uiScalePercent == value }
            compose.onNodeWithTag("scale:value").assertTextEquals("$value%")
        }
        try {
            for (i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("scale:reset").performClick()
            waitScale(85)
            val originalWidth = compose.onNodeWithTag("nav:Home").fetchSemanticsNode().boundsInRoot.width
            val originalContent = compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height
            val footer = compose.onNodeWithTag("app:footer").fetchSemanticsNode().boundsInRoot.height
            val header = compose.onNodeWithTag("app:header").fetchSemanticsNode().boundsInRoot.height
            assertTrue("Footer stays compact", footer < header / 2)
            compose.onNodeWithTag("scale:minus").performSemanticsAction(SemanticsActions.RequestFocus)
            for (value in listOf(80, 75, 70)) {
                remote(KeyEvent.KEYCODE_DPAD_CENTER); waitScale(value)
            }
            compose.onNodeWithTag("scale:minus").assertIsNotEnabled()
            focused("scale:plus")
            val smallWidth = compose.onNodeWithTag("nav:Home").fetchSemanticsNode().boundsInRoot.width
            assertEquals(70f / 85f, smallWidth / originalWidth, .04f)
            assertTrue("Smaller chrome frees actual content space",
                compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height > originalContent)
            for (value in 75..110 step 5) {
                remote(KeyEvent.KEYCODE_ENTER); waitScale(value)
            }
            compose.onNodeWithTag("scale:plus").assertIsNotEnabled()
            focused("scale:minus")
            val reloaded = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reloaded.load() }
            assertEquals(110, reloaded.state.value.uiScalePercent)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("scale:value").assertTextEquals("110%")
            compose.onNodeWithTag("scale:reset").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER); waitScale(85)
            focused("scale:reset")
        } finally {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiScalePercent = original) } }
        }
    }
    @Test fun homeActionTransfersFocusToSearchAndSettings() {
        compose.onNodeWithTag("nav:Home").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("home:search")
        remote(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        compose.onNodeWithText("Open Settings").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Settings").assertIsSelected()
        compose.onNodeWithText("Сохранить").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("nav:Home").assertIsFocused()
    }
    @Test fun settingsDialogCanBeCancelledWithRemoteAndRestoresFocus() {
        compose.onNodeWithTag("nav:Home").assertIsFocused()
        for(i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Settings").assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("field:Порт встроенного сервера"))
        compose.onNodeWithTag("field:Порт встроенного сервера").performSemanticsAction(SemanticsActions.RequestFocus)
        focused("field:Порт встроенного сервера")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("dialog:editor")
        compose.waitUntil(3000) { !compose.activity.hasWindowFocus() }
        // With the AVD hardware keyboard enabled, DOWN leaves the editor for its actions.
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("dialog:done")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("dialog:cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("dialog:cancel").assertDoesNotExist()
        focused("field:Порт встроенного сервера")
    }
    @Test fun engineScreenBackRestoresSettingsFocusWithRemote() {
        for (i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("server:engine"))
        compose.onNodeWithTag("server:engine").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("engine:back")
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("engine:back").assertDoesNotExist()
        compose.onNodeWithTag("nav:Settings").assertIsSelected()
        focused("server:engine")
    }
    @Test fun settingsShowsActualWebAddressAndLanSwitchAcceptsRemote() {
        val graph = (compose.activity.application as TorrServeApplication).graph
        for (i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("server:web-url"))
        compose.waitUntil(10_000) { graph.serverWebAddress.url.value.isNotEmpty() }
        compose.onNodeWithTag("server:web-url").assertTextEquals(graph.serverWebAddress.url.value)
        compose.onNodeWithTag("server:lan").performSemanticsAction(SemanticsActions.RequestFocus)
        focused("server:lan")
        try {
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { !graph.settings.state.value.lanAccess }
            compose.onNodeWithTag("server:web-url").assertDoesNotExist()
            // Saving the flag precedes the server restart and connection check.
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(compose.activity.getString(R.string.server_busy), substring = true)
                    .fetchSemanticsNodes().isEmpty()
            }
            focused("server:lan")
            remote(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(10_000) { graph.settings.state.value.lanAccess && graph.serverWebAddress.url.value.isNotEmpty() }
            compose.onNodeWithTag("server:web-url").assertTextEquals(graph.serverWebAddress.url.value)
            focused("server:lan")
        } finally {
            kotlinx.coroutines.runBlocking { graph.serverActions.lan(true) }
        }
    }
    @Test fun torrServerAddressDialogRestoresFocusAndRejectsInvalidAddress() {
        for (i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        val tag = "field:Адрес TorrServer"
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("dialog:editor").performTextReplacement("ftp://invalid")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused(tag)
        compose.onNodeWithText(compose.activity.getString(R.string.server_invalid_url)).assertExists()
    }
    @Test fun manualMagnetActionsAreAbsentFromHomeAndLibrary() {
        compose.onNodeWithTag("nav:Home").assertIsFocused()
        compose.onNodeWithText("Add Magnet Link").assertDoesNotExist()
        compose.onNodeWithTag("home:settings").assertExists()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Add Magnet").assertDoesNotExist()
        compose.onNodeWithText("Add Magnet Link").assertDoesNotExist()
        compose.onNodeWithText("Find Torrents").assertExists()
    }
    @Test fun compactHomeFitsShortTvWindow() {
        val fallback = FocusRequester()
        val config = Configuration(compose.activity.resources.configuration).apply {
            screenWidthDp = 720; screenHeightDp = 400
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalConfiguration provides config) {
                    TorrServeTheme {
                        Box(Modifier.width(720.dp).height(400.dp)) {
                            HomeScreen(emptyList(), {}, {}, {}, fallback) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("home:search").assertIsDisplayed()
        compose.onNodeWithTag("home:settings").assertIsDisplayed()
        compose.onNodeWithText("Make it yours").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("home:settings").getUnclippedBoundsInRoot()
        assertTrue(bounds.right <= 720.dp && bounds.bottom <= 400.dp)
    }
    @Test fun metadataPriorityMovesWithRemoteAndKeepsFocus() {
        val order = mutableStateOf(MetadataPriority.defaultOrder)
        compose.runOnUiThread {
            compose.activity.setContent {
                TorrServeTheme { Box(Modifier.fillMaxWidth()) {
                    MetadataPriorityControls(order.value) { provider, direction ->
                        order.value = MetadataPriority.move(order.value, provider, direction)
                    }
                } }
            }
        }
        compose.onNodeWithTag("priority:TMDB:up").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("1. TMDB")
        focused("priority:TMDB:down")
        remote(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("priority:OMDb:label").assertTextEquals("1. IMDb (OMDb)")
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("2. TMDB")
        focused("priority:TMDB:down")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("3. TMDB")
        focused("priority:TMDB:up")
    }
    @Test fun settingsHasPriorityAndNoManualMetadataKeyFields() {
        for(i in 0 until 3) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("priority:OMDb:label"))
        compose.onNodeWithTag("priority:OMDb:label").assertTextEquals("1. IMDb (OMDb)")
        for(label in listOf("TMDB API Key", "OMDb API Key", "Kinopoisk API Key")) {
            compose.onNodeWithTag("field:$label").assertDoesNotExist()
        }
        compose.onNodeWithText("AniList for anime: Off").assertExists()
    }
    @Test fun themeProvidesLightTextOutsideButtonsAndSurfaces() {
        var inherited = Color.Unspecified
        compose.runOnUiThread {
            compose.activity.setContent {
                TorrServeTheme {
                    val color = LocalContentColor.current
                    SideEffect { inherited = color }
                    androidx.tv.material3.Text("Theme regression check")
                }
            }
        }
        compose.onNodeWithText("Theme regression check").assertExists()
        compose.runOnIdle { assertEquals(TextPrimary, inherited) }
    }
}
