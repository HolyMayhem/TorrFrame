package tv.torrserve.silicon

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.phoneRemote.api.RemoteOperations
import tv.torrserve.silicon.phoneRemote.security.SessionManager
import tv.torrserve.silicon.features.player.preferredVideo
import tv.torrserve.silicon.core.models.*

class PhoneRemoteOperationTests {
    @Test fun requestRetriesAreIdempotentAndResultsBelongToTheirSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessions = SessionManager(MemorySessions())
        val first = sessions.create("first").session; val second = sessions.create("second").session
        var calls = 0
        val operations = RemoteOperations(scope, { _, _, _ -> calls++; JSONObject().put("value", 1) }, { "failed" })
        val request = JSONObject().put("requestId", "a".repeat(32)).put("type", "search")
        try {
            val id = operations.submit(first, request).getString("operationId")
            assertEquals(id, operations.submit(first, request).getString("operationId"))
            withTimeout(3000) { while (operations.find(first, id)?.getString("status") != "completed") delay(10) }
            assertEquals(1, calls); assertNull(operations.find(second, id))
        } finally { scope.cancel() }
    }
    @Test fun revokingSessionCancelsItsPendingWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = SessionManager(MemorySessions()).create("client").session
        val started = CompletableDeferred<Unit>(); var wrote = false
        val operations = RemoteOperations(scope, { _, _, _ -> started.complete(Unit); delay(10_000); wrote = true; JSONObject() }, { "failed" })
        try {
            val id = operations.submit(session, JSONObject().put("requestId", "b".repeat(32)).put("type", "search")).getString("operationId")
            withTimeout(3000) { started.await() }; operations.revokeExcept(emptySet())
            withTimeout(3000) { while (operations.find(session, id)?.getString("status") != "cancelled") delay(10) }
            assertFalse(wrote)
        } finally { scope.cancel() }
    }
    @Test fun tvAndPhoneShareFileSelectionRules() {
        val files = listOf(TorrentFile(1,"one.mkv",1), TorrentFile(2,"two.mkv",1))
        assertNull(preferredVideo(null, files))
        assertEquals(1, preferredVideo(null, files.take(1))?.id)
        val item = LibraryItem("a".repeat(40), "Film", 1, files = files, position = 60_000, duration = 120_000, lastFileId = 2)
        assertEquals(2, preferredVideo(item, files)?.id)
        assertNull(preferredVideo(item.copy(position = 120_000), files))
    }
}
