package tv.torrserve.silicon.features.catalog.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CatalogCache {
    data class Entry(val payload: String, val fetchedAt: Long, val expiresAt: Long)
    suspend fun read(key: String): Entry?
    suspend fun write(key: String, payload: String, ttl: Long, retention: Long)
    suspend fun remove(key: String)
}
/** Disposable data only; user torrents, settings and playback remain in their existing stores. */
class SqliteCatalogCache(context: Context, name: String = "catalog.sqlite", private val now: () -> Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context, name, null, 1), CatalogCache {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE catalog_cache(cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, delete_at INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { db.execSQL("DROP TABLE IF EXISTS catalog_cache"); onCreate(db) }
    override suspend fun read(key: String): CatalogCache.Entry? = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("catalog_cache", "delete_at<=?", arrayOf(now().toString()))
        db.rawQuery("SELECT payload,fetched_at,expires_at FROM catalog_cache WHERE cache_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) CatalogCache.Entry(it.getString(0), it.getLong(1), it.getLong(2)) else null
        }
    }
    override suspend fun write(key: String, payload: String, ttl: Long, retention: Long): Unit = withContext(Dispatchers.IO) {
        if (payload.toByteArray().size > 1024 * 1024) return@withContext
        val db = writableDatabase; val time = now(); db.beginTransaction()
        try {
            db.delete("catalog_cache", "delete_at<=?", arrayOf(time.toString()))
            db.insertWithOnConflict("catalog_cache", null, ContentValues().apply {
                put("cache_key", key); put("payload", payload); put("fetched_at", time); put("expires_at", time + ttl); put("delete_at", time + retention)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM catalog_cache WHERE cache_key IN (SELECT cache_key FROM catalog_cache ORDER BY fetched_at DESC LIMIT -1 OFFSET 256)")
            while (db.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM catalog_cache", null).use { it.moveToFirst(); it.getLong(0) } > 16L * 1024 * 1024) {
                db.execSQL("DELETE FROM catalog_cache WHERE cache_key=(SELECT cache_key FROM catalog_cache ORDER BY fetched_at LIMIT 1)")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) { writableDatabase.delete("catalog_cache", "cache_key=?", arrayOf(key)); Unit }
}
