package tv.torrserve.silicon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.database.MetadataCache
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.silicon.features.metadata.*

class MetadataResolverTests {
    private class Cache : MetadataCache {
        val entries = mutableMapOf<String, Metadata?>()
        override suspend fun cached(key: String) = LibraryStore.Cached(entries.containsKey(key), entries[key])
        override suspend fun cache(key: String, value: Metadata?) { entries[key] = value }
        override suspend fun removeCache(key: String) { entries.remove(key) }
    }
    private class Sources : MetadataSource {
        val calls = mutableListOf<String>()
        var available = setOf("OMDb", "TMDB", "Kinopoisk", "AniList")
        var failures = emptySet<String>()
        var rejected = emptySet<String>()
        var detailsFailures = emptySet<String>()
        var cancel = false
        override suspend fun search(p: ParsedName, s: Settings, provider: String): List<MetadataMatch> {
            calls += provider
            if (cancel) throw CancellationException("cancelled")
            if (provider in failures) error("source unavailable")
            if (provider !in available) return emptyList()
            return listOf(MetadataMatch(Metadata(provider, "42", if(provider in rejected) "Unrelated Title" else p.title,
                if(provider in rejected) "Unrelated Title" else p.title, p.year, p.kind)))
        }
        override suspend fun details(match: MetadataMatch, s: Settings): Metadata {
            if (match.value.provider in detailsFailures) error("details unavailable")
            return match.value
        }
    }
    private val title = listOf("Interstellar.2014.1080p.mkv")

    @Test fun defaultOrderStopsAfterFirstConfidentResult() = runBlocking {
        val source = Sources()
        assertEquals("OMDb", MetadataResolver(source, Cache()).resolve(title, Settings())?.provider)
        assertEquals(listOf("OMDb"), source.calls)
    }
    @Test fun missesAndRejectedMatchesFallThroughInUserOrder() = runBlocking {
        val source = Sources().apply { available = setOf("Kinopoisk"); rejected = setOf("TMDB"); available += "TMDB" }
        val settings = Settings(metadataOrder = listOf("TMDB", "OMDb", "Kinopoisk"))
        assertEquals("Kinopoisk", MetadataResolver(source, Cache()).resolve(title, settings)?.provider)
        assertEquals(listOf("TMDB", "OMDb", "Kinopoisk"), source.calls.distinct())
    }
    @Test fun serviceErrorsFallThroughAndDoNotPoisonHigherPriorityCache() = runBlocking {
        val source = Sources().apply { failures = setOf("OMDb") }
        val resolver = MetadataResolver(source, Cache())
        assertEquals("TMDB", resolver.resolve(title, Settings())?.provider)
        source.failures = emptySet(); source.calls.clear()
        assertEquals("OMDb", resolver.resolve(title, Settings())?.provider)
        assertEquals(listOf("OMDb"), source.calls)
    }
    @Test fun failedDetailsFallThrough() = runBlocking {
        val source = Sources().apply { detailsFailures = setOf("OMDb") }
        assertEquals("TMDB", MetadataResolver(source, Cache()).resolve(title, Settings())?.provider)
    }
    @Test fun changingPriorityDoesNotReuseLowerPriorityResult() = runBlocking {
        val source = Sources(); val resolver = MetadataResolver(source, Cache())
        assertEquals("OMDb", resolver.resolve(title, Settings())?.provider)
        source.calls.clear()
        assertEquals("Kinopoisk", resolver.resolve(title, Settings(metadataOrder = listOf("Kinopoisk", "OMDb", "TMDB")))?.provider)
        assertEquals(listOf("Kinopoisk"), source.calls)
    }
    @Test fun cacheDeduplicatesAndForceRefreshRemovesNegativeResult() = runBlocking {
        val source = Sources().apply { available = emptySet() }; val resolver = MetadataResolver(source, Cache())
        assertNull(resolver.resolve(title, Settings()))
        source.available = setOf("OMDb"); source.calls.clear()
        assertNull(resolver.resolve(title, Settings())); assertTrue(source.calls.isEmpty())
        assertEquals("OMDb", resolver.resolve(title, Settings(), forceRefresh = true)?.provider)
        source.calls.clear()
        assertEquals("OMDb", resolver.resolve(title, Settings())?.provider); assertTrue(source.calls.isEmpty())
    }
    @Test fun languageSeparatesCacheAndAniListIsOptIn() = runBlocking {
        val source = Sources(); val resolver = MetadataResolver(source, Cache())
        resolver.resolve(title, Settings(language = "en-US")); source.calls.clear()
        assertEquals("OMDb", resolver.resolve(title, Settings(language = "ru-RU"))?.provider)
        assertFalse(source.calls.isEmpty())
        source.calls.clear()
        assertEquals("AniList", resolver.resolve(title, Settings(aniListEnabled = true))?.provider)
        assertEquals(listOf("AniList"), source.calls)
    }
    @Test fun cancellationStopsFallbackAndWritesNoCache() = runBlocking {
        val source = Sources().apply { cancel = true }; val cache = Cache()
        try { MetadataResolver(source, cache).resolve(title, Settings()); fail("Cancellation was swallowed") }
        catch(expected: CancellationException) { }
        assertEquals(listOf("OMDb"), source.calls); assertTrue(cache.entries.isEmpty())
    }
    @Test fun malformedOrderIsNormalizedAndMovesPreserveEveryProvider() {
        assertEquals(listOf("Kinopoisk", "OMDb", "TMDB"), MetadataPriority.normalized(listOf("invalid", "Kinopoisk", "Kinopoisk")))
        assertEquals(listOf("TMDB", "OMDb", "Kinopoisk"), MetadataPriority.move(MetadataPriority.defaultOrder, "TMDB", -1))
        assertEquals(MetadataPriority.defaultOrder, MetadataPriority.move(MetadataPriority.defaultOrder, "OMDb", -1))
        assertEquals(MetadataPriority.defaultOrder, MetadataPriority.move(MetadataPriority.defaultOrder, "invalid", 1))
    }
}
