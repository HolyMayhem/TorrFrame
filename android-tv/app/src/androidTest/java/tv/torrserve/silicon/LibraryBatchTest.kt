package tv.torrserve.silicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.models.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryBatchTest {
    @Test fun snapshotPreservesManualDetailsProgressFilesAndAdditionTime() = runBlocking {
        val f=StorageFixture()
        try {
            val hash=StorageFixture.hash(1)
            val original=Torrent(hash,"Original",listOf(TorrentFile(1,"Original.mkv",1000)))
            f.store.put(original)
            val oldTicket=f.store.metadataTicket(hash,automatic=true)
            f.store.metadata(hash,Metadata("TMDB","1","Chosen","Original",2020,MediaKind.Movie))
            f.store.rename(hash,"Manual title");f.store.checkpoint(hash,1,60_000,120_000)
            val before=f.store.item(hash)!!;val revision=f.store.changes.value
            f.store.putAll(listOf(original.copy(title="Server replacement",files=listOf(TorrentFile(1,"New.mkv",2000))),
                Torrent(StorageFixture.hash(2),"New title",emptyList())))
            val after=f.store.item(hash)!!
            assertEquals(before.sourceTitle,after.sourceTitle);assertEquals(before.metadata,after.metadata)
            assertEquals(before.addedAt,after.addedAt);assertEquals(before.position,after.position)
            assertEquals(before.duration,after.duration);assertEquals(before.lastFileId,after.lastFileId)
            assertEquals("New.mkv",after.files.single().path)
            assertFalse(f.store.metadata(hash,Metadata("TMDB","2","Stale automatic result","",2020,MediaKind.Movie),oldTicket))
            assertEquals("Manual title",f.store.item(hash)!!.title)
            assertEquals(revision+1,f.store.changes.value);assertEquals(2,f.store.list().size)
            f.store.putAll(listOf(original.copy(files=emptyList())))
            assertEquals("New.mkv",f.store.item(hash)!!.files.single().path)
            val emptyRevision=f.store.changes.value;f.store.putAll(emptyList())
            assertEquals(emptyRevision,f.store.changes.value)
        } finally { f.close() }
    }
    @Test fun failedSnapshotRollsBackAllRowsAndDoesNotNotifyObservers() = runBlocking {
        val f=StorageFixture()
        try {
            val rejected=StorageFixture.hash(2)
            f.store.writableDatabase.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON library WHEN NEW.hash='$rejected' BEGIN SELECT RAISE(ABORT,'fixture'); END")
            val revision=f.store.changes.value
            try {
                f.store.putAll((1..3).map { Torrent(StorageFixture.hash(it),"Title $it",emptyList()) });fail("Snapshot should fail")
            } catch(_:android.database.sqlite.SQLiteException) { }
            assertTrue(f.store.list().isEmpty());assertEquals(revision,f.store.changes.value)
        } finally { f.close() }
    }
    @Test fun overlappingSyncSharesOneRequestAndLaterRefreshStillFetches() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize();val gate=CompletableDeferred<Unit>();val revision=f.store.changes.value
            coroutineScope {
                val calls=List(4) { async(start=CoroutineStart.UNDISPATCHED) { gate.await();f.repository.sync() } }
                gate.complete(Unit);calls.awaitAll()
            }
            assertEquals(1,f.listCalls.get());assertEquals(1,f.versionCalls.get())
            assertEquals(revision+1,f.store.changes.value);assertEquals(100,f.store.list().size)
            f.repository.sync();assertEquals(2,f.listCalls.get())
        } finally { f.close() }
    }
    @Test fun cancellingOneSubscriberKeepsTheOtherRefreshAlive() = runBlocking {
        val f=StorageFixture();val gate=CountDownLatch(1)
        try {
            f.initialize();f.listGate=gate
            coroutineScope {
                val first=async(start=CoroutineStart.UNDISPATCHED) { f.repository.sync() }
                val second=async(start=CoroutineStart.UNDISPATCHED) { f.repository.sync() }
                assertTrue(withContext(Dispatchers.IO) { f.listStarted.await(5,TimeUnit.SECONDS) })
                first.cancelAndJoin();gate.countDown();second.await()
            }
            assertEquals(1,f.listCalls.get());assertEquals(100,f.store.list().size)
        } finally { gate.countDown();f.close() }
    }
    @Test fun failedSharedRefreshCanBeRetried() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize();f.listStatus=503
            supervisorScope {
                val calls=List(3) { async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.repository.sync() }.isFailure } }
                assertTrue(calls.awaitAll().all { it })
            }
            assertEquals(1,f.listCalls.get());assertTrue(f.store.list().isEmpty())
            f.listStatus=200;f.repository.sync()
            assertEquals(2,f.listCalls.get());assertEquals(100,f.store.list().size)
        } finally { f.close() }
    }
    @Test fun cancellingAllSubscribersCancelsTheOldWriteAndAllowsAFreshRequest() = runBlocking {
        val f=StorageFixture();val gate=CountDownLatch(1)
        try {
            f.initialize();f.listGate=gate;val revision=f.store.changes.value
            coroutineScope {
                val first=async(start=CoroutineStart.UNDISPATCHED) { f.repository.sync() }
                val second=async(start=CoroutineStart.UNDISPATCHED) { f.repository.sync() }
                assertTrue(withContext(Dispatchers.IO) { f.listStarted.await(5,TimeUnit.SECONDS) })
                first.cancelAndJoin();second.cancelAndJoin();gate.countDown()
            }
            withTimeout(5000) { f.repository.sync() }
            assertEquals(2,f.listCalls.get());assertEquals(revision+1,f.store.changes.value)
            assertEquals(100,f.store.list().size)
        } finally { gate.countDown();f.close() }
    }
}
