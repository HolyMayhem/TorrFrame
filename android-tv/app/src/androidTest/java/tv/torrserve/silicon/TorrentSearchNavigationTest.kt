package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.models.TorrentFile
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.presentation.CatalogViewModel
import tv.torrserve.silicon.features.torrentsearch.data.TorrentSearchActions
import tv.torrserve.silicon.features.torrentsearch.presentation.*
import tv.torrserve.silicon.ui.components.LocalUiLanguage
import tv.torrserve.silicon.ui.theme.TorrFrameTheme
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

@RunWith(AndroidJUnit4::class)
class TorrentSearchNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val models=ViewModelStore();private lateinit var vm:TorrentSearchViewModel
    private fun row(id:Int,seeds:Long?)=TorrentResult("fake","Fixture","Film $id 1080p WEB-DL",seeds,3,sizeBytes=2048,
        magnet=TorrentIdentity.magnet("%040x".format(id)))
    private val first get()=row(1,5)
    private fun provider(id:String,block:suspend () -> List<TorrentResult>)=object:SearchProvider {
        override val id=id;override val name=id
        override suspend fun search(query:String,type:MediaType)=block()
    }
    private class Actions:TorrentSearchActions {
        var adds=0;var watched=0;var fail=false;var videos=listOf(TorrentFile(1,"episode1.mp4",100))
        override suspend fun add(row:TorrentResult):String { adds++;if(fail)error("failure");return "fixture" }
        override suspend fun files(hash:String):List<TorrentFile>{watched++;return videos}
    }
    private fun setup(providers:List<SearchProvider> = listOf(provider("fake"){listOf(first,row(2,null))}),
        actions:Actions=Actions(),scale:Int=85,light:Boolean=false):Actions {
        compose.runOnUiThread {
            vm=TorrentSearchViewModel(TorrentSearchEngine(providers,sourceTimeout=5000),actions,androidx.lifecycle.SavedStateHandle());models.put("search",vm)
            compose.activity.setContent {
                val state by vm.state.collectAsState()
                val focus=remember{FocusRequester()}
                TorrFrameTheme(scale,light,animationsEnabled=false) {
                    CompositionLocalProvider(LocalUiLanguage provides "ru") { TorrentSearchScreen(state,vm,focus,vm::close) }
                }
            }
            vm.open(SearchTarget("Film",year=2024))
        }
        compose.waitUntil(5000){!vm.state.value.starting}
        return actions
    }
    private fun remote(key:Int){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle()}
    private fun node(row:TorrentResult)=compose.onNodeWithTag("torrentsearch:row:${row.key}")
    @After fun clear(){compose.runOnUiThread{models.clear()}}
    @Test fun lateHigherSeededResultsRetainFocusedIdentityAndMenusImportWithoutPlayback() {
        val gate=CompletableDeferred<Unit>()
        val actions=setup(listOf(provider("fast"){listOf(first,row(2,null))},provider("slow"){gate.await();listOf(row(3,50),first.copy(seeds=20))}))
        compose.waitUntil(5000){vm.state.value.snapshot.results.size==2}
        compose.onNodeWithTag("torrentsearch:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);node(first).assertIsFocused()
        gate.complete(Unit)
        compose.waitUntil(5000){!vm.state.value.searching}
        assertEquals(listOf(50L,20L,null),vm.state.value.snapshot.results.map{it.seeds})
        node(first).assertIsFocused();remote(KeyEvent.KEYCODE_DPAD_UP);node(row(3,50)).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("torrentsearch:add").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000){compose.onAllNodesWithTag("torrentsearch:actions").fetchSemanticsNodes().isEmpty()}
        node(row(3,50)).assertIsFocused();assertEquals(1,actions.adds);assertEquals(0,actions.watched);assertNull(vm.state.value.play)
    }
    @Test fun watchWithMultipleVideosSelectsFileAndReturnsFocusAfterChooser() {
        val actions=setup(actions=Actions().apply{videos=listOf(TorrentFile(1,"one.mp4",10),TorrentFile(2,"two.mp4",20))})
        compose.waitUntil(5000){vm.state.value.snapshot.results.isNotEmpty()}
        remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("torrentsearch:watch").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000){vm.state.value.files.size==2}
        compose.onNodeWithTag("torrentsearch:file:1").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("torrentsearch:file:2").assertIsFocused();remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2,vm.state.value.play!!.file.id);assertEquals(1,actions.watched)
    }
    @Test fun retryWorksAfterSourceFailureAndImportFailureKeepsActionsUsable() {
        var broken=true
        val actions=setup(listOf(provider("fake"){if(broken)error("blocked");listOf(first)}),Actions().apply{fail=true})
        compose.waitUntil(5000){!vm.state.value.searching}
        compose.onNodeWithTag("torrentsearch:empty").assertIsDisplayed()
        broken=false;compose.onNodeWithTag("torrentsearch:retry").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000){vm.state.value.snapshot.results.isNotEmpty()}
        node(first).performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000){vm.state.value.actionError!=null}
        compose.onNodeWithTag("torrentsearch:actions").assertExists();assertEquals(1,actions.adds)
        remote(KeyEvent.KEYCODE_BACK);node(first).assertIsFocused()
    }
    @Test fun compactRowsFitBothThemeScaleExtremes() {
        for((scale,light) in listOf(70 to false,110 to true)) {
            setup(scale=scale,light=light);compose.waitUntil(5000){vm.state.value.snapshot.results.isNotEmpty()}
            compose.onNodeWithTag("torrentsearch:back").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_DOWN);node(first).assertIsFocused()
            val row=node(first).fetchSemanticsNode().boundsInRoot
            val list=compose.onNodeWithTag("torrentsearch:results").fetchSemanticsNode().boundsInRoot
            assertTrue(row.left>=list.left && row.right<=list.right && row.top>=list.top && row.bottom<=list.bottom)
        }
    }
    @Test fun actualCatalogButtonBuildsOriginalTitleYearQueryAndBackRestoresIt():Unit=runBlocking {
        withCatalogDetailsFixture((compose.activity.application as TorrFrameApplication).graph) {
        compose.onNodeWithTag("nav:Search").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        val catalog=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${CatalogViewModel::class.java.name}") as CatalogViewModel
        compose.runOnUiThread{catalog.open(CatalogRef(157336,CatalogType.Movie))}
        compose.waitUntil(20_000){catalog.state.value.details.data!=null}
        compose.onNodeWithTag("catalog:torrentsearch").performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("torrentsearch:screen").assertExists()
        compose.onNodeWithTag("torrentsearch:query").assertTextContains("Interstellar 2014",substring=true)
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("catalog:torrentsearch").assertIsFocused()
        }
    }
}
