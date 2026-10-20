package tv.torrframe

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.core.storage.Settings
import tv.torrframe.features.startup.StartupPhase
import tv.torrframe.onboarding.SetupStep

@RunWith(AndroidJUnit4::class)
class SetupWizardIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as TorrFrameApplication).graph
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    private fun step(step: SetupStep) {
        compose.waitUntil(5_000) { graph.setup.state.value.step == step && !graph.setup.state.value.busy }
        compose.onNodeWithTag("setup:page:${step.name}").assertIsDisplayed()
    }
    private fun start(): Settings = runBlocking {
        graph.ready.await()
        compose.waitUntil(15_000) { graph.startupSplash.state.value.phase == StartupPhase.Finished }
        graph.setup.restart()
        step(SetupStep.Welcome)
        graph.settings.state.value
    }
    private fun close(original: Settings) = runBlocking {
        graph.setup.finish()
        compose.waitUntil(5000) { graph.setup.state.value.step == SetupStep.Finished && !graph.setup.state.value.busy }
        graph.settings.save(original)
    }

    @Test fun remoteNavigationLanguageAppearanceAndCompletionSurviveRecreation() {
        val original = start()
        try {
            click("setup:next"); step(SetupStep.Language)
            click("setup:language:en")
            compose.waitUntil { graph.settings.state.value.uiLanguage == "en" }
            compose.onNodeWithText("Choose your language").assertIsDisplayed()
            click("setup:next"); step(SetupStep.Appearance)
            click("setup:theme:light")
            compose.waitUntil { graph.settings.state.value.uiTheme == "light" }
            click("setup:accent:white")
            compose.waitUntil { graph.settings.state.value.uiAccent == "white" }
            compose.onNodeWithTag("setup:next").assertIsDisplayed()
            click("setup:next"); step(SetupStep.PhoneRemote)
            compose.onNodeWithTag("setup:phone:enabled").assertIsDisplayed()
            click("setup:next"); step(SetupStep.Server)
            click("setup:next")
            compose.waitUntil { graph.setup.state.value.step == SetupStep.Finished }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("setup:panel").assertDoesNotExist()
            assertEquals("en", graph.settings.state.value.uiLanguage)
            assertEquals("light", graph.settings.state.value.uiTheme)
            assertEquals("white", graph.settings.state.value.uiAccent)
        } finally { close(original) }
    }

    @Test fun interruptedSetupResumesBackAndSkipWorkAndAboutReopensWizard() {
        val original = start()
        try {
            click("setup:next"); step(SetupStep.Language)
            click("setup:next"); step(SetupStep.Appearance)
            compose.activityRule.scenario.recreate()
            compose.waitForIdle(); step(SetupStep.Appearance)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle(); step(SetupStep.Language)
            click("setup:back"); step(SetupStep.Welcome)
            click("setup:skip")
            compose.waitUntil { graph.setup.state.value.step == SetupStep.Finished }
            compose.waitForIdle()
            click("nav:Settings")
            compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:About"))
            click("settings:category:About")
            compose.onNodeWithTag("settings:detail:About").performScrollToNode(hasTestTag("settings:setup"))
            click("settings:setup"); step(SetupStep.Welcome)
        } finally { close(original) }
    }
}
