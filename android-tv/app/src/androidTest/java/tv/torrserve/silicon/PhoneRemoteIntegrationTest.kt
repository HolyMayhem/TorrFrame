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
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrFrameApplication
    private class Memory : SessionPersistence {
        var data = emptyList<RemoteSession>()
        override suspend fun read() = data
        override suspend fun write(sessions: List<RemoteSession>) { data = sessions }
    }
    @Test fun browserWithoutCameraPairsOverActualLanWithNumericCode() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val graph = app.graph; graph.ready.await(); graph.phoneRemote.initialize()
            assumeFalse(graph.phonePreferences.state.value.enabled)
            try {
                graph.phoneRemote.enabled(true)
                val state = withTimeout(15_000) { graph.phoneRemote.state.first { s -> s.status == PhoneRemoteStatus.Running || s.status == PhoneRemoteStatus.Error } }
                assertEquals(state.error, PhoneRemoteStatus.Running, state.status)
                val challenge = graph.phoneRemote.pairingLink()
                val body = JSONObject().put("code", challenge.code).put("device", "Camera-free integration").toString()
                val media = okhttp3.MediaType.Companion.run { "application/json".toMediaType() }
                val packet = okhttp3.RequestBody.Companion.run { body.toRequestBody(media) }
                val request = okhttp3.Request.Builder().url("${state.url}/pair").header("Origin", state.url)
                    .header("X-Phone-Remote", "1").post(packet).build()
                var cookie = ""
                graph.http.client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    cookie = response.header("Set-Cookie")!!.substringBefore(';')
                }
                graph.http.client.newCall(okhttp3.Request.Builder().url("${state.url}/api/state").header("Cookie", cookie).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertTrue(JSONObject(response.body.string()).has("settings"))
                }
                graph.http.client.newCall(request).execute().use { assertEquals(401, it.code) }
            } finally { graph.phoneRemote.enabled(false) }
        }
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
    @Test fun jackettSwitchIsSharedBetweenPhoneAndTvAndDisabledSearchIsRejected() = runBlocking {
        val graph=app.graph;graph.ready.await()
        val original=graph.settings.state.value
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val sessions=SessionManager(Memory());val session=sessions.create("Jackett settings test").session
        val backend=StateSyncManager(graph,scope,sessions);val commands=RemoteCommands(graph)
        try {
            // The phone changes the same persisted settings observed by TV.
            commands.execute(session,"settings.update",JSONObject().put("revision",graph.settings.revision.value)
                .put("edits",JSONObject().put("jackettEnabled",false)))
            assertFalse(graph.settings.state.value.jackettEnabled)
            assertFalse(backend.snapshot(session).getJSONObject("settings").getBoolean("jackettEnabled"))
            for(command in listOf("search","settings.testJackett")) try {
                commands.execute(session,command,JSONObject().put("query","Film").put("source","jackett").put("filter","Movies"))
                fail("Disabled Jackett accepted: $command")
            } catch(_:IllegalArgumentException) { }
            // A TV-side change is immediately reflected in the phone snapshot.
            graph.settings.update { it.copy(jackettEnabled=true) }
            val json=backend.snapshot(session).getJSONObject("settings")
            assertTrue(json.getBoolean("jackettEnabled"));assertFalse(json.has("jackettKey"))
        } finally { backend.close();scope.cancel();graph.settings.save(original) }
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
    @Test fun removedMetadataTicketsCannotMatchReaddedTorrentsAndDoNotAccumulate() = runBlocking {
        val name = "phone-remote-ticket-test.sqlite"
        val store = LibraryStore(app, name)
        val hash = "f".repeat(40)
        try {
            assertNull(store.metadataTicket(hash))
            store.clearMetadata(hash)
            store.put(Torrent(hash, "Original", emptyList()))
            val old = requireNotNull(store.metadataTicket(hash, automatic = true))
            store.remove(hash)
            store.put(Torrent(hash, "Readded", emptyList()))
            val fresh = requireNotNull(store.metadataTicket(hash, automatic = true))
            assertNotEquals(old, fresh)
            assertFalse(store.metadata(hash, Metadata("TMDB", "1", "Stale", "Stale", 2020, MediaKind.Movie), old))
            assertTrue(store.metadata(hash, Metadata("TMDB", "2", "Fresh", "Fresh", 2024, MediaKind.Movie), fresh))
            store.remove(hash)
            val field = LibraryStore::class.java.getDeclaredField("metadataEpochs").apply { isAccessible = true }
            assertTrue((field.get(store) as Map<*, *>).isEmpty())
        } finally { store.close(); app.deleteDatabase(name) }
    }
    @Test fun phoneUsesSharedTorrentSearchAndExposesCurrentPreferences() = runBlocking {
        val graph = app.graph; graph.ready.await()
        val original = graph.settings.state.value
        val session = SessionManager(Memory()).create("parity test").session
        val commands = RemoteCommands(graph)
        try {
            graph.settings.update { it.copy(torrentSearch = it.torrentSearch.copy(enabledProviders = emptySet())) }
            // With no sources selected the shared engine completes without touching the network or requiring Jackett.
            val search = commands.execute(session, "search", JSONObject().put("query", "Test").put("filter", "Movies"))
            assertEquals(0, search.getJSONArray("items").length())
            assertTrue(search.has("sources"))
            val edits = JSONObject().put("uiAccent", "white").put("playerMode", "Ask")
                .put("builtInPlayer", JSONObject().put("speed", 1.25))
            commands.execute(session, "settings.update", JSONObject().put("revision", graph.settings.revision.value).put("edits", edits))
            assertEquals("white", graph.settings.state.value.uiAccent)
            assertEquals(1.25f, graph.settings.state.value.builtInPlayer.speed)
            val json = tv.torrserve.silicon.phoneRemote.api.RemoteJson.settings(graph.settings.state.value, graph.settings.revision.value)
            assertTrue(json.has("torrentSearch")); assertTrue(json.has("lanAccess")); assertTrue(json.has("builtInPlayer"))
            for (magnet in listOf("http://example.com/file.torrent", "magnet:?xt=urn:btih:invalid")) {
                try { commands.execute(session, "library.magnet", JSONObject().put("magnet", magnet)); fail("Invalid magnet accepted") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { graph.settings.save(original) }
    }
}
