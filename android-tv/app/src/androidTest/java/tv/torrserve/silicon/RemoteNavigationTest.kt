package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
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
    @Test fun homeActionTransfersFocusToSearchAndSettings() {
        compose.onNodeWithTag("nav:Home").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("home:search")
        remote(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("nav:Search").assertIsSelected()
        compose.onNodeWithText("Open Settings").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("nav:Settings").assertIsSelected()
        compose.onNodeWithText("Save").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("nav:Home").assertIsFocused()
    }
    @Test fun magnetDialogCanBeCancelledWithRemoteAndRestoresFocus() {
        compose.onNodeWithTag("nav:Home").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("home:search")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("home:add").assertIsFocused()
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
        compose.onNodeWithTag("home:add").assertIsFocused()
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
