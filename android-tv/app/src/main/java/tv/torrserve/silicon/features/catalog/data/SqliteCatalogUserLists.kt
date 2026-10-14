package tv.torrserve.silicon.features.catalog.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.torrserve.silicon.features.catalog.domain.*

/** User choices never expire and are independent of the disposable TMDB cache. */
class SqliteCatalogUserLists(context: Context, name: String = "catalog-user-lists.sqlite") :
    SQLiteOpenHelper(context, name, null, 1), CatalogUserLists {
    private val mutable = MutableStateFlow<List<CatalogListEntry>>(emptyList())
    override val entries: StateFlow<List<CatalogListEntry>> = mutable
    private val lock = Mutex()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE lists(ref TEXT PRIMARY KEY,id INTEGER NOT NULL,type TEXT NOT NULL,favorite INTEGER NOT NULL,watch_later INTEGER NOT NULL,added_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE items(ref TEXT NOT NULL,locale TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(ref,locale))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { mutable.value = read(writableDatabase) } }
    override suspend fun toggle(item: CatalogItem, language: String, list: CatalogUserList) = lock.withLock {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val old = read(db).firstOrNull { it.ref == item.ref }
                val favorite = if (list == CatalogUserList.Favorites) !(old?.favorite ?: false) else old?.favorite ?: false
                val later = if (list == CatalogUserList.WatchLater) !(old?.watchLater ?: false) else old?.watchLater ?: false
                if (!favorite && !later) {
                    db.delete("items", "ref=?", arrayOf(item.ref.key)); db.delete("lists", "ref=?", arrayOf(item.ref.key))
                } else {
                    val values = ContentValues().apply { put("favorite", if (favorite) 1 else 0); put("watch_later", if (later) 1 else 0) }
                    if (old == null) {
                        values.put("ref", item.ref.key); values.put("id", item.ref.id); values.put("type", item.ref.type.name); values.put("added_at", System.currentTimeMillis())
                        db.insertOrThrow("lists", null, values)
                    } else db.update("lists", values, "ref=?", arrayOf(item.ref.key))
                    snapshot(db, item, language)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            mutable.value = read(db)
        }
    }
    override suspend fun localize(item: CatalogItem, language: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            if (db.rawQuery("SELECT 1 FROM lists WHERE ref=?", arrayOf(item.ref.key)).use { it.moveToFirst() }) {
                snapshot(db, item, language); mutable.value = read(db)
            }
        }
    }
    private fun snapshot(db: SQLiteDatabase, item: CatalogItem, language: String) {
        val json = JSONObject().put("title", item.title).put("original", item.originalTitle).put("poster", item.poster)
            .put("backdrop", item.backdrop).put("date", item.date).put("rating", item.rating).put("votes", item.votes)
        db.insertWithOnConflict("items", null, ContentValues().apply {
            put("ref", item.ref.key); put("locale", language); put("payload", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun read(db: SQLiteDatabase): List<CatalogListEntry> {
        val result = linkedMapOf<String, CatalogListEntry>()
        db.rawQuery("SELECT l.ref,l.id,l.type,l.favorite,l.watch_later,i.locale,i.payload FROM lists l JOIN items i ON i.ref=l.ref ORDER BY l.added_at DESC,l.rowid DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val ref = CatalogRef(cursor.getInt(1), CatalogType.valueOf(cursor.getString(2)))
                val j = JSONObject(cursor.getString(6))
                val item = CatalogItem(ref,j.getString("title"),j.optString("original"),j.optString("poster"),j.optString("backdrop"),j.optString("date"),j.optDouble("rating"),j.optInt("votes"))
                val old = result[key] ?: CatalogListEntry(ref,cursor.getInt(3)!=0,cursor.getInt(4)!=0,emptyMap())
                result[key] = old.copy(localized = old.localized + (cursor.getString(5) to item))
            }
        }
        return result.values.toList()
    }
}
