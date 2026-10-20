package tv.torrframe

import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import tv.torrframe.core.database.LibraryStore
import tv.torrframe.core.database.MetadataCache
import tv.torrframe.core.models.*
import tv.torrframe.core.storage.*
import tv.torrframe.features.metadata.*

class MetadataResolverTests {
    private class Cache : MetadataCache {
        val entries = Collections.synchronizedMap(mutableMapOf<String, Metadata?>())
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

    @Test fun unrelatedTitleFinishesWhileDuplicateWaitsForTheSameSlowLookup() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val slowCalls=AtomicInteger();val fastCalls=AtomicInteger()
        val source=object:MetadataSource {
            override suspend fun search(p:ParsedName,s:Settings,provider:String):List<MetadataMatch> {
                if(p.title=="Interstellar") { slowCalls.incrementAndGet();entered.complete(Unit);release.await() }
                else fastCalls.incrementAndGet()
                return listOf(MetadataMatch(Metadata(provider,p.title,p.title,p.title,p.year,p.kind)))
            }
            override suspend fun details(match:MetadataMatch,s:Settings)=match.value
        }
        val resolver=MetadataResolver(source,Cache())
        try {
            val first=async { resolver.resolve(title,Settings()) }
            withTimeout(3000) { entered.await() }
            val duplicate=async(start=CoroutineStart.UNDISPATCHED) { resolver.resolve(title,Settings()) }
            val fast=async { resolver.resolve(listOf("Arrival.2016.mkv"),Settings()) }
            assertEquals("Arrival",withTimeout(3000) { fast.await() }?.title)
            assertFalse(first.isCompleted);assertEquals(1,fastCalls.get())
            release.complete(Unit)
            assertEquals(first.await(),duplicate.await());assertEquals(1,slowCalls.get())
        } finally { release.complete(Unit) }
    }
    @Test fun independentLookupsStillLimitNetworkConcurrencyToTwo() = runBlocking {
        val release=CompletableDeferred<Unit>();val twoStarted=CompletableDeferred<Unit>()
        val active=AtomicInteger();val peak=AtomicInteger();val started=AtomicInteger()
        val source=object:MetadataSource {
            override suspend fun search(p:ParsedName,s:Settings,provider:String):List<MetadataMatch> {
                val count=active.incrementAndGet();peak.updateAndGet { maxOf(it,count) }
                if(started.incrementAndGet()==2)twoStarted.complete(Unit)
                try { release.await();return listOf(MetadataMatch(Metadata(provider,p.title,p.title,p.title,p.year,p.kind))) }
                finally { active.decrementAndGet() }
            }
            override suspend fun details(match:MetadataMatch,s:Settings)=match.value
        }
        val resolver=MetadataResolver(source,Cache())
        try {
            val calls=listOf("Interstellar.2014.mkv","Arrival.2016.mkv","Dune.2021.mkv").map { async { resolver.resolve(listOf(it),Settings()) } }
            withTimeout(3000) { twoStarted.await() }
            assertEquals(2,active.get());assertEquals(2,started.get())
            release.complete(Unit);assertTrue(calls.awaitAll().all { it!=null })
            assertEquals(2,peak.get());assertEquals(3,started.get())
        } finally { release.complete(Unit) }
    }
    @Test fun cancelledOwnerDoesNotLeaveItsDuplicateLockedOrCacheAFailedResult() = runBlocking {
        val entered=CompletableDeferred<Unit>();val calls=AtomicInteger();val cache=Cache()
        val source=object:MetadataSource {
            override suspend fun search(p:ParsedName,s:Settings,provider:String):List<MetadataMatch> {
                if(calls.incrementAndGet()==1) { entered.complete(Unit);awaitCancellation() }
                return listOf(MetadataMatch(Metadata(provider,p.title,p.title,p.title,p.year,p.kind)))
            }
            override suspend fun details(match:MetadataMatch,s:Settings)=match.value
        }
        val resolver=MetadataResolver(source,cache)
        val first=async { resolver.resolve(title,Settings()) }
        withTimeout(3000) { entered.await() }
        val duplicate=async(start=CoroutineStart.UNDISPATCHED) { resolver.resolve(title,Settings()) }
        first.cancelAndJoin()
        assertEquals("Interstellar",withTimeout(3000) { duplicate.await() }?.title)
        assertEquals(2,calls.get());assertEquals(1,cache.entries.size)
        assertEquals("Interstellar",resolver.resolve(title,Settings())?.title);assertEquals(2,calls.get())
    }
}
