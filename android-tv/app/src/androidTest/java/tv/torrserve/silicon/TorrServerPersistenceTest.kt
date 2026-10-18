package tv.torrserve.silicon

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.SettingsStore

@RunWith(AndroidJUnit4::class)
class TorrServerPersistenceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrFrameApplication
    @Test fun connectionAndBootPreferenceSurviveReloadWithoutLosingExistingSettings() = runBlocking {
        app.graph.ready.await()
        val original = app.graph.settings.state.value
        val store = SettingsStore(app)
        try {
            store.save(original.copy(torrServerUrl = "https://example.com/torrserver/", useLocalServer = false,
                startOnBoot = true, engineSettingsInitialized = true, lanAccess = false, uiScalePercent = 95,
                uiTheme = "light", showNavigationHints = false, uiAccent = "pink"))
            val reopened = SettingsStore(app); reopened.load()
            assertEquals("https://example.com/torrserver", reopened.state.value.torrServerUrl)
            assertFalse(reopened.state.value.useLocalServer); assertTrue(reopened.state.value.startOnBoot)
            assertTrue(reopened.state.value.engineSettingsInitialized)
            assertFalse(reopened.state.value.lanAccess)
            assertEquals(95, reopened.state.value.uiScalePercent)
            assertEquals("light", reopened.state.value.uiTheme); assertFalse(reopened.state.value.showNavigationHints)
            assertEquals("pink", reopened.state.value.uiAccent)
            assertEquals(original.metadataOrder, reopened.state.value.metadataOrder)
            assertEquals(original.jackettKey, reopened.state.value.jackettKey)
            assertEquals(original.resume, reopened.state.value.resume)
        } finally { store.save(original) }
    }
    @Test fun legacyPortBecomesLocalAddress() = runBlocking {
        app.graph.ready.await()
        val original = app.graph.settings.state.value
        val store = SettingsStore(app)
        try {
            val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().remove("torrServerUrl").remove("useLocalServer").remove("startOnBoot").remove("lanAccess").remove("uiScalePercent").putInt("port", 8097).commit()
            store.load()
            assertEquals("http://localhost:8097", store.state.value.torrServerUrl)
            assertTrue(store.state.value.useLocalServer); assertFalse(store.state.value.startOnBoot)
            assertTrue(store.state.value.lanAccess); assertTrue(store.state.value.needsLocalService)
            assertEquals(85, store.state.value.uiScalePercent)
        } finally { store.save(original) }
    }
    @Test fun concurrentUpdatesDoNotLoseOtherFields() = runBlocking {
        app.graph.ready.await()
        val original = app.graph.settings.state.value
        val store = SettingsStore(app); store.load()
        try {
            coroutineScope {
                launch { store.update { it.copy(startOnBoot = true) } }
                launch { store.update { it.copy(audioLanguage = "ru") } }
            }
            val reopened = SettingsStore(app); reopened.load()
            assertTrue(reopened.state.value.startOnBoot); assertEquals("ru", reopened.state.value.audioLanguage)
        } finally { store.save(original) }
    }
    @Test fun restartRetainsEngineSettingsAndSingleManagedProcess() = runBlocking {
        val graph = app.graph; graph.ready.await()
        if (!graph.settings.state.value.useLocalServer) return@runBlocking
        graph.server.requireRunning()
        val original = graph.api.settings()
        try {
            val changed = EngineSettings.merge(original, mapOf("PreloadCache" to if (original.getInt("PreloadCache") == 41) "42" else "41"))
            graph.api.setSettings(changed)
            EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))
            graph.server.restart()
            EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))
            val pid = (graph.server.state.value as ServerState.Running).pid
            graph.server.start()
            assertEquals(pid, (graph.server.state.value as ServerState.Running).pid)
        } finally { graph.api.setSettings(original) }
    }
}
