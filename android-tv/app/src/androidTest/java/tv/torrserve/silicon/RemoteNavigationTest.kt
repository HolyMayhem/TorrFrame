package tv.torrserve.silicon

import tv.torrserve.silicon.core.localization.uiString
import org.junit.Before
import org.junit.After
import android.view.KeyEvent
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toPixelMap
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
import tv.torrserve.silicon.ui.theme.TorrFrameTheme
import tv.torrserve.silicon.ui.components.scrollEdgeFade
import tv.torrserve.silicon.features.settings.MetadataPriorityControls
import tv.torrserve.silicon.core.storage.MetadataPriority
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalLanguage = "en"
    @Before fun languageFixture() = kotlinx.coroutines.runBlocking {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        graph.ready.await(); originalLanguage = graph.settings.state.value.uiLanguage
        graph.settings.update { it.copy(uiLanguage = "ru") }
    }
    @After fun restoreLanguage() = kotlinx.coroutines.runBlocking {
        (compose.activity.application as TorrFrameApplication).graph.settings.update { it.copy(uiLanguage = originalLanguage) }
    }
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
    private fun settingsList() = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("settings:drawer")))
    private fun openSettings() {
        compose.onNodeWithTag("nav:Settings").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("settings:category:PhoneRemote")
        compose.onNodeWithTag("settings:back").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").assertDoesNotExist()
    }
    private fun selectCategory(category: String) {
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:$category"))
        compose.onNodeWithTag("settings:category:$category").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("settings:back")
        if (category == "TorrServer") compose.waitUntil(30_000) {
            compose.onAllNodesWithText(compose.activity.uiString(R.string.server_busy), substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
    private fun openCategory(category: String) { openSettings(); selectCategory(category) }
    @Test fun pointerCategoryEntryAlwaysHasVisibleFocus() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        openSettings()
        for (category in tv.torrserve.silicon.features.settings.SettingsCategory.entries) {
            val tag = "settings:category:${category.name}"
            compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag(tag).performTouchInput { click() }
            focused("settings:back")
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("settings:back").captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, 6]
            val expected = Color(tv.torrserve.silicon.core.storage.UiAccent.colors.getValue(graph.settings.state.value.uiAccent))
            assertEquals("${category.name}: focused background red", expected.red, actual.red, .04f)
            assertEquals("${category.name}: focused background green", expected.green, actual.green, .04f)
            assertEquals("${category.name}: focused background blue", expected.blue, actual.blue, .04f)
            remote(KeyEvent.KEYCODE_BACK)
            focused(tag)
        }
    }
    @Test fun animationsSwitchPersistsAndKeepsFocusOnRecreation() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value.animationsEnabled
        try {
            openCategory("Appearance")
            val toggle = compose.onNodeWithTag("appearance:animations")
            toggle.performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { graph.settings.state.value.animationsEnabled == !original }
            focused("appearance:animations")
            val reopened = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reopened.load() }
            assertEquals(!original, reopened.state.value.animationsEnabled)
            compose.activityRule.scenario.recreate()
            focused("settings:back")
            toggle.assertIsDisplayed()
            toggle.performTouchInput { click() }
            compose.waitUntil(5000) { graph.settings.state.value.animationsEnabled == original }
            focused("appearance:animations")
        } finally {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(animationsEnabled = original) } }
        }
    }
    @Test fun quickClickHasVisualFeedbackAndAnimationSwitchRemovesIt() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        compose.runOnUiThread { compose.activity.setContent {
            TorrFrameTheme(animationsEnabled = enabled.value) {
                tv.torrserve.silicon.ui.components.TvAction("Click feedback", modifier = Modifier.width(240.dp).testTag("motion:button")) { clicks++ }
            }
        } }
        val button = compose.onNodeWithTag("motion:button")
        val width = button.fetchSemanticsNode().boundsInRoot.width
        try {
            compose.mainClock.autoAdvance = false
            button.performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle { assertEquals("Click executes immediately", 1, clicks) }
            compose.mainClock.advanceTimeBy(96)
            assertTrue("Quick click produces a visible pulse", button.fetchSemanticsNode().boundsInRoot.width < width * .98f)
            compose.mainClock.advanceTimeBy(500)
            assertEquals(width, button.fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.runOnIdle { enabled.value = false }
            compose.mainClock.advanceTimeByFrame()
            button.performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(96)
            assertEquals("Disabled animations keep geometry static", width, button.fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.runOnIdle { assertEquals(2, clicks); enabled.value = true }
            compose.mainClock.advanceTimeByFrame()
            button.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(96)
            assertTrue("Pointer feedback works after enabling animations again", button.fetchSemanticsNode().boundsInRoot.width < width * .98f)
            compose.runOnIdle { assertEquals(3, clicks) }
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun backArrowIsCenteredAtBothScalesAndAcceptsRemote() {
        var clicks=0
        for ((scale,light,language) in listOf(Triple(70,false,"en"),Triple(110,true,"ru"))) {
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme(scale,light,animationsEnabled=false) {
                    CompositionLocalProvider(tv.torrserve.silicon.ui.components.LocalUiLanguage provides language) {
                        Box(Modifier.width(240.dp).height(120.dp).background(tv.torrserve.silicon.ui.theme.Background)) {
                            tv.torrserve.silicon.ui.components.TvBackAction(Modifier.testTag("test:back")) { clicks++ }
                        }
                    }
                }
            } }
            val button=compose.onNodeWithTag("test:back")
            val icon=compose.onNodeWithTag("back:icon",useUnmergedTree=true)
            button.performSemanticsAction(SemanticsActions.RequestFocus)
            val bounds=button.fetchSemanticsNode().boundsInRoot
            val arrow=icon.fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.width,bounds.height,.5f)
            assertEquals(bounds.center.x,arrow.center.x,.5f)
            assertEquals(bounds.center.y,arrow.center.y,.5f)
            val pixels=button.captureToImage().toPixelMap()
            val corner=pixels[1,1]
            // A centered circular surface leaves the corner outside the button fill.
            val edge=pixels[pixels.width/2,3]
            assertTrue(kotlin.math.abs(corner.red-edge.red)+kotlin.math.abs(corner.green-edge.green)+kotlin.math.abs(corner.blue-edge.blue)>.1f)
            icon.assertContentDescriptionEquals(if(language=="ru")"Назад" else "Back")
            button.assert(SemanticsMatcher("no visible text") { it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() })
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        assertEquals(2,clicks)
    }
    @Test fun dpadNavigatesTabsAndDrawerBackPreservesSelectedScreen() {
        val uiLanguage=(compose.activity.application as TorrFrameApplication).graph.settings.state.value.uiLanguage
        compose.onNodeWithTag("nav:Search").assertTextEquals(if(uiLanguage=="ru")"Каталог" else "Catalog")
        compose.onNodeWithTag("nav:Home").assertDoesNotExist()
        compose.onNodeWithTag("nav:Library").assertIsFocused().assertIsSelected()
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("nav:Search").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Library").assertIsSelected()
        openSettings()
        val panel = compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag("settings:overlay").fetchSemanticsNode().boundsInRoot
        assertEquals(overlay.width / 4f, panel.width, 2f)
        assertEquals(overlay.right, panel.right, 1f)
        val gear = compose.onNodeWithTag("nav:Settings").fetchSemanticsNode().boundsInRoot
        assertEquals(gear.width, gear.height, 1f)
        assertTrue(gear.left > compose.onNodeWithTag("nav:Library").fetchSemanticsNode().boundsInRoot.right)
        remote(KeyEvent.KEYCODE_DPAD_DOWN); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings:detail:TorrServer").assertExists()
        compose.onNodeWithTag("phone:enabled").assertDoesNotExist()
        val detailPanel = compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(overlay.width / 2f, detailPanel.width, 2f)
        assertEquals(overlay.right, detailPanel.right, 1f)
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        focused("settings:back")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Library").assertIsNotFocused()
        focused("settings:category:TorrServer")
        assertEquals(overlay.width / 4f, compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot.width, 2f)
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        focused("settings:category:TorrServer")
        remote(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("settings:drawer").fetchSemanticsNodes().isEmpty() }
        focused("nav:Settings")
        compose.onNodeWithTag("nav:Library").assertIsSelected()
        compose.onNodeWithTag("nav:Search").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        openCategory("Search")
        remote(KeyEvent.KEYCODE_BACK)
        focused("settings:category:Search")
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        remote(KeyEvent.KEYCODE_BACK)
        focused("nav:Settings")
        compose.onNodeWithTag("nav:Search").assertIsSelected()
    }

    @Test fun everyHeaderButtonCanMoveDownIntoCurrentScreen() {
        for (screen in listOf("Library", "Search")) {
            compose.onNodeWithTag("nav:$screen").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            for (button in listOf("Search", "Library", "Settings")) {
                compose.onNodeWithTag("nav:$button").performSemanticsAction(SemanticsActions.RequestFocus)
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("app:content"))).assertExists()
                compose.onNodeWithTag("nav:$screen").assertIsSelected()
            }
        }
    }
    @Test fun appearanceLeftMovesBetweenControlsAndNeverReturnsToCategories() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value
        try {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiScalePercent = 85) } }
            openCategory("Appearance")
            settingsList().performScrollToNode(hasTestTag("scale:plus"))
            compose.onNodeWithTag("scale:plus").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_LEFT); focused("scale:minus")
            remote(KeyEvent.KEYCODE_DPAD_RIGHT); focused("scale:plus")
            settingsList().performScrollToNode(hasText("ru"))
            compose.onNodeWithText("ru").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_LEFT); compose.onNodeWithText("en").assertIsFocused()
            settingsList().performScrollToNode(hasTestTag("appearance:light"))
            compose.onNodeWithTag("appearance:light").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_LEFT); focused("appearance:dark")
            settingsList().performScrollToNode(hasTestTag("appearance:accent:blue"))
            compose.onNodeWithTag("appearance:accent:blue").performSemanticsAction(SemanticsActions.RequestFocus)
            for (color in listOf("pink", "orange", "white", "green")) {
                remote(KeyEvent.KEYCODE_DPAD_LEFT); focused("appearance:accent:$color")
            }
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { graph.settings.state.value.uiAccent == "green" }
            focused("appearance:accent:green")
            compose.onNodeWithTag("settings:detail:Appearance").assertExists()
            remote(KeyEvent.KEYCODE_BACK); focused("settings:category:Appearance")
            remote(KeyEvent.KEYCODE_BACK); focused("nav:Settings")
        } finally { kotlinx.coroutines.runBlocking { graph.settings.save(original) } }
    }
    @Test fun scaleReflowsContentPersistsAndKeepsRemoteFocusAtBothLimits() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value.uiScalePercent
        fun waitScale(value: Int) {
            compose.waitUntil(5000) { graph.settings.state.value.uiScalePercent == value }
            compose.onNodeWithTag("scale:value").assertTextEquals("$value%")
            val drawer = compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot
            val overlay = compose.onNodeWithTag("settings:overlay").fetchSemanticsNode().boundsInRoot
            assertEquals(overlay.width / 2f, drawer.width, 2f)
        }
        try {
            openCategory("Appearance")
            settingsList().performScrollToNode(hasTestTag("scale:reset"))
            compose.onNodeWithTag("scale:reset").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            waitScale(85)
            val originalWidth = compose.onNodeWithTag("nav:Library").fetchSemanticsNode().boundsInRoot.width
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
            val smallWidth = compose.onNodeWithTag("nav:Library").fetchSemanticsNode().boundsInRoot.width
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
            settingsList().performScrollToNode(hasTestTag("scale:value"))
            compose.onNodeWithTag("scale:value").assertTextEquals("110%")
            compose.onNodeWithTag("scale:reset").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER); waitScale(85)
            focused("scale:reset")
        } finally {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiScalePercent = original) } }
        }
    }
    @Test fun settingsDialogCanBeCancelledWithRemoteAndRestoresFocus() {
        compose.onNodeWithTag("nav:Library").assertIsFocused()
        openCategory("TorrServer")
        compose.onNodeWithTag("settings:drawer").assertExists()
        settingsList().performScrollToNode(hasTestTag("field:Порт встроенного сервера"))
        compose.onNodeWithTag("field:Порт встроенного сервера").performSemanticsAction(SemanticsActions.RequestFocus)
        focused("field:Порт встроенного сервера")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("dialog:editor")
        compose.waitUntil(3000) { !compose.activity.hasWindowFocus() }
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        focused("dialog:editor")
        compose.onNodeWithTag("settings:detail:TorrServer").assertExists()
        // With the AVD hardware keyboard enabled, DOWN leaves the editor for its actions.
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("dialog:done")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("dialog:cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("dialog:cancel").assertDoesNotExist()
        focused("field:Порт встроенного сервера")
        remote(KeyEvent.KEYCODE_BACK)
        focused("settings:category:TorrServer")
    }
    @Test fun engineScreenBackRestoresSettingsFocusWithRemote() {
        openCategory("TorrServer")
        settingsList().performScrollToNode(hasTestTag("server:engine"))
        compose.onNodeWithTag("server:engine").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused("engine:back")
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        focused("engine:back")
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("engine:back").assertDoesNotExist()
        compose.onNodeWithTag("settings:drawer").assertExists()
        focused("server:engine")
    }
    @Test fun settingsShowsActualWebAddressAndLanSwitchAcceptsRemote() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        openCategory("TorrServer")
        settingsList().performScrollToNode(hasTestTag("server:web-url"))
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
                compose.onAllNodesWithText(compose.activity.uiString(R.string.server_busy), substring = true)
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
        openCategory("TorrServer")
        val tag = "field:Адрес TorrServer"
        settingsList().performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("dialog:editor").performTextReplacement("ftp://invalid")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        focused(tag)
        compose.onNodeWithText(compose.activity.uiString(R.string.server_invalid_url)).assertExists()
    }
    @Test fun manualMagnetActionsAreAbsentFromLibrary() {
        compose.onNodeWithTag("nav:Library").assertIsFocused()
        compose.onNodeWithTag("nav:Home").assertDoesNotExist()
        compose.onNodeWithText("Add Magnet").assertDoesNotExist()
        compose.onNodeWithText("Add Magnet Link").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("Открыть каталог").fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun appearanceAppliesImmediatelyAndHiddenFooterFreesContent() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value
        try {
            openCategory("Appearance")
            settingsList().performScrollToNode(hasTestTag("appearance:light"))
            compose.onNodeWithTag("appearance:light").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { graph.settings.state.value.uiTheme == "light" }
            focused("appearance:light")
            val height = compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height
            compose.onNodeWithTag("appearance:hints").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { !graph.settings.state.value.showNavigationHints }
            compose.onNodeWithTag("app:footer").assertDoesNotExist()
            assertEquals(compose.onNodeWithTag("app:screen").fetchSemanticsNode().boundsInRoot.bottom,
                compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
            assertTrue(compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height > height)
            val reloaded = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reloaded.load() }
            assertEquals("light", reloaded.state.value.uiTheme)
            org.junit.Assert.assertFalse(reloaded.state.value.showNavigationHints)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("app:footer").assertDoesNotExist()
            settingsList().performScrollToNode(hasTestTag("appearance:dark"))
            compose.onNodeWithTag("appearance:dark").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { graph.settings.state.value.uiTheme == "dark" }
        } finally {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiTheme = original.uiTheme, showNavigationHints = original.showNavigationHints) } }
        }
    }
    @Test fun focusedInputFieldsStayWithinTheirGroups() {
        openCategory("PhoneRemote")
        for ((category, label) in listOf("PhoneRemote" to compose.activity.uiString(R.string.phone_port), "TorrServer" to compose.activity.uiString(R.string.server_url))) {
            if (category == "TorrServer") { remote(KeyEvent.KEYCODE_BACK); selectCategory(category) }
            val tag = "field:$label"
            settingsList().performScrollToNode(hasTestTag(tag))
            val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            focused(tag)
            val after = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val group = compose.onNodeWithTag("field-group:$label").fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, after.width, .5f)
            assertTrue(after.left >= group.left && after.right <= group.right && after.bottom <= group.bottom)
        }
    }
    @Test fun settingsAutosaveSurvivesViewModelClearAndMetadataActionsFitDrawer() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value
        val models = androidx.lifecycle.ViewModelStore()
        val vm = tv.torrserve.silicon.features.settings.SettingsViewModel(graph)
        models.put("autosave", vm)
        val audio = if (original.audioLanguage == "ru") "en" else "ru"
        val subtitles = if (original.subtitleLanguage == "en") "ru" else "en"
        try {
            compose.waitUntil(5000) { vm.state.value.draft == original }
            compose.runOnUiThread {
                vm.edit { it.copy(audioLanguage = audio) }
                vm.edit { it.copy(subtitleLanguage = subtitles) }
                vm.edit { it.copy(resume = !original.resume) }
                models.clear()
            }
            compose.waitUntil(5000) { graph.settings.state.value.let { it.audioLanguage == audio && it.subtitleLanguage == subtitles && it.resume != original.resume } }
            val reloaded = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reloaded.load() }
            assertEquals(audio, reloaded.state.value.audioLanguage)
            assertEquals(subtitles, reloaded.state.value.subtitleLanguage)
            openCategory("Metadata")
            compose.onNodeWithText(compose.activity.uiString(R.string.settings_save)).assertDoesNotExist()
            settingsList().performScrollToNode(hasText(compose.activity.uiString(R.string.settings_clear_metadata)))
            val language = compose.onNodeWithText("en-US").fetchSemanticsNode().boundsInRoot
            val clear = compose.onNodeWithText(compose.activity.uiString(R.string.settings_clear_metadata)).fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot
            assertTrue(language.left >= panel.left && language.right <= panel.right)
            assertTrue(clear.left >= panel.left && clear.right <= panel.right)
            assertTrue(clear.top >= language.bottom)
        } finally {
            models.clear()
            kotlinx.coroutines.runBlocking { graph.settings.save(original) }
        }
    }
    @Test fun allAccentChoicesPersistAndAboutIsLast() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val original = graph.settings.state.value
        try {
            openCategory("Appearance")
            settingsList().performScrollToNode(hasTestTag("appearance:accent:green"))
            for (key in tv.torrserve.silicon.core.storage.UiAccent.colors.keys) {
                val tag = "appearance:accent:$key"
                compose.onNodeWithTag("settings:detail:Appearance").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
                remote(KeyEvent.KEYCODE_ENTER)
                compose.waitUntil(5000) { graph.settings.state.value.uiAccent == key }
                focused(tag)
            }
            val reloaded = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reloaded.load() }
            assertEquals("blue", reloaded.state.value.uiAccent)
            compose.activityRule.scenario.recreate()
            settingsList().performScrollToNode(hasTestTag("appearance:accent:blue"))
            compose.onNodeWithTag("appearance:accent:blue").assertExists()
            remote(KeyEvent.KEYCODE_BACK)
            selectCategory("About")
            compose.onNodeWithText(compose.activity.uiString(R.string.settings_licenses)).assertIsDisplayed()
        } finally { kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiAccent = original.uiAccent) } } }
    }
    @Test fun qrPanelIsWideAndShowsSixDigitAlternative() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val originalEnabled = graph.phonePreferences.state.value.enabled
        try {
            kotlinx.coroutines.runBlocking { graph.phoneRemote.enabled(true) }
            compose.waitUntil(15_000) { graph.phoneRemote.state.value.status == tv.torrserve.silicon.phoneRemote.server.PhoneRemoteStatus.Running }
            openCategory("PhoneRemote")
            compose.onNodeWithTag("phone:qr").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("phone:pair-code").fetchSemanticsNodes().isNotEmpty() }
            val panel = compose.onNodeWithTag("phone:qr:panel").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.width > compose.activity.window.decorView.width * .7f)
            val digits = compose.onNodeWithTag("phone:pair-code").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.replace(" ", "")
            assertTrue(digits.matches(Regex("[0-9]{6}")))
            remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("phone:qr:panel").assertDoesNotExist()
        } finally { kotlinx.coroutines.runBlocking { graph.phoneRemote.enabled(originalEnabled) } }
    }
    @Test fun movieHasLoadedPosterBesideMetadataAndNoRemovedActions() {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        val file = java.io.File(compose.activity.cacheDir, "poster-test.png")
        val bitmap = android.graphics.Bitmap.createBitmap(32, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val item = tv.torrserve.silicon.core.models.LibraryItem("a".repeat(40), "Original", 0,
            tv.torrserve.silicon.core.models.Metadata("OMDb", "test", "Movie title", "Original", 2024,
                tv.torrserve.silicon.core.models.MediaKind.Movie, overview = "Film description", poster = file.toURI().toString(), rating = 8.4, genres = "Drama", runtime = 110))
        val store = androidx.lifecycle.ViewModelStore()
        val vm = tv.torrserve.silicon.features.library.LibraryViewModel(graph)
        store.put("movie", vm)
        try {
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme { tv.torrserve.silicon.features.library.MovieDetailScreen(item,
                    tv.torrserve.silicon.features.library.LibraryUi(), vm, {}, play = { _, _ -> }) }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("movie:poster:loaded").fetchSemanticsNodes().isNotEmpty() }
            val cover = compose.onNodeWithTag("movie:poster").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("movie:title").fetchSemanticsNode().boundsInRoot
            assertTrue(cover.right < title.left)
            compose.onNodeWithTag("movie:overview").assertTextEquals("Film description")
            compose.onNodeWithTag("movie:genres").assertTextEquals("Drama")
            compose.onNodeWithTag("movie:facts").assertTextContains("2024", substring = true)
            compose.onNodeWithText(compose.activity.uiString(R.string.metadata_edit)).assertDoesNotExist()
            compose.onNodeWithText("Find Torrents").assertDoesNotExist()
        } finally { store.clear(); file.delete() }
    }
    @Test fun metadataPriorityMovesWithRemoteAndKeepsFocus() {
        val order = mutableStateOf(MetadataPriority.defaultOrder)
        compose.runOnUiThread {
            compose.activity.setContent {
                TorrFrameTheme { Box(Modifier.fillMaxWidth()) {
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
        compose.onNodeWithTag("priority:OMDb:label").assertTextEquals("1. OMDB")
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("2. TMDB")
        focused("priority:TMDB:down")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("3. TMDB")
        focused("priority:TMDB:up")
    }
    @Test fun settingsHasPriorityAndNoManualMetadataKeyFields() {
        openCategory("Metadata")
        settingsList().performScrollToNode(hasTestTag("priority:OMDb:label"))
        compose.onNodeWithTag("priority:OMDb:label").assertTextEquals("1. OMDB")
        for(label in listOf("TMDB API Key", "OMDb API Key", "Kinopoisk API Key")) {
            compose.onNodeWithTag("field:$label").assertDoesNotExist()
        }
        compose.onNodeWithTag("metadata:anilist").assertIsOff()
    }
    @Test fun bothThemesProvideCorrectTextAndGreenAccent() {
        var inherited = Color.Unspecified
        var primary = Color.Unspecified
        val light = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                TorrFrameTheme(light = light.value) {
                    val color = LocalContentColor.current
                    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
                    SideEffect { inherited = color; primary = accent }
                    androidx.tv.material3.Text("Theme regression check")
                }
            }
        }
        compose.onNodeWithText("Theme regression check").assertExists()
        compose.runOnIdle {
            assertEquals(tv.torrserve.silicon.ui.theme.DarkPalette.text, inherited)
            assertEquals(Color(0xFF008800), primary)
            light.value = true
        }
        compose.runOnIdle {
            assertEquals(tv.torrserve.silicon.ui.theme.LightPalette.text, inherited)
            assertEquals(Color(0xFF008800), primary)
        }
    }
    @Test fun whiteAccentToggleRemainsReadableOnAndOffWithAndWithoutFocus() {
        for (light in listOf(false, true)) {
            val checked = mutableStateOf(true)
            var clearFocus: () -> Unit = {}
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme(light = light, accent = "white", animationsEnabled = false) {
                    val focus = androidx.compose.ui.platform.LocalFocusManager.current
                    SideEffect { clearFocus = { focus.clearFocus(force = true) } }
                    Box(Modifier.width(360.dp).background(tv.torrserve.silicon.ui.theme.Background)) {
                        tv.torrserve.silicon.ui.components.TvToggle("Contrast", checked.value,
                            Modifier.testTag("test:toggle")) { checked.value = it }
                    }
                }
            } }
            val toggle = compose.onNodeWithTag("test:toggle")
            fun contrast(left: Color, right: Color): Float {
                val a = left.luminance(); val b = right.luminance()
                return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
            }
            fun track() = compose.onNodeWithTag("toggle:track", useUnmergedTree = true).captureToImage().toPixelMap()
            // The two centers cover the empty track and the thumb at opposite ends.
            fun assertReadableOn() {
                toggle.assertIsOn()
                val pixels = track()
                assertTrue("White checked thumb must contrast with the track", contrast(
                    pixels[pixels.width * 12 / 42, pixels.height / 2],
                    pixels[pixels.width * 30 / 42, pixels.height / 2]) >= 3f)
            }
            compose.runOnIdle { clearFocus() }
            assertReadableOn()
            toggle.performSemanticsAction(SemanticsActions.RequestFocus)
            assertReadableOn()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            toggle.assertIsOff()
            val off = track()
            assertTrue("Off thumb remains on the left", off[off.width * 12 / 42, off.height / 2].luminance() >
                off[off.width * 30 / 42, off.height / 2].luminance())
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            assertReadableOn()
        }
    }
    @Test fun focusedCheckedToggleOutlineIsVisibleForEveryAccent() {
        for (light in listOf(false, true)) for (accent in listOf("green", "white", "orange", "pink", "blue")) {
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme(light = light, accent = accent, animationsEnabled = false) {
                    Box(Modifier.width(360.dp).background(tv.torrserve.silicon.ui.theme.Background)) {
                        tv.torrserve.silicon.ui.components.TvToggle("Contrast", true, Modifier.testTag("test:toggle")) { }
                    }
                }
            } }
            compose.onNodeWithTag("test:toggle").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("test:toggle").assertIsFocused().assertIsOn()
            val track = compose.onNodeWithTag("toggle:track", useUnmergedTree = true).captureToImage().toPixelMap()
            val outline = track[track.width / 2, 1].luminance()
            val fill = track[track.width * 12 / 42, track.height / 2].luminance()
            assertTrue("Focused $accent toggle outline must remain visible (light=$light)",
                (maxOf(outline, fill) + .05f) / (minOf(outline, fill) + .05f) >= 2f)
        }
    }
    @Test fun scrollEdgesFadeOnlyWhereContentContinues() {
        for (vertical in listOf(false, true)) {
            val count = mutableStateOf(10)
            var scroll: (Int) -> Unit = {}
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme(animationsEnabled = false) {
                    val state = androidx.compose.foundation.lazy.rememberLazyListState()
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    SideEffect { scroll = { index -> scope.launch { state.scrollToItem(index) }; Unit } }
                    val modifier = Modifier.width(180.dp).height(72.dp).background(Color.Black)
                        .scrollEdgeFade(state, vertical = vertical, background = Color.Black).testTag("test:fade")
                    if (vertical) androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = modifier) {
                        items(count.value) { Box(Modifier.width(180.dp).height(if (count.value == 1) 72.dp else 40.dp).background(Color.White)) }
                    } else androidx.compose.foundation.lazy.LazyRow(state = state, modifier = modifier) {
                        items(count.value) { Box(Modifier.width(if (count.value == 1) 180.dp else 80.dp).height(72.dp).background(Color.White)) }
                    }
                }
            } }
            fun assertEdges(startFaded: Boolean, endFaded: Boolean) {
                val pixels = compose.onNodeWithTag("test:fade").captureToImage().toPixelMap()
                fun sample(fraction: Float): Float {
                    val x = if (vertical) pixels.width / 2 else (fraction * (pixels.width - 1)).toInt()
                    val y = if (vertical) (fraction * (pixels.height - 1)).toInt() else pixels.height / 2
                    return pixels[x, y].luminance()
                }
                assertTrue("Center stays opaque", sample(.5f) > .95f)
                assertTrue("Start fade=$startFaded, vertical=$vertical", if (startFaded) sample(.01f) < .1f else sample(.01f) > .95f)
                assertTrue("End fade=$endFaded, vertical=$vertical", if (endFaded) sample(.99f) < .1f else sample(.99f) > .95f)
                if (startFaded) assertTrue("Start is a gradient", sample(.01f) < sample(.06f) && sample(.06f) < sample(.15f))
                if (endFaded) assertTrue("End is a gradient", sample(.85f) > sample(.94f) && sample(.94f) > sample(.99f))
            }
            assertEdges(false, true)
            compose.runOnIdle { scroll(3) }
            assertEdges(true, true)
            compose.runOnIdle { scroll(9) }
            assertEdges(true, false)
            compose.runOnIdle { count.value = 1 }
            assertEdges(false, false)
        }
    }
    @Test fun headerActionsShareHeightAndAlignmentAndAcceptRemote() {
        var clicks = 0
        for (scale in listOf(70, 110)) {
            compose.runOnUiThread { compose.activity.setContent {
                TorrFrameTheme(uiScalePercent = scale, animationsEnabled = false) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.width(440.dp).height(60.dp).background(tv.torrserve.silicon.ui.theme.Background),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        tv.torrserve.silicon.phoneRemote.ui.PhoneRemoteShortcut { clicks++ }
                        tv.torrserve.silicon.ui.components.ServerStatusIndicator(false,
                            tv.torrserve.silicon.core.server.ServerState.Stopped,
                            tv.torrserve.silicon.core.server.ConnectionState(tv.torrserve.silicon.core.server.ConnectionStatus.Connected)) { clicks++ }
                        tv.torrserve.silicon.ui.components.SettingsShortcut { clicks++ }
                    }
                }
            } }
            val tags = listOf("phone:shortcut", "server:indicator", "nav:Settings")
            val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            bounds.forEach {
                assertEquals(bounds.first().height, it.height, .5f)
                assertEquals(bounds.first().center.y, it.center.y, .5f)
            }
            assertEquals(bounds.first().width, bounds.last().width, .5f)
            compose.onNodeWithTag(tags.first()).performSemanticsAction(SemanticsActions.RequestFocus)
            for ((index, tag) in tags.withIndex()) {
                if (index > 0) remote(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused(tag)
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
            }
        }
        assertEquals(6, clicks)
    }
}
