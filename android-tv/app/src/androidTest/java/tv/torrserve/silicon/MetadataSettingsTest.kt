package tv.torrserve.silicon

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.features.metadata.BuiltInMetadataKeys
import tv.torrserve.silicon.features.metadata.MediaNameParser

@RunWith(AndroidJUnit4::class)
class MetadataSettingsTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrServeApplication
    @Test fun releaseNameCandidatesAreCompatibleWithAndroidRegex() {
        for(provider in listOf("OMDb", "TMDB", "Kinopoisk", "AniList")) {
            val candidates = MediaNameParser.candidates(listOf(
                "Interstellar [release] {group} (Director) / Интерстеллар 2014 1080p.mkv",
                "Cowboy.Bebop.S01E01.720p.mkv", "Arrival [unfinished"
            ), provider, "ru-RU")
            assertTrue(candidates.isNotEmpty())
            assertTrue(candidates.any { it.parsed.year == 2014 || it.parsed.season == 1 })
        }
    }
    @Test fun priorityAndAniListSurviveReopeningSettings() = runBlocking {
        app.graph.ready.await()
        val original = app.graph.settings.state.value
        val store = SettingsStore(app)
        try {
            store.save(original.copy(metadataOrder = listOf("Kinopoisk", "OMDb", "TMDB"), aniListEnabled = true))
            val reopened = SettingsStore(app); reopened.load()
            assertEquals(listOf("Kinopoisk", "OMDb", "TMDB"), reopened.state.value.metadataOrder)
            assertTrue(reopened.state.value.aniListEnabled)
        } finally { store.save(original) }
    }
    @Test fun legacyKeysAreRemovedWithoutDecodingAndJackettIsRetained() = runBlocking {
        app.graph.ready.await()
        val original = app.graph.settings.state.value
        val store = SettingsStore(app)
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        try {
            store.save(original.copy(jackettKey = "metadata-migration-jackett-test"))
            prefs.edit().putString("provider", "AniList").remove("aniListEnabled")
                .putString("metadataKey_TMDB", "obsolete-key-not-valid-ciphertext")
                .putString("metadataKey_OMDb", "obsolete-key")
                .putString("metadataOrder", "unknown,Kinopoisk,Kinopoisk").commit()
            val migrated = SettingsStore(app); migrated.load()
            assertEquals("metadata-migration-jackett-test", migrated.state.value.jackettKey)
            assertEquals(listOf("Kinopoisk", "OMDb", "TMDB"), migrated.state.value.metadataOrder)
            assertTrue(migrated.state.value.aniListEnabled)
            assertFalse(prefs.contains("provider"))
            assertFalse(prefs.all.keys.any { it.startsWith("metadataKey_") })
            val reopened = SettingsStore(app); reopened.load(); assertTrue(reopened.state.value.aniListEnabled)
        } finally { store.save(original) }
    }
    @Test fun embeddedKeysResolveRealMetadataWithoutUserInput() = runBlocking {
        app.graph.ready.await()
        for(provider in listOf("OMDb", "TMDB", "Kinopoisk")) assertTrue(BuiltInMetadataKeys.forProvider(provider).isNotBlank())
        val metadata = app.graph.metadata.resolve(listOf("Interstellar.2014.1080p.mkv"),
            app.graph.settings.state.value.copy(metadataOrder = listOf("OMDb", "TMDB", "Kinopoisk"), aniListEnabled = false), forceRefresh = true)
        assertNotNull("Embedded provider metadata is unavailable", metadata)
        assertEquals("OMDb", metadata?.provider)
        assertEquals("tt0816692", metadata?.id)
        assertEquals(2014, metadata?.year)
    }
}
