package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.features.startup.StartupPhase
import tv.torrserve.silicon.core.storage.UiScale

@RunWith(AndroidJUnit4::class)
class RefreshedInterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun headerNeverOverlapsAtScaleExtremesAndSettingsRemainReachableInBothLanguages() = runBlocking {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        graph.ready.await()
        val original = graph.settings.state.value
        compose.waitUntil(15_000) { graph.startupSplash.state.value.phase == StartupPhase.Finished }
        try {
            for (language in listOf("ru", "en")) for (scale in listOf(UiScale.MIN, UiScale.DEFAULT, UiScale.MAX)) {
                graph.settings.update { it.copy(uiLanguage = language, uiScalePercent = scale,
                    uiTheme = if (scale == UiScale.MAX) "light" else "dark") }
                compose.waitForIdle()
                val header = compose.onNodeWithTag("app:header").fetchSemanticsNode().boundsInRoot
                val brand = compose.onNodeWithTag("app:brand", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val navigation = compose.onNodeWithTag("app:navigation", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val utilities = compose.onNodeWithTag("app:utilities", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("Brand overlaps navigation at $scale/$language", brand.right <= navigation.left)
                assertTrue("Navigation overlaps utilities at $scale/$language", navigation.right <= utilities.left)
                assertTrue("Header outside safe area at $scale/$language", brand.left >= header.left && utilities.right <= header.right)
                compose.onNodeWithTag("nav:Settings").assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitForIdle()
                compose.onNodeWithTag("settings:categories").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                compose.waitUntil(3_000) { compose.onAllNodesWithTag("settings:categories").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("nav:Settings").assertIsFocused()
            }
        } finally { graph.settings.save(original) }
    }
}
