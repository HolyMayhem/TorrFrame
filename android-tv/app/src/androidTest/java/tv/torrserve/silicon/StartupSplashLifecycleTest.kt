package tv.torrserve.silicon

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.features.startup.StartupPhase

/** Run each class in a separate instrumentation invocation to get a genuinely new app process. */
@RunWith(AndroidJUnit4::class)
class StartupSplashRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun recreationDuringStartupDoesNotReplayOrAllocateAnotherIntro() {
        val splash = (compose.activity.application as TorrFrameApplication).graph.startupSplash
        compose.waitUntil(4_000) { splash.state.value.renderedVideo || splash.state.value.videoFailed }
        compose.activityRule.scenario.recreate()
        assertNotEquals(StartupPhase.Video, splash.state.value.phase)
        compose.onNodeWithTag("startup:video").assertDoesNotExist()
        compose.waitUntil(8_000) { splash.state.value.phase == StartupPhase.Finished }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("startup:cover").assertDoesNotExist()
        compose.onNodeWithTag("app:screen").assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class StartupSplashBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun leavingDuringVideoReleasesItAndReturningDoesNotShowTheIntro() {
        val splash = (compose.activity.application as TorrFrameApplication).graph.startupSplash
        compose.waitUntil(4_000) { splash.state.value.renderedVideo || splash.state.value.videoFailed }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(StartupPhase.Finished, splash.state.value.phase)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("startup:cover").assertDoesNotExist()
        compose.onNodeWithTag("app:screen").assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class StartupSplashBackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun backClosesTheLauncherInsteadOfOperatingTheCoveredInterface() {
        val activity = compose.activity
        val splash = (activity.application as TorrFrameApplication).graph.startupSplash
        compose.waitUntil(4_000) { splash.state.value.renderedVideo || splash.state.value.videoFailed }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(200)
        assertEquals(StartupPhase.Finished, splash.state.value.phase)
        assertTrue(activity.isFinishing || compose.activityRule.scenario.state == Lifecycle.State.DESTROYED)
    }
}

@RunWith(AndroidJUnit4::class)
class StartupSplashFadeResumeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun backgroundingDuringFadeDoesNotRestoreTheBlackCoverOnResume() {
        val splash = (compose.activity.application as TorrFrameApplication).graph.startupSplash
        compose.waitUntil(10_000) { splash.state.value.phase == StartupPhase.Fading }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(StartupPhase.Finished, splash.state.value.phase)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("startup:cover").assertDoesNotExist()
        compose.onNodeWithTag("app:screen").assertIsDisplayed()
    }
}
