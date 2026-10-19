package tv.torrserve.silicon

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.database.LibraryStore
import tv.torrserve.silicon.core.database.MetadataCache
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.features.catalog.data.CatalogCache
import tv.torrserve.silicon.features.developer.*
import tv.torrserve.silicon.features.library.*
import tv.torrserve.silicon.features.metadata.*

class DeveloperControlsTests {
    @Test fun startupPreviewsAreOneShotAndUnavailableInRelease() {
        val release = DeveloperControls(false)
        release.previewStartup(true); assertNull(release.takeStartupPreview())
        val debug = DeveloperControls(true)
        debug.previewStartup(true)
        val slow = requireNotNull(debug.takeStartupPreview())
        assertEquals(7_000L, slow.readyDelayMs); assertNull(debug.takeStartupPreview())
        debug.previewStartup(false)
        val fast = requireNotNull(debug.takeStartupPreview())
        assertEquals(0L, fast.readyDelayMs); assertTrue(fast.sequence > slow.sequence)
        assertEquals(DeveloperState(), debug.state.value)
    }
    @Test fun releaseControlsAreInertAndNewSessionHasNoOverrides() {
        val release=DeveloperControls(false)
        release.update { DeveloperState(setOf("TMDB"),true,true,true) }
        assertFalse(release.state.value.active)
        for(service in listOf("TMDB","OMDb","Kinopoisk","AniList","TorrServer"))release.beforeRequest(service)
        val debug=DeveloperControls(true);debug.update{it.copy(missingKeys=setOf("TMDB"))}
        assertTrue(debug.state.value.active);assertFalse(DeveloperControls(true).state.value.active)
        debug.reloadCatalog();val first=debug.catalogReloads.value
        debug.reset();assertTrue(debug.catalogReloads.value>first)
        val reset=debug.catalogReloads.value
        debug.reloadCatalog();assertTrue(debug.catalogReloads.value>reset)
        debug.reset();assertEquals(DeveloperState(),debug.state.value)
        release.reset();assertEquals(0,release.catalogReloads.value)
    }
    @Test fun keyAndNetworkFailuresAreScopedAndNeverSentToNetwork():Unit=runBlocking {
        MockWebServer().use { server ->
            server.start();val controls=DeveloperControls(true);val http=Http(beforeRequest=controls::beforeRequest)
            for(provider in DeveloperControls.keyedServices) {
                controls.update{DeveloperState(missingKeys=setOf(provider))}
                val error=try{http.get(server.url("/metadata"),provider);error("Expected simulated failure")}catch(e:ServiceFailure){e}
                assertEquals(401,error.status);assertTrue(controls.state.value.bypassMetadata(provider))
            }
            controls.update{DeveloperState(metadataOffline=true)}
            for(provider in DeveloperControls.keyedServices+"AniList") {
                try{http.get(server.url("/metadata"),provider);fail()}catch(e:ServiceFailure){assertEquals(408,e.status)}
            }
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("Jackett works"));assertEquals("Jackett works",http.get(server.url("/"),"Jackett").toString(Charsets.UTF_8))
            controls.reset();server.enqueue(MockResponse().setBody("TMDB works"));assertEquals("TMDB works",http.get(server.url("/"),"TMDB").toString(Charsets.UTF_8))
        }
    }
    @Test fun serverSimulationLeavesOtherServicesAndHealthClientAvailable():Unit=runBlocking {
        MockWebServer().use { server ->
            server.start();val controls=DeveloperControls(true).apply{update{it.copy(serverUnavailable=true)}}
            val http=Http(beforeRequest=controls::beforeRequest)
            try{http.get(server.url("/"),"TorrServer");fail()}catch(e:ServiceFailure){assertEquals(0,e.status)}
            server.enqueue(MockResponse().setBody("MatriX.146.1"))
            assertEquals("MatriX.146.1",Http(http.client).get(server.url("/echo"),"TorrServer").toString(Charsets.UTF_8))
            controls.beforeRequest("TMDB");assertEquals(1,server.requestCount)
        }
    }
    @Test fun catalogSimulationBypassesReadWriteAndDeleteWithoutErasingCachedLanguages():Unit=runBlocking {
        val entries=mutableMapOf<String,CatalogCache.Entry>()
        val disk=object:CatalogCache {
            override suspend fun read(key:String)=entries[key]
            override suspend fun write(key:String,payload:String,ttl:Long,retention:Long){entries[key]=CatalogCache.Entry(payload,0,ttl)}
            override suspend fun remove(key:String){entries.remove(key)}
        }
        val controls=DeveloperControls(true);val cache=DeveloperCatalogCache(disk,controls)
        cache.write("ru","Русский",100,200);cache.write("en","English",100,200)
        val original=entries.toMap()
        controls.update{it.copy(missingKeys=setOf("TMDB"))}
        assertNull(cache.read("ru"));cache.write("ru","wrong",100,200);cache.remove("en");assertEquals(original,entries)
        controls.reset();assertEquals("Русский",cache.read("ru")?.payload);assertEquals("English",cache.read("en")?.payload)
    }
    @Test fun missingKeyBypassesCachedMetadataFallsBackAndResetRecoversOriginalCache():Unit=runBlocking {
        val controls=DeveloperControls(true);val entries=mutableMapOf<String,Metadata?>();val calls=mutableListOf<String>()
        val cache=object:MetadataCache {
            override suspend fun cached(key:String)=LibraryStore.Cached(entries.containsKey(key),entries[key])
            override suspend fun cache(key:String,value:Metadata?){entries[key]=value}
            override suspend fun removeCache(key:String){entries.remove(key)}
        }
        val sources=object:MetadataSource {
            override suspend fun search(p:ParsedName,s:Settings,provider:String):List<MetadataMatch> {
                calls+=provider;controls.beforeRequest(provider)
                return listOf(MetadataMatch(Metadata(provider,"42",p.title,p.title,p.year,p.kind)))
            }
            override suspend fun details(match:MetadataMatch,s:Settings):Metadata {controls.beforeRequest(match.value.provider);return match.value}
        }
        val resolver=MetadataResolver(sources,cache){controls.state.value.bypassMetadata(it)}
        val names=listOf("Interstellar.2014.1080p.mkv");val settings=Settings()
        assertEquals("OMDb",resolver.resolve(names,settings)?.provider)
        val original=entries.toMap();controls.update{it.copy(missingKeys=setOf("OMDb"))}
        assertEquals("TMDB",resolver.resolve(names,settings,forceRefresh=true)?.provider)
        assertEquals(original.values.first(),entries[original.keys.first()])
        controls.update{it.copy(metadataOffline=true)};val saved=entries.toMap()
        assertNull(resolver.resolve(names,settings,true));assertEquals(saved,entries)
        controls.reset();calls.clear();assertEquals("OMDb",resolver.resolve(names,settings)?.provider);assertTrue(calls.isEmpty())
    }
    @Test fun libraryStatusDistinguishesStoppedRemoteErrorsAndLiveServer() {
        val running=ServerState.Running("MatriX.146.1",42)
        val connected=ConnectionState(ConnectionStatus.Connected)
        assertEquals(LibraryAvailability.Stopped,libraryAvailability(true,ServerState.Stopped,connected))
        assertEquals(LibraryAvailability.Stopped,libraryAvailability(true,ServerState.Stopping,connected))
        assertEquals(LibraryAvailability.Connecting,libraryAvailability(true,ServerState.Starting,connected))
        assertEquals(LibraryAvailability.Unavailable,libraryAvailability(true,ServerState.Error("failed"),connected))
        assertEquals(LibraryAvailability.Ready,libraryAvailability(false,ServerState.Stopped,connected))
        assertEquals(LibraryAvailability.Ready,libraryAvailability(true,running,connected))
        assertEquals(LibraryAvailability.AccessDenied,libraryAvailability(true,running,connected,syncFailure=403))
        assertEquals(LibraryAvailability.Unavailable,libraryAvailability(true,running,connected,syncFailure=0))
        assertEquals(LibraryAvailability.Unavailable,libraryAvailability(true,running,connected,true))
        for(code in listOf(401,403))assertEquals(LibraryAvailability.AccessDenied,libraryAvailability(false,running,ConnectionState(ConnectionStatus.Error,failure=code)))
        assertEquals(LibraryAvailability.InvalidResponse,libraryAvailability(false,running,ConnectionState(ConnectionStatus.Error,failure=-2)))
        assertEquals(LibraryAvailability.Unavailable,libraryAvailability(false,running,ConnectionState(ConnectionStatus.Unavailable,failure=408)))
    }
}
