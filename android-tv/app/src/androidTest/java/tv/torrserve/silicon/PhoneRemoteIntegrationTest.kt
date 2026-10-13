package tv.torrserve.silicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.storage.SettingsConflict
import tv.torrserve.silicon.phoneRemote.api.RemoteCommands
import tv.torrserve.silicon.phoneRemote.discovery.LocalAddressResolver
import tv.torrserve.silicon.phoneRemote.security.*
import tv.torrserve.silicon.phoneRemote.server.PhoneRemoteStatus
import tv.torrserve.silicon.phoneRemote.synchronization.StateSyncManager
import java.net.ServerSocket

/** Run on a clean ARM Android TV installation. Skips when an existing user enabled Phone Remote. */
@RunWith(AndroidJUnit4::class)
class PhoneRemoteIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrServeApplication
    private class Memory : SessionPersistence {
        var data = emptyList<RemoteSession>()
        override suspend fun read() = data
        override suspend fun write(sessions: List<RemoteSession>) { data = sessions }
    }
    @Test fun applicationAndTorrServerWorkWithPhoneRemoteDisabled(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val graph = app.graph; graph.ready.await(); graph.phoneRemote.initialize()
            assumeFalse(graph.phonePreferences.state.value.enabled)
            graph.server.requireRunning()
            assertTrue(graph.api.version().startsWith("MatriX"))
            graph.store.list(); graph.settings.snapshot()
            assertEquals(PhoneRemoteStatus.Disabled, graph.phoneRemote.state.value.status)
            LocalAddressResolver(app).current()?.let { address ->
                ServerSocket().use { socket -> socket.bind(java.net.InetSocketAddress(address, graph.phonePreferences.state.value.port)) }
            }
        }
    }
    @Test fun sharedSettingsRejectStalePhoneWritesAndAppearInSnapshots() = runBlocking {
        val graph = app.graph; graph.ready.await()
        val original = graph.settings.state.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessions = SessionManager(Memory()); val session = sessions.create("test").session
        val backend = StateSyncManager(graph, scope, sessions)
        try {
            val revision = graph.settings.revision.value
            val args = JSONObject().put("revision", revision).put("edits", JSONObject().put("resume", !original.resume))
            RemoteCommands(graph).execute(session, "settings.update", args)
            assertEquals(!original.resume, backend.snapshot(session).getJSONObject("settings").getBoolean("resume"))
            try { RemoteCommands(graph).execute(session, "settings.update", args); fail("Stale revision accepted") } catch (_: SettingsConflict) {}
            graph.settings.update { it.copy(resume = original.resume) }
            assertEquals(original.resume, backend.snapshot(session).getJSONObject("settings").getBoolean("resume"))
        } finally { backend.close(); scope.cancel(); graph.settings.save(original) }
    }
    @Test fun metadataSelectionIsOneSourceAndStaleAutomaticWritesCannotWin() = runBlocking {
        val name = "phone-remote-metadata-test.sqlite"
        val store = LibraryStore(app, name)
        val hash = "d".repeat(40)
        try {
            store.put(Torrent(hash,"Original",emptyList()))
            val automatic = checkNotNull(store.metadataTicket(hash, automatic = true))
            val manual = checkNotNull(store.metadataTicket(hash))
            assertTrue(store.metadata(hash, Metadata("TMDB","1","Chosen","Chosen",2024,MediaKind.Movie), manual))
            assertFalse(store.metadata(hash, Metadata("TMDB","2","Stale","Stale",2020,MediaKind.Movie), automatic))
            assertEquals("Chosen", store.item(hash)?.title)
            assertNull(store.metadataTicket(hash, automatic = true))
            store.clearMetadata(hash); assertNull(store.item(hash)?.metadata)
            assertFalse(store.metadata(hash, Metadata("TMDB","1","Old","Old",2020,MediaKind.Movie), manual))
        } finally { store.close(); app.deleteDatabase(name) }
    }
    @Test fun enablingAndDisablingReleasesLanPortWithoutStoppingTorrServer() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val graph = app.graph; graph.ready.await(); graph.phoneRemote.initialize()
            assumeFalse(graph.phonePreferences.state.value.enabled)
            val address = LocalAddressResolver(app).current(); assumeNotNull(address)
            graph.server.requireRunning()
            try {
                graph.phoneRemote.enabled(true)
                val running = withTimeout(15_000) { graph.phoneRemote.state.first { s -> s.status == PhoneRemoteStatus.Running || s.status == PhoneRemoteStatus.Error } }
                assertEquals(running.error, PhoneRemoteStatus.Running, running.status)
                graph.phoneRemote.enabled(false)
                assertEquals(PhoneRemoteStatus.Disabled, graph.phoneRemote.state.value.status)
                ServerSocket().use { socket -> socket.bind(java.net.InetSocketAddress(address, graph.phonePreferences.state.value.port)) }
                assertTrue(graph.api.version().startsWith("MatriX"))
            } finally { graph.phoneRemote.enabled(false) }
        }
    }
}
