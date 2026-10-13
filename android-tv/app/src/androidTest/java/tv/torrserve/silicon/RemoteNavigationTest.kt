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
import tv.torrserve.silicon.ui.theme.TorrServeTheme
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
    @Test fun dpadNavigatesThreeTabsAndBackReturnsLibrary() {
        compose.onNodeWithTag("nav:Home").assertDoesNotExist()
        compose.onNodeWithTag("nav:Library").assertIsFocused().assertIsSelected()
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("nav:Search").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        for (tab in listOf("Library", "Settings")) {
            remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("nav:$tab").assertIsFocused().assertIsSelected()
        }
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("nav:Library").assertIsFocused().assertIsSelected()
    }
    @Test fun scaleReflowsContentPersistsAndKeepsRemoteFocusAtBothLimits() {
        val graph = (compose.activity.application as TorrServeApplication).graph
        val original = graph.settings.state.value.uiScalePercent
        fun waitScale(value: Int) {
            compose.waitUntil(5000) { graph.settings.state.value.uiScalePercent == value }
            compose.onNodeWithTag("scale:value").assertTextEquals("$value%")
        }
        try {
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("scale:reset"))
            compose.onNodeWithTag("scale:reset").performClick()
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
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("scale:value"))
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
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
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
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
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
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
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
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
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
    @Test fun manualMagnetActionsAreAbsentFromLibrary() {
        compose.onNodeWithTag("nav:Library").assertIsFocused()
        compose.onNodeWithTag("nav:Home").assertDoesNotExist()
        compose.onNodeWithText("Add Magnet").assertDoesNotExist()
        compose.onNodeWithText("Add Magnet Link").assertDoesNotExist()
        compose.onNodeWithText("Find Torrents").assertExists()
    }
    @Test fun appearanceAppliesImmediatelyAndHiddenFooterFreesContent() {
        val graph = (compose.activity.application as TorrServeApplication).graph
        val original = graph.settings.state.value
        try {
            remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("appearance:light"))
            compose.onNodeWithTag("appearance:light").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { graph.settings.state.value.uiTheme == "light" }
            focused("appearance:light")
            val height = compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height
            compose.onNodeWithTag("appearance:hints").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000) { !graph.settings.state.value.showNavigationHints }
            compose.onNodeWithTag("app:footer").assertDoesNotExist()
            assertTrue(compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot.height > height)
            val reloaded = tv.torrserve.silicon.core.storage.SettingsStore(compose.activity)
            kotlinx.coroutines.runBlocking { reloaded.load() }
            assertEquals("light", reloaded.state.value.uiTheme)
            org.junit.Assert.assertFalse(reloaded.state.value.showNavigationHints)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("app:footer").assertDoesNotExist()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("appearance:dark"))
            compose.onNodeWithTag("appearance:dark").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { graph.settings.state.value.uiTheme == "dark" }
        } finally {
            kotlinx.coroutines.runBlocking { graph.settings.update { it.copy(uiTheme = original.uiTheme, showNavigationHints = original.showNavigationHints) } }
        }
    }
    @Test fun focusedInputFieldsStayWithinTheirGroups() {
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        for (label in listOf(compose.activity.getString(R.string.phone_port), compose.activity.getString(R.string.server_url))) {
            val tag = "field:$label"
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
            val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            focused(tag)
            val after = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val group = compose.onNodeWithTag("field-group:$label").fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, after.width, .5f)
            assertTrue(after.left >= group.left && after.right <= group.right && after.bottom <= group.bottom)
        }
    }
    @Test fun movieHasLoadedPosterBesideMetadataAndNoRemovedActions() {
        val graph = (compose.activity.application as TorrServeApplication).graph
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
                TorrServeTheme { tv.torrserve.silicon.features.library.MovieDetailScreen(item,
                    tv.torrserve.silicon.features.library.LibraryUi(), vm, {}, { _, _ -> }) }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("movie:poster:loaded").fetchSemanticsNodes().isNotEmpty() }
            val cover = compose.onNodeWithTag("movie:poster").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("movie:title").fetchSemanticsNode().boundsInRoot
            assertTrue(cover.right < title.left)
            compose.onNodeWithTag("movie:overview").assertTextEquals("Film description")
            compose.onNodeWithTag("movie:genres").assertTextEquals("Drama")
            compose.onNodeWithTag("movie:facts").assertTextContains("2024", substring = true)
            compose.onNodeWithText(compose.activity.getString(R.string.metadata_edit)).assertDoesNotExist()
            compose.onNodeWithText("Find Torrents").assertDoesNotExist()
        } finally { store.clear(); file.delete() }
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
        compose.onNodeWithTag("priority:OMDb:label").assertTextEquals("1. OMDB")
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("2. TMDB")
        focused("priority:TMDB:down")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("priority:TMDB:label").assertTextEquals("3. TMDB")
        focused("priority:TMDB:up")
    }
    @Test fun settingsHasPriorityAndNoManualMetadataKeyFields() {
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("priority:OMDb:label"))
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
                TorrServeTheme(light = light.value) {
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
}
