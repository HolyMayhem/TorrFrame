package tv.torrserve.silicon.features.catalog.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CatalogUserList { Favorites, WatchLater }
data class CatalogListEntry(val ref: CatalogRef, val favorite: Boolean, val watchLater: Boolean,
    val localized: Map<String, CatalogItem>) {
    fun contains(list: CatalogUserList) = if (list == CatalogUserList.Favorites) favorite else watchLater
    fun item(language: String): CatalogItem {
        val fallback = localized.values.first()
        return localized[language] ?: fallback.copy(title = fallback.originalTitle.ifBlank { fallback.title })
    }
}
interface CatalogUserLists {
    val entries: StateFlow<List<CatalogListEntry>>
    suspend fun load()
    suspend fun toggle(item: CatalogItem, language: String, list: CatalogUserList)
    suspend fun localize(item: CatalogItem, language: String)
}

/** Default for isolated previews/tests; production injects the persistent store. */
class MemoryCatalogUserLists : CatalogUserLists {
    private val mutable = MutableStateFlow<List<CatalogListEntry>>(emptyList())
    override val entries: StateFlow<List<CatalogListEntry>> = mutable
    private val lock = Mutex()
    override suspend fun load() = Unit
    override suspend fun toggle(item: CatalogItem, language: String, list: CatalogUserList) = lock.withLock {
        val old = mutable.value.firstOrNull { it.ref == item.ref } ?: CatalogListEntry(item.ref, false, false, emptyMap())
        val next = old.copy(favorite = if (list == CatalogUserList.Favorites) !old.favorite else old.favorite,
            watchLater = if (list == CatalogUserList.WatchLater) !old.watchLater else old.watchLater,
            localized = old.localized + (language to item))
        mutable.value = if (old.ref in mutable.value.map { it.ref }) mutable.value.mapNotNull { if (it.ref == item.ref) next.takeIf { it.favorite || it.watchLater } else it }
        else listOf(next) + mutable.value
    }
    override suspend fun localize(item: CatalogItem, language: String) = lock.withLock {
        mutable.value = mutable.value.map { if (it.ref == item.ref) it.copy(localized = it.localized + (language to item)) else it }
    }
}
