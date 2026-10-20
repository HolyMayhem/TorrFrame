package tv.torrframe.features.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.torrframe.core.database.LibraryStore
import tv.torrframe.core.models.MediaKind
import tv.torrframe.core.storage.SettingsStore

/** Explicit user-selected matches reuse the same providers and shared library as automatic lookup. */
class MetadataEditor(private val store: LibraryStore, private val providers: MetadataSource, private val settings: SettingsStore) {
    suspend fun search(query: String, provider: String, kind: MediaKind): List<MetadataMatch> = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 250 && provider in listOf("OMDb", "TMDB", "Kinopoisk", "AniList"))
        val parsed = MediaNameParser.parse(query)
        providers.search(parsed.copy(kind = kind), settings.state.value, provider).take(20)
    }
    suspend fun select(hash: String, match: MetadataMatch) {
        check(store.item(hash) != null)
        val ticket = checkNotNull(store.metadataTicket(hash))
        val details = withContext(Dispatchers.IO) { providers.details(match, settings.state.value) }
        check(store.metadata(hash, details, ticket)) { "Metadata changed while loading" }
    }
    suspend fun rename(hash: String, title: String) = store.rename(hash, title)
    suspend fun reset(hash: String) = store.clearMetadata(hash)
}
