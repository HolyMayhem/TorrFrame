package tv.torrframe.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import tv.torrframe.core.models.*

interface MetadataCache {
    suspend fun cached(key: String): LibraryStore.Cached
    suspend fun cache(key: String, value: Metadata?)
    suspend fun removeCache(key: String)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryStore(context: Context, databaseName: String = "library.sqlite") : SQLiteOpenHelper(context, databaseName, null, 2), MetadataCache {
    private val metadataLock = Mutex()
    private val manualMetadata = mutableSetOf<String>()
    private val metadataEpochs = mutableMapOf<String, Long>()
    private var metadataSequence = 0L
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val library = revision.mapLatest { list() }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE library(hash TEXT PRIMARY KEY, source_title TEXT NOT NULL, added_at INTEGER NOT NULL, metadata TEXT, files TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE TABLE playback(hash TEXT NOT NULL REFERENCES library(hash) ON DELETE CASCADE, file_id INTEGER NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(hash,file_id))")
        db.execSQL("CREATE TABLE library_origin(hash TEXT PRIMARY KEY REFERENCES library(hash) ON DELETE CASCADE, source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata_cache(cache_key TEXT PRIMARY KEY, payload TEXT, expires_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE library_origin(hash TEXT PRIMARY KEY REFERENCES library(hash) ON DELETE CASCADE, source TEXT NOT NULL)")
    }
    private fun changed() { revision.update { it+1 } }
    suspend fun item(hash: String): LibraryItem? = withContext(Dispatchers.IO) { readItems(hash).singleOrNull() }
    suspend fun list(): List<LibraryItem> = withContext(Dispatchers.IO) { readItems() }
    private fun readItems(hash: String? = null): List<LibraryItem> {
        val args = hash?.let { arrayOf(it) }
        val origins = readableDatabase.rawQuery("SELECT hash, source FROM library_origin" + if (hash != null) " WHERE hash=?" else "", args).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        val progress = readableDatabase.rawQuery("SELECT hash,file_id,position,duration,updated_at FROM playback" + if (hash != null) " WHERE hash=?" else "", args).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0) to c.getInt(1), Triple(c.getLong(2), c.getLong(3), c.getLong(4))) }
        }
        return readableDatabase.rawQuery("SELECT * FROM library" + (if (hash != null) " WHERE hash=?" else "") + " ORDER BY added_at DESC", args).use { c ->
            buildList { while (c.moveToNext()) {
                val identity = c.getString(0)
                val files = JSONArray(c.getString(4)).files().map { file ->
                    val saved = progress[identity to file.id]
                    file.copy(hash = identity, source = origins[identity] ?: "Manual",
                        position = saved?.first ?: 0, duration = saved?.second ?: 0, updatedAt = saved?.third ?: 0)
                }
                val recent = progress.filterKeys { it.first == identity }.maxByOrNull { it.value.third }
                add(LibraryItem(identity, c.getString(1), c.getLong(2),
                    c.getString(3)?.let { Metadata.from(JSONObject(it)) }, files,
                    recent?.value?.first ?: 0, recent?.value?.second ?: 0, recent?.key?.second ?: 0))
            } }
        }
    }
    suspend fun origin(hash: String, source: String) = withContext(Dispatchers.IO) {
        require(source in setOf("Manual", "Jackett", "TorrentSearch"))
        writableDatabase.insertWithOnConflict("library_origin", null, ContentValues().apply {
            put("hash", hash); put("source", source)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }
    suspend fun put(t: Torrent, sourceTitle: String = t.title) = withContext(Dispatchers.IO) {
        val db = writableDatabase; db.beginTransaction()
        try {
            put(db, t, sourceTitle)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }; changed()
    }
    /** A server snapshot is one atomic update and one invalidation of the library projection. */
    suspend fun putAll(torrents: List<Torrent>) = withContext(Dispatchers.IO) {
        if (torrents.isEmpty()) return@withContext
        val db = writableDatabase; db.beginTransaction()
        try {
            for (torrent in torrents) {
                currentCoroutineContext().ensureActive()
                put(db, torrent, torrent.title)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }
    private fun put(db: SQLiteDatabase, t: Torrent, sourceTitle: String) {
        db.insertWithOnConflict("library", null, ContentValues().apply {
            put("hash", t.hash); put("source_title", sourceTitle); put("added_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (t.files.isNotEmpty()) db.update("library", ContentValues().apply {
            put("files", JSONArray(t.files.map { it.json() }).toString())
        }, "hash=?", arrayOf(t.hash))
        if(sourceTitle.isNotBlank() && sourceTitle != "Torrent") db.update("library", ContentValues().apply {
            put("source_title",sourceTitle)
        }, "hash=? AND (source_title='' OR source_title='Torrent')", arrayOf(t.hash))
    }
    private fun exists(hash: String) = readableDatabase.rawQuery("SELECT 1 FROM library WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }
    suspend fun metadataTicket(hash: String, automatic: Boolean = false): Long? = withContext(Dispatchers.IO) { metadataLock.withLock {
        if (!exists(hash)) return@withLock null
        if (automatic && hash in manualMetadata) return@withLock null
        if (!automatic) manualMetadata.add(hash)
        val next = ++metadataSequence; metadataEpochs[hash] = next; next
    } }
    suspend fun metadata(hash: String, value: Metadata, ticket: Long? = null): Boolean = withContext(Dispatchers.IO) { metadataLock.withLock {
        if (!exists(hash)) { metadataEpochs.remove(hash); manualMetadata.remove(hash); return@withLock false }
        if (ticket != null && metadataEpochs[hash] != ticket) return@withLock false
        if (ticket == null) { manualMetadata.add(hash); metadataEpochs[hash] = ++metadataSequence }
        val count = writableDatabase.update("library", ContentValues().apply { put("metadata", value.json().toString()) }, "hash=?", arrayOf(hash))
        changed(); count > 0
    } }
    suspend fun clearMetadata(hash: String) = withContext(Dispatchers.IO) { metadataLock.withLock {
        if (!exists(hash)) return@withLock
        manualMetadata.add(hash); metadataEpochs[hash] = ++metadataSequence
        writableDatabase.update("library", ContentValues().apply { putNull("metadata") }, "hash=?", arrayOf(hash)); changed()
    } }
    suspend fun rename(hash: String, title: String) = withContext(Dispatchers.IO) { metadataLock.withLock {
        require(title.isNotBlank() && title.length <= 250)
        require(exists(hash))
        manualMetadata.add(hash); metadataEpochs[hash] = ++metadataSequence
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
        metadataEpochs.remove(hash); manualMetadata.remove(hash)
        writableDatabase.delete("library", "hash=?", arrayOf(hash)); changed()
    } }
    suspend fun checkpoint(hash: String, file: Int, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        // A title may be removed while playback is stopping. Check its existence
        // in the same SQL statement, so a late checkpoint cannot create an orphan.
        writableDatabase.execSQL("INSERT OR REPLACE INTO playback(hash,file_id,position,duration,updated_at) " +
            "SELECT ?,?,?,?,? WHERE EXISTS (SELECT 1 FROM library WHERE hash=?)",
            arrayOf<Any>(hash, file, position.coerceAtLeast(0), duration.coerceAtLeast(0), System.currentTimeMillis(), hash))
        changed()
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
