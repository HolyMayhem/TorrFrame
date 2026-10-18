package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.storage.Settings
import tv.torrserve.silicon.core.storage.SettingsStore
import tv.torrserve.silicon.features.torrentsearch.data.TorrentSearchConfiguration
import tv.torrserve.torrentsearch.model.*

@RunWith(AndroidJUnit4::class)
class TorrentSearchSettingsNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=(compose.activity.application as TorrFrameApplication).graph
    private lateinit var original:Settings
    @Before fun defaults()=runBlocking {
        graph.ready.await();original=graph.settings.state.value
        graph.settings.update { it.copy(torrentSearch=TorrentSearchOptions()) }
    }
    @After fun restore()=runBlocking { graph.settings.update { it.copy(torrentSearch=original.torrentSearch,uiScalePercent=original.uiScalePercent,uiTheme=original.uiTheme,uiLanguage=original.uiLanguage) } }
    private fun remote(key:Int){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle()}
    private fun open() {
        compose.onNodeWithTag("nav:Settings").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:TorrentSearch"))
        compose.onNodeWithTag("settings:category:TorrentSearch").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("settings:back").assertIsFocused()
    }
    private fun focus(tag:String) {
        compose.onNodeWithTag("settings:detail:TorrentSearch").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
    }
    private fun choose(tag:String){focus(tag);remote(KeyEvent.KEYCODE_DPAD_CENTER)}
    private fun saved(check:(TorrentSearchOptions)->Boolean)=compose.waitUntil(5000){check(graph.settings.state.value.torrentSearch)}
    @Test fun allControlsPersistAndBackReturnsToSameCategoryWithRemote() {
        open();choose("ts:source:rutorinfo");saved{it.enabledProviders?.contains("rutorinfo")==false}
        compose.onNodeWithTag("ts:source:rutorinfo").assertIsFocused()
        choose("ts:resolution:1080p");saved{it.resolutions==setOf("1080p")}
        choose("ts:resolution:2160p");saved{it.resolutions==setOf("1080p","2160p")}
        choose("ts:format:WEB-DL");saved{it.formats==setOf("WEB-DL")}
        choose("ts:min-size:plus");saved{it.minBytes==TorrentSearchOptions.GIB/2}
        choose("ts:max-size:plus");saved{it.maxBytes==TorrentSearchOptions.GIB/2}
        choose("ts:max-size:plus");saved{it.maxBytes==TorrentSearchOptions.GIB}
        choose("ts:seeds:5");saved{it.minSeeds==5L}
        choose("ts:zero");saved{it.hideZeroSeeds}
        choose("ts:timeout:plus");saved{it.sourceTimeoutMs==19_000L}
        choose("ts:title:Russian");saved{it.titleMode==SearchTitleMode.Russian}
        val expected=graph.settings.state.value.torrentSearch
        val reopened=SettingsStore(compose.activity)
        runBlocking{reopened.load()};assertEquals(expected,reopened.state.value.torrentSearch)
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("settings:category:TorrentSearch").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("settings:back").assertIsFocused()
        focus("ts:title:Russian");compose.onNodeWithTag("ts:title:Russian").assertIsSelected()
        choose("ts:reset");saved{it==TorrentSearchOptions()}
        compose.onNodeWithTag("ts:reset").assertIsFocused()
    }
    @Test fun disabledAllSurvivesReloadAndResetRestoresAllSources() {
        open();choose("ts:none");saved{it.enabledProviders==emptySet<String>()}
        val reloaded=SettingsStore(compose.activity);runBlocking{reloaded.load()}
        assertEquals(emptySet<String>(),reloaded.state.value.torrentSearch.enabledProviders)
        choose("ts:all");saved{it.enabledProviders==null}
        choose("ts:timeout:minus");saved{it.sourceTimeoutMs==17_000L}
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("ts:timeout:plus").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_LEFT);compose.onNodeWithTag("ts:timeout:minus").assertIsFocused()
        choose("ts:reset");saved{it==TorrentSearchOptions()}
    }
    @Test fun controlsRemainInsideDrawerAtBothScaleExtremes() {
        for((scale,theme) in listOf(70 to "dark",110 to "light")) {
            runBlocking{graph.settings.update{it.copy(uiScalePercent=scale,uiTheme=theme)}}
            open()
            for(tag in listOf("ts:source:rutorinfo","ts:resolution:2160p","ts:format:BluRay","ts:min-size:plus","ts:max-size:plus","ts:timeout:plus","ts:title:Both","ts:reset")) {
                focus(tag)
                val node=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                val drawer=compose.onNodeWithTag("settings:drawer").fetchSemanticsNode().boundsInRoot
                assertTrue("$tag outside drawer at $scale",node.left>=drawer.left && node.right<=drawer.right && node.top>=drawer.top && node.bottom<=drawer.bottom)
                compose.onNodeWithTag(tag).assertIsFocused()
            }
            remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000){compose.onAllNodesWithTag("settings:drawer").fetchSemanticsNodes().isEmpty()}
        }
    }
    @Test fun russianQueryUsesExistingTmdbLocalizationEvenWithEnglishInterface():Unit=runBlocking {
        withCatalogDetailsFixture(graph) {
        graph.settings.update{it.copy(uiLanguage="en")}
        val config=TorrentSearchConfiguration(graph)
        val target=SearchTarget("Interstellar","Interstellar",2014,tmdbId=157336)
        val russian=config.prepare(target,TorrentSearchOptions(titleMode=SearchTitleMode.Russian))
        assertEquals("Интерстеллар",russian.title)
        assertEquals(listOf("Интерстеллар 2014"),russian.queries(SearchTitleMode.Russian))
        assertEquals(target,config.prepare(target,TorrentSearchOptions(titleMode=SearchTitleMode.Original)))
        }
    }
    @Test fun counterBoundariesMoveFocusToEnabledButton() {
        runBlocking { graph.settings.update { it.copy(torrentSearch=it.torrentSearch.copy(sourceTimeoutMs=6_000)) } }
        open();choose("ts:timeout:minus");saved{it.sourceTimeoutMs==5_000L}
        compose.onNodeWithTag("ts:timeout:plus").assertIsFocused()
        runBlocking { graph.settings.update { it.copy(torrentSearch=it.torrentSearch.copy(sourceTimeoutMs=59_000)) } }
        choose("ts:timeout:plus");saved{it.sourceTimeoutMs==60_000L}
        compose.onNodeWithTag("ts:timeout:minus").assertIsFocused()
    }
    @Test fun catalogSearchUsesPersistedSourceSelectionFromNewCategory():Unit=runBlocking {
        withCatalogDetailsFixture(graph) {
        runBlocking { graph.settings.update { it.copy(torrentSearch=it.torrentSearch.copy(enabledProviders=emptySet())) } }
        compose.onNodeWithTag("nav:Search").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        val catalog=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${tv.torrserve.silicon.features.catalog.presentation.CatalogViewModel::class.java.name}") as tv.torrserve.silicon.features.catalog.presentation.CatalogViewModel
        compose.runOnUiThread { catalog.open(tv.torrserve.silicon.features.catalog.domain.CatalogRef(157336,tv.torrserve.silicon.features.catalog.domain.CatalogType.Movie)) }
        compose.waitUntil(20_000){catalog.state.value.details.data!=null}
        compose.onNodeWithTag("catalog:torrentsearch").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        val search=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${tv.torrserve.silicon.features.torrentsearch.presentation.TorrentSearchViewModel::class.java.name}") as tv.torrserve.silicon.features.torrentsearch.presentation.TorrentSearchViewModel
        compose.waitUntil(5000){!search.state.value.searching}
        assertEquals(emptySet<String>(),search.state.value.options.enabledProviders)
        assertTrue(search.state.value.snapshot.sources.isEmpty());compose.onNodeWithTag("torrentsearch:empty").assertIsDisplayed()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:torrentsearch").assertIsFocused()
        }
    }

}
