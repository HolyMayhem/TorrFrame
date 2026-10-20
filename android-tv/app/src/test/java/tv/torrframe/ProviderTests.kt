package tv.torrframe

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import tv.torrframe.core.network.Http
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.models.MediaKind
import tv.torrframe.features.metadata.*
import tv.torrframe.features.search.*

class ProviderTests {
    private fun http(server: MockWebServer) = Http(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + "?" + chain.request().url.encodedQuery.orEmpty())).build())
    }.build())
    @Test fun tmdbMovieAndReducedImageUrls() = runBlocking {
        MockWebServer().use { server -> server.start(); server.enqueue(MockResponse().setBody("""{"results":[{"id":157336,"title":"Интерстеллар","original_title":"Interstellar","release_date":"2014-11-05","poster_path":"/poster.jpg","vote_average":8.4}]}"""))
            val m = MetadataProviders(http(server)) { "key" }.search(ParsedName("Interstellar",2014,MediaKind.Movie),Settings(language="ru-RU"), "TMDB").single().value
            assertEquals(2014,m.year); assertEquals("Interstellar",m.originalTitle); assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg",m.poster)
        }
    }
    @Test fun omdbSeriesAndUnavailableImage() = runBlocking {
        MockWebServer().use { server -> server.start(); server.enqueue(MockResponse().setBody("""{"Response":"True","Search":[{"imdbID":"tt0903747","Title":"Breaking Bad","Year":"2008–2013","Type":"series","Poster":"N/A"}]}"""))
            val m = MetadataProviders(http(server)) { "key" }.search(ParsedName("Breaking Bad",2008,MediaKind.Tv),Settings(), "OMDb").single().value
            assertEquals(MediaKind.Tv,m.kind); assertEquals(2008,m.year); assertTrue(m.poster.isEmpty())
        }
    }
    @Test fun kinopoiskRussianTitleAndHeaderAuth() = runBlocking {
        MockWebServer().use { server -> server.start(); server.enqueue(MockResponse().setBody("""{"items":[{"kinopoiskId":258687,"nameRu":"Интерстеллар","nameOriginal":"Interstellar","year":2014,"type":"FILM","ratingKinopoisk":8.6}]}"""))
            val m = MetadataProviders(http(server)) { "key" }.search(ParsedName("Интерстеллар",2014,MediaKind.Movie),Settings(language="ru-RU"), "Kinopoisk").single().value
            assertEquals("Интерстеллар",m.title); assertEquals("key",server.takeRequest().getHeader("X-API-KEY"))
        }
    }
    @Test fun anilistMatchesSynonymsAndKeepsMediaKind() = runBlocking {
        MockWebServer().use { server -> server.start(); server.enqueue(MockResponse().setBody("""{"data":{"Page":{"media":[{"id":1,"title":{"romaji":"Cowboy Bebop","english":"Cowboy Bebop","native":"カウボーイビバップ"},"synonyms":["Bebop"],"format":"TV","startDate":{"year":1998},"averageScore":85,"coverImage":{"large":"https://example.com/poster.jpg"}}]}}}"""))
            val m = MetadataProviders(http(server)) { "key" }.search(ParsedName("Bebop",1998,MediaKind.Tv),Settings(), "AniList").single()
            assertEquals(MediaKind.Tv,m.value.kind); assertTrue("Bebop" in m.aliases); assertEquals(8.5,m.value.rating,.001)
        }
    }
    @Test fun jackettDownloadSupportsRedirectToMagnet() = runBlocking {
        MockWebServer().use { server -> server.start(); val magnet = "magnet:?xt=urn:btih:"+"0".repeat(40)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",magnet))
            val result = SearchResult("id","Title",server.url("/download").toString(),"",0,0,0,"",0,emptySet())
            assertEquals(magnet,(JackettClient(Http()).download(result) as TorrentPayload.Magnet).value)
        }
    }
}
