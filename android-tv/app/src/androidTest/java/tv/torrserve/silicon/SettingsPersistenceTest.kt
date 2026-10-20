package tv.torrserve.silicon

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.network.CatalogSource
import tv.torrserve.silicon.core.storage.*
import tv.torrserve.torrentsearch.model.SearchTitleMode

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    @Test fun appearanceWritesKeepEncryptedSecretsAndRevisionsSurviveReopening() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize()
            val jackett=f.prefs.getString("jackettKey","");val cub=f.prefs.getString("cubToken","")
            assertFalse(jackett.isNullOrEmpty());assertFalse(cub.isNullOrEmpty())
            val revision=f.settings.revision.value
            repeat(10) { f.settings.update { it.copy(uiScalePercent=if(it.uiScalePercent==85)90 else 85) } }
            assertEquals(jackett,f.prefs.getString("jackettKey",""));assertEquals(cub,f.prefs.getString("cubToken",""))
            assertEquals(revision+10,f.settings.revision.value)
            val reopened=SettingsStore(f.context);reopened.load()
            assertEquals(f.settings.state.value,reopened.state.value);assertEquals(f.settings.revision.value,reopened.revision.value)
            f.settings.update { it.copy(jackettKey="replacement-key",catalogNetwork=it.catalogNetwork.copy(cubToken="replacement-token")) }
            assertNotEquals(jackett,f.prefs.getString("jackettKey",""));assertNotEquals(cub,f.prefs.getString("cubToken",""))
            reopened.load();assertEquals("replacement-key",reopened.state.value.jackettKey)
            assertEquals("replacement-token",reopened.state.value.catalogNetwork.cubToken)
            f.settings.update { it.copy(jackettKey="",catalogNetwork=it.catalogNetwork.copy(cubToken="")) }
            reopened.load();assertEquals("",reopened.state.value.jackettKey);assertEquals("",reopened.state.value.catalogNetwork.cubToken)
        } finally { f.close() }
    }
    @Test fun independentFieldUpdatesRoundTripWithoutLosingOtherSettings() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize()
            f.settings.update { it.copy(jackettEnabled=true,jackettUrl="http://fixture.example:9117",metadataOrder=it.metadataOrder.reversed(),
                language="ru-RU",port=8091,cacheMb=128,resume=false,audioLanguage="ru",subtitleLanguage="en",
                updateRepository="fixture/repo",uiLanguage="ru",aniListEnabled=true,torrServerUrl="http://fixture.example:8091",
                startOnBoot=true,engineSettingsInitialized=true,lanAccess=false,uiScalePercent=90,uiTheme="light",
                showNavigationHints=false,uiAccent="white",animationsEnabled=false,
                torrentSearch=it.torrentSearch.copy(minSeeds=5,titleMode=SearchTitleMode.Russian),
                builtInPlayer=it.builtInPlayer.copy(speed=1.25f),externalPlayer="fixture/player",
                catalogNetwork=it.catalogNetwork.copy(source=CatalogSource.Cub,showFavorites=false,showWatchLater=false)) }
            val reopened=SettingsStore(f.context);reopened.load()
            assertEquals(f.settings.state.value,reopened.state.value)
            f.settings.update { it.copy(animationsEnabled=true) }
            reopened.load();assertEquals(f.settings.state.value,reopened.state.value)
        } finally { f.close() }
    }
    @Test fun concurrentPhoneRevisionAndTvUpdatesKeepConflictDetection() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize();val revision=f.settings.revision.value
            val results=coroutineScope { List(2) { index -> async { runCatching {
                f.settings.update(revision) { it.copy(uiScalePercent=if(index==0)90 else 95) }
            } } }.awaitAll() }
            assertEquals(1,results.count { it.isSuccess });assertEquals(1,results.count { it.exceptionOrNull() is SettingsConflict })
            assertEquals(revision+1,f.settings.revision.value)
            coroutineScope { List(10) { async { f.settings.update { it.copy(resume=!it.resume) } } }.awaitAll() }
            assertEquals(revision+11,f.settings.revision.value)
            val reopened=SettingsStore(f.context);reopened.load();assertEquals(f.settings.state.value,reopened.state.value)
        } finally { f.close() }
    }
    @Test fun fullSaveAndExternalStoreWritesKeepSnapshotSemantics() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize();val original=f.settings.state.value
            val other=SettingsStore(f.context);other.load()
            other.update { it.copy(uiTheme="light",jackettKey="other-key") }
            // A stale store's update still persists its complete intended snapshot, as before.
            f.settings.update { it.copy(uiScalePercent=90) }
            other.load();assertEquals(original.copy(uiScalePercent=90),other.state.value)
            other.update { it.copy(uiTheme="light") }
            f.settings.save(original)
            other.load();assertEquals(original,other.state.value)
        } finally { f.close() }
    }
    @Test fun failedCommitIsNotAcknowledgedAndNextWriteRestoresTheIntendedSnapshot() = runBlocking {
        val f=StorageFixture()
        try {
            f.initialize();var reject=false
            val prefs=object : SharedPreferences by f.prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor=f.prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean { val written=editor.commit();return written && !reject }
                    }
                }
            }
            val context=object : ContextWrapper(f.context) { override fun getSharedPreferences(name:String,mode:Int)=prefs }
            val store=SettingsStore(context);store.load();val original=store.state.value;val revision=store.revision.value
            reject=true
            try { store.update { it.copy(jackettKey="unacknowledged-key",uiTheme="light") };fail("Commit should fail") }
            catch(_:IllegalStateException) { }
            assertEquals(original,store.state.value);assertEquals(revision,store.revision.value)
            reject=false;store.update { it.copy(uiScalePercent=90) }
            val reopened=SettingsStore(f.context);reopened.load()
            assertEquals(original.copy(uiScalePercent=90),reopened.state.value)
            assertEquals(revision+1,reopened.revision.value)
        } finally { f.close() }
    }
}
