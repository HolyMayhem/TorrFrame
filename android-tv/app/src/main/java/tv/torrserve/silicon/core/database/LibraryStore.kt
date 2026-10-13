package tv.torrserve.silicon.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import tv.torrserve.silicon.core.models.*

interface MetadataCache {
    suspend fun cached(key: String): LibraryStore.Cached
    suspend fun cache(key: String, value: Metadata?)
    suspend fun removeCache(key: String)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryStore(context: Context, databaseName: String = "library.sqlite") : SQLiteOpenHelper(context, databaseName, null, 1), MetadataCache {
    private val metadataLock = Mutex()
    private val manualMetadata = mutableSetOf<String>()
    private val metadataEpochs = mutableMapOf<String, Long>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val library = revision.mapLatest { list() }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE library(hash TEXT PRIMARY KEY, source_title TEXT NOT NULL, added_at INTEGER NOT NULL, metadata TEXT, files TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE TABLE playback(hash TEXT NOT NULL REFERENCES library(hash) ON DELETE CASCADE, file_id INTEGER NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(hash,file_id))")
        db.execSQL("CREATE TABLE metadata_cache(cache_key TEXT PRIMARY KEY, payload TEXT, expires_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Database migration from $oldVersion to $newVersion is not defined.")
    }
    private fun changed() { revision.update { it+1 } }
    suspend fun item(hash: String): LibraryItem? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT l.*, p.position, p.duration, p.file_id FROM library l LEFT JOIN playback p ON p.hash=l.hash AND p.file_id=(SELECT file_id FROM playback WHERE hash=l.hash ORDER BY updated_at DESC LIMIT 1) WHERE l.hash=?", arrayOf(hash)).use { c ->
            if(!c.moveToFirst()) null else LibraryItem(c.getString(0),c.getString(1),c.getLong(2),
                c.getString(3)?.let { Metadata.from(JSONObject(it)) },JSONArray(c.getString(4)).files(),c.getLong(5),c.getLong(6),c.getInt(7))
        }
    }
    suspend fun list(): List<LibraryItem> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT l.*, p.position, p.duration, p.file_id FROM library l LEFT JOIN playback p ON p.hash=l.hash AND p.file_id=(SELECT file_id FROM playback WHERE hash=l.hash ORDER BY updated_at DESC LIMIT 1) ORDER BY added_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(LibraryItem(c.getString(0), c.getString(1), c.getLong(2),
                c.getString(3)?.let { Metadata.from(JSONObject(it)) }, JSONArray(c.getString(4)).files(),
                c.getLong(5), c.getLong(6), c.getInt(7))) }
        }
    }
    suspend fun put(t: Torrent, sourceTitle: String = t.title) = withContext(Dispatchers.IO) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.insertWithOnConflict("library", null, ContentValues().apply {
                put("hash", t.hash); put("source_title", sourceTitle); put("added_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (t.files.isNotEmpty()) db.update("library", ContentValues().apply {
                put("files", JSONArray(t.files.map { it.json() }).toString())
            }, "hash=?", arrayOf(t.hash))
            if(sourceTitle.isNotBlank() && sourceTitle != "Torrent") db.update("library", ContentValues().apply {
                put("source_title",sourceTitle)
            }, "hash=? AND (source_title='' OR source_title='Torrent')", arrayOf(t.hash))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }; changed()
    }
    suspend fun metadataTicket(hash: String, automatic: Boolean = false): Long? = metadataLock.withLock {
        if (automatic && hash in manualMetadata) return@withLock null
        if (!automatic) manualMetadata.add(hash)
        val next = (metadataEpochs[hash] ?: 0) + 1; metadataEpochs[hash] = next; next
    }
    suspend fun metadata(hash: String, value: Metadata, ticket: Long? = null): Boolean = withContext(Dispatchers.IO) { metadataLock.withLock {
        if (ticket != null && metadataEpochs[hash] != ticket) return@withLock false
        if (ticket == null) { manualMetadata.add(hash); metadataEpochs[hash] = (metadataEpochs[hash] ?: 0) + 1 }
        val count = writableDatabase.update("library", ContentValues().apply { put("metadata", value.json().toString()) }, "hash=?", arrayOf(hash))
        changed(); count > 0
    } }
    suspend fun clearMetadata(hash: String) = withContext(Dispatchers.IO) { metadataLock.withLock {
        manualMetadata.add(hash); metadataEpochs[hash] = (metadataEpochs[hash] ?: 0) + 1
        writableDatabase.update("library", ContentValues().apply { putNull("metadata") }, "hash=?", arrayOf(hash)); changed()
    } }
    suspend fun rename(hash: String, title: String) = withContext(Dispatchers.IO) { metadataLock.withLock {
        require(title.isNotBlank() && title.length <= 250)
        manualMetadata.add(hash); metadataEpochs[hash] = (metadataEpochs[hash] ?: 0) + 1
        val db = writableDatabase; db.beginTransaction()
        try {
            val metadata = db.rawQuery("SELECT metadata FROM library WHERE hash=?", arrayOf(hash)).use { c ->
                check(c.moveToFirst()); c.getString(0)?.let { Metadata.from(JSONObject(it)).copy(title = title.trim()) }
            }
            db.update("library", ContentValues().apply { put("source_title", title.trim()); metadata?.let { put("metadata", it.json().toString()) } }, "hash=?", arrayOf(hash))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    } }
    suspend fun remove(hash: String) = withContext(Dispatchers.IO) { metadataLock.withLock {
        metadataEpochs[hash] = (metadataEpochs[hash] ?: 0) + 1; manualMetadata.remove(hash)
        writableDatabase.delete("library", "hash=?", arrayOf(hash)); changed()
    } }
    suspend fun checkpoint(hash: String, file: Int, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("playback", null, ContentValues().apply {
            put("hash", hash); put("file_id", file); put("position", position.coerceAtLeast(0)); put("duration", duration.coerceAtLeast(0))
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE); changed()
    }
    suspend fun progress(hash: String, file: Int): Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT position FROM playback WHERE hash=? AND file_id=?", arrayOf(hash, file.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else 0
        }
    }
    data class Cached(val present: Boolean, val value: Metadata?)
    override suspend fun cached(key: String): Cached = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT payload FROM metadata_cache WHERE cache_key=? AND expires_at>?", arrayOf(key, System.currentTimeMillis().toString())).use {
            if (it.moveToFirst()) Cached(true, it.getString(0)?.let { s -> Metadata.from(JSONObject(s)) }) else Cached(false, null)
        }
    }
    override suspend fun cache(key: String, value: Metadata?) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("metadata_cache", "expires_at<?", arrayOf(System.currentTimeMillis().toString()))
        db.insertWithOnConflict("metadata_cache", null, ContentValues().apply {
            put("cache_key", key); put("payload", value?.json()?.toString())
            put("expires_at", System.currentTimeMillis() + (if (value == null) 1 else 30) * 86_400_000L)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM metadata_cache WHERE cache_key IN (SELECT cache_key FROM metadata_cache ORDER BY expires_at DESC LIMIT -1 OFFSET 1000)")
    }
    suspend fun clearCache() = withContext(Dispatchers.IO) { writableDatabase.delete("metadata_cache", null, null); Unit }
    override suspend fun removeCache(key: String) = withContext(Dispatchers.IO) { writableDatabase.delete("metadata_cache", "cache_key=?", arrayOf(key)); Unit }
}
