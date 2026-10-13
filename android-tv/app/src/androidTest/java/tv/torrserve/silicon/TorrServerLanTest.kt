package tv.torrserve.silicon

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.phoneRemote.discovery.LocalAddressResolver

/** Use an isolated ARM TV emulator: these tests restart the packaged server. */
@RunWith(AndroidJUnit4::class)
class TorrServerLanTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrServeApplication
    private val graph get() = app.graph
    private fun pid() = (graph.server.state.value as ServerState.Running).pid
    private suspend fun address(): String = withTimeout(10_000) {
        graph.serverWebAddress.url.first { it.isNotEmpty() }
    }
    private fun web(url: String): String = graph.http.client.newCall(Request.Builder().url(url).build()).execute().use {
        assertEquals(200, it.code)
        it.body.string()
    }

    @Test fun lanAndLoopbackShareServerAndSwitchPreservesEngineSettings() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.ready.await()
            val saved = graph.settings.state.value
            graph.serverActions.lan(true)
            val engine = graph.api.settings()
            try {
                val url = address()
                assertEquals("http://${LocalAddressResolver(app).current()}:${saved.port}", url)
                assertEquals(graph.api.version(), web("$url/echo").trim())
                assertTrue(web("$url/").contains("<html", ignoreCase = true))
                val originalPid = pid()
                graph.server.start()
                assertEquals(originalPid, pid())
                assertTrue(graph.settings.state.value.torrServerUrl.contains("localhost"))

                val changed = EngineSettings.merge(engine, mapOf("PreloadCache" to "41"))
                graph.api.at(url).setSettings(changed)
                EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))
                graph.serverActions.lan(false)
                assertTrue(graph.api.version().startsWith("MatriX"))
                assertEquals("", withTimeout(5000) { graph.serverWebAddress.url.first { it.isEmpty() } })
                val external = runCatching { web("$url/echo") }
                assertTrue("Loopback mode refuses the LAN interface", external.isFailure)
                EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))
                graph.serverActions.lan(true)
                assertEquals(graph.api.version(), web("${address()}/echo").trim())
                EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))

                val testPort = if (saved.port == 8092) 8093 else 8092
                graph.serverActions.connect("http://localhost:$testPort", true, testPort)
                val moved = withTimeout(5000) { graph.serverWebAddress.url.first { it.endsWith(":$testPort") } }
                assertEquals(graph.api.version(), web("$moved/echo").trim())
                EngineSettings.verify(changed, graph.api.settings(), setOf("PreloadCache"))
            } finally {
                graph.serverActions.connect(saved.torrServerUrl, saved.useLocalServer, saved.port)
                graph.api.setSettings(engine)
                graph.serverActions.lan(saved.lanAccess)
            }
        }
    }

    @Test fun lanSurvivesHomeWithBootAutostartDisabled() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.ready.await()
            val saved = graph.settings.state.value
            try {
                graph.serverActions.lan(true)
                graph.serverActions.boot(false)
                withTimeout(5000) { while (!graph.server.backgroundOwned) delay(50) }
                val url = address(); val runningPid = pid()
                assertFalse(graph.server.playbackOwned)
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                withTimeout(5000) { while (graph.server.foreground) delay(50) }
                delay(17_000)
                assertFalse(graph.settings.state.value.startOnBoot)
                assertTrue(graph.server.backgroundOwned)
                assertEquals(runningPid, pid())
                assertEquals(graph.api.version(), web("$url/echo").trim())
            } finally {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                withTimeout(5000) { while (!graph.server.foreground) delay(50) }
                graph.serverActions.boot(saved.startOnBoot)
                graph.serverActions.lan(saved.lanAccess)
            }
        }
    }

    @Test fun lanRestartIsRejectedWhilePlaybackOwnsServer() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            graph.ready.await(); graph.server.requireRunning()
            val saved = graph.settings.state.value; val runningPid = pid()
            graph.server.playbackOwned = true
            try {
                try { graph.serverActions.lan(!saved.lanAccess); fail("Restart during playback accepted") }
                catch (e: ApplicationActionFailure) { assertEquals(R.string.server_stop_playback, e.resource) }
                assertEquals(saved.lanAccess, graph.settings.state.value.lanAccess)
                assertEquals(runningPid, pid())
                assertTrue(graph.api.version().startsWith("MatriX"))
            } finally { graph.server.playbackOwned = false }
        }
    }
}
