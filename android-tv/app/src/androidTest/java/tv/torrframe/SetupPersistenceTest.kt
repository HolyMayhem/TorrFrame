package tv.torrframe

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.onboarding.*

@RunWith(AndroidJUnit4::class)
class SetupPersistenceTest {
    @Test fun cleanInstallResumesAndCompletedSetupDoesNotReturn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("test-setup-${System.nanoTime()}", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = SetupController(prefs, scope)
            first.initialize(existingInstallation = false)
            assertEquals(SetupStep.Welcome, first.state.value.step)
            first.next()
            withTimeout(3000) { first.state.first { it.step == SetupStep.Language && !it.busy } }
            first.next()
            withTimeout(3000) { first.state.first { it.step == SetupStep.Appearance && !it.busy } }
            val reopened = SetupController(prefs, scope)
            reopened.initialize(existingInstallation = true)
            assertEquals(SetupStep.Appearance, reopened.state.value.step)
            reopened.finish()
            withTimeout(3000) { reopened.state.first { it.step == SetupStep.Finished && !it.busy } }
            val completed = SetupController(prefs, scope)
            completed.initialize(existingInstallation = false)
            assertEquals(SetupStep.Finished, completed.state.value.step)
        } finally { scope.cancel(); prefs.edit().clear().commit() }
    }

    @Test fun existingUsersAreNotInterruptedAndReplayPreservesSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("test-setup-${System.nanoTime()}", Context.MODE_PRIVATE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap()
            val controller = SetupController(prefs, scope)
            controller.initialize(existingInstallation = true)
            assertEquals(SetupStep.Finished, controller.state.value.step)
            controller.restart()
            withTimeout(3000) { controller.state.first { it.step == SetupStep.Welcome && !it.busy } }
            controller.finish()
            withTimeout(3000) { controller.state.first { it.step == SetupStep.Finished && !it.busy } }
            assertEquals(settings, context.getSharedPreferences("settings", Context.MODE_PRIVATE).all)
        } finally { scope.cancel(); prefs.edit().clear().commit() }
    }
}
