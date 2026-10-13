package tv.torrserve.silicon

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.phoneRemote.security.*
import tv.torrserve.silicon.phoneRemote.api.RemoteJson
import tv.torrserve.silicon.core.storage.*

class MemorySessions : SessionPersistence {
    var stored = emptyList<RemoteSession>()
    override suspend fun read() = stored
    override suspend fun write(sessions: List<RemoteSession>) { stored = sessions }
}
class PhoneRemoteSecurityTests {
    @Test fun pairingIsSingleUseAndBoundToOrigin() {
        var time = 1_000L
        val pairing = PairingManager { time }
        val first = pairing.issue("http://192.168.1.50:8765")
        val token = first.url.substringAfter("#pair=")
        assertEquals(64, token.length)
        assertFalse(pairing.consume(token, "http://attacker.test:8765"))
        assertTrue(pairing.consume(token, "http://192.168.1.50:8765"))
        assertFalse(pairing.consume(token, "http://192.168.1.50:8765"))
        val expired = pairing.issue("http://192.168.1.50:8765").url.substringAfter("#pair=")
        time += 120_001
        assertFalse(pairing.consume(expired, "http://192.168.1.50:8765"))
    }
    @Test fun refreshingQrRevokesPreviousPairingToken() {
        val pairing = PairingManager()
        val first = pairing.issue("http://192.168.1.1:8765").url.substringAfter("#pair=")
        val next = pairing.issue("http://192.168.1.1:8765").url.substringAfter("#pair=")
        assertNotEquals(first, next); assertFalse(pairing.consume(first, "http://192.168.1.1:8765"))
        assertTrue(pairing.consume(next, "http://192.168.1.1:8765")); pairing.clear()
    }
    @Test fun sessionsPersistWithoutReusableCookieSecretsAndAreRevocable() = runBlocking {
        val persistence = MemorySessions(); val manager = SessionManager(persistence)
        val grant = manager.create("iPhone")
        assertNotEquals(grant.cookie, persistence.stored.single().secretHash)
        assertFalse(persistence.stored.toString().contains(grant.cookie))
        val reopened = SessionManager(persistence); reopened.load()
        val authenticated = reopened.authenticate(grant.cookie)
        assertNotNull(authenticated); assertTrue(reopened.csrf(authenticated!!, grant.session.csrf))
        assertFalse(reopened.csrf(authenticated, "x".repeat(64)))
        reopened.revoke(grant.session.id); assertNull(reopened.authenticate(grant.cookie))
    }
    @Test fun expiredSessionsAndDisablingInvalidateAccess() = runBlocking {
        var time = 1_000L; val persistence = MemorySessions(); val manager = SessionManager(persistence) { time }
        val first = manager.create("Browser"); time += 7 * 86_400_000L + 1
        assertNull(manager.authenticate(first.cookie))
        val second = manager.create("Browser"); manager.clear()
        assertNull(manager.authenticate(second.cookie)); assertTrue(persistence.stored.isEmpty())
    }
    @Test fun hostAndOriginBlockRebindingAndForeignOrigins() {
        val guard = RequestGuard("192.168.1.50", 8765)
        assertTrue(guard.peer("192.168.1.20")); assertFalse(guard.peer("8.8.8.8")); assertFalse(guard.peer("evil.test"));
        assertTrue(guard.host("192.168.1.50:8765")); assertFalse(guard.host("evil.test:8765"))
        assertFalse(guard.host("192.168.1.50:8090")); assertFalse(guard.origin("null", true))
        assertFalse(guard.origin("https://example.com", true)); assertFalse(guard.origin(null, true))
        assertTrue(guard.origin(null, false)); assertTrue(guard.origin("http://192.168.1.50:8765", true))
    }
    @Test fun pairingAttemptsAreBounded() {
        var time = 1_000L; val limit = PairingRateLimit { time }
        repeat(8) { assertTrue(limit.allow("client")) }; assertFalse(limit.allow("client"))
        time += 60_001; assertTrue(limit.allow("client"))
    }
    @Test fun remoteProjectionDoesNotExposeJackettKey() {
        val json = RemoteJson.settings(Settings(jackettKey = "private-test-key"), 7)
        assertFalse(json.has("jackettKey")); assertFalse(json.toString().contains("private-test-key"))
        assertTrue(json.getBoolean("jackettKeyConfigured")); assertEquals(7, json.getLong("revision"))
    }
    @Test fun tvDraftRebasesUneditedFieldsAndKeepsExplicitUnsavedEdits() {
        val original = Settings(jackettUrl = "http://old", resume = true)
        val tvDraft = original.copy(audioLanguage = "ru")
        val remote = original.copy(jackettUrl = "http://new", resume = false)
        val merged = SettingsMerge.rebase(tvDraft, original, remote)
        assertEquals("http://new", merged.jackettUrl); assertFalse(merged.resume); assertEquals("ru", merged.audioLanguage)
        assertEquals(remote.torrServerUrl, merged.torrServerUrl)
    }
}
