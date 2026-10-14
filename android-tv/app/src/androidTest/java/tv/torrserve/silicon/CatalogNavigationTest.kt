package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.data.SqliteCatalogCache
import tv.torrserve.silicon.features.catalog.presentation.*
import tv.torrserve.silicon.ui.theme.TorrServeTheme
import tv.torrserve.silicon.ui.components.LocalUiLanguage

@RunWith(AndroidJUnit4::class)
class CatalogNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val store=ViewModelStore()
    private lateinit var vm:CatalogViewModel
    private class Repository(private val count:Int=20,private val failSecondPage:Boolean=false) : CatalogRepository {
        var searches=0;var sectionCalls=0;var discoveryCalls=0
        val detailLocales=mutableListOf<String>()
        fun items(type:CatalogType=CatalogType.Movie, page:Int=1)=(1..count).map { CatalogItem(CatalogRef((page-1)*count+it,type),"Title $it",date="2026-01-01",rating=8.6,votes=100) }
        override fun section(section:CatalogSection,language:String,page:Int,force:Boolean)=flow {
            sectionCalls++
            if(page==2 && failSecondPage) emit(CatalogLoad(error=true))
            else emit(CatalogLoad(data=CatalogPage(items(section.type,page),page,if(count<10)1 else 2)))
        }
        override fun search(query:String,language:String,page:Int,force:Boolean)=flow {
            searches++;emit(CatalogLoad(data=CatalogPage(items(if(query=="Series")CatalogType.Tv else CatalogType.Movie,page),page,2)))
        }
        override fun details(ref:CatalogRef,language:String,force:Boolean)=flow {
            detailLocales+=language
            emit(CatalogLoad(data=CatalogDetails(CatalogItem(ref,"Details ${ref.id}",date="2026-01-01",rating=8.6,votes=100),
                "A complete synopsis. ".repeat(15),listOf("Drama"),120,listOf("Director"),listOf("Actor"),
                if(ref.type==CatalogType.Tv)3 else null,if(ref.type==CatalogType.Tv)30 else null)))
        }
        override fun discover(filter:CatalogFilter,type:CatalogType,language:String,page:Int,force:Boolean)=flow {
            discoveryCalls++;emit(CatalogLoad(data=CatalogPage(items(type,page),page,2)))
        }
    }
    private fun setup(repo:Repository=Repository(),scale:Int=85,light:Boolean=false,header:Boolean=false,
        lists:CatalogUserLists=MemoryCatalogUserLists(),languages:MutableStateFlow<String> = MutableStateFlow("ru-RU")):Repository {
        compose.runOnUiThread {
            vm=CatalogViewModel(repo,languages,androidx.lifecycle.SavedStateHandle(),userLists=lists);store.put("catalog",vm)
            compose.activity.setContent { key(repo) {
                val state by vm.state.collectAsState()
                val entry=remember{FocusRequester()}
                val screen=remember{FocusRequester()}
                TorrServeTheme(scale,light,animationsEnabled=false) {
                    CompositionLocalProvider(LocalUiLanguage provides "ru") {
                        Column {
                            if (header) Row(Modifier.fillMaxWidth().focusProperties { down=screen }.focusGroup()) {
                                for (tab in listOf("Search", "Library", "Settings"))
                                    tv.torrserve.silicon.ui.components.TvNavItem(tab,tab=="Search") {}
                            }
                            Box(Modifier.weight(1f).focusRequester(screen).focusGroup()) { CatalogScreen(state,vm,entry) }
                        }
                    }
                }
                LaunchedEffect(Unit) { entry.requestFocus() }
            } }
        }
        compose.waitUntil(5000){compose.onAllNodesWithTag("catalog:row:TrendingMovies").fetchSemanticsNodes().isNotEmpty()}
        return repo
    }
    private fun remote(key:Int) { compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle() }
    private fun holdOk(key:Int=KeyEvent.KEYCODE_DPAD_CENTER) {
        compose.waitForIdle()
        val now=android.os.SystemClock.uptimeMillis()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,key,0))
        android.os.SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong()+150)
        instrumentation.sendKeySync(KeyEvent(now,android.os.SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,key,1,0,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_LONG_PRESS,android.view.InputDevice.SOURCE_DPAD))
        instrumentation.sendKeySync(KeyEvent(now,android.os.SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0))
        compose.waitForIdle()
        compose.onNodeWithTag("catalog:list-menu").assertExists()
        compose.onNodeWithTag("catalog:details").assertDoesNotExist()
    }
    private fun chooseList(list:CatalogUserList) {
        compose.onNodeWithTag("catalog:list-menu:${list.name}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("catalog:list-menu").fetchSemanticsNodes().isEmpty() }
    }
    private fun card(id:Int,type:String="movie",section:String="TrendingMovies") = compose.onNode(hasTestTag("catalog:card:$type:$id") and hasAnyAncestor(hasTestTag("catalog:row:$section")))
    @After fun clear() { compose.runOnUiThread { store.clear() } }
    @Test fun remoteHoldAddsIndependentListsAndShortClickStillOpensDetails() {
        setup();remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_DOWN)
        holdOk();compose.onNodeWithTag("catalog:list-menu:Favorites").assertTextEquals("Добавить в избранное")
        chooseList(CatalogUserList.Favorites);card(1).assertIsFocused()
        holdOk();compose.onNodeWithTag("catalog:list-menu:Favorites").assertTextEquals("Удалить из избранного")
        chooseList(CatalogUserList.WatchLater);card(1).assertIsFocused()
        holdOk();compose.onNodeWithTag("catalog:list-menu:WatchLater").assertTextEquals("Удалить из «Смотреть позже»")
        chooseList(CatalogUserList.Favorites);card(1).assertIsFocused()
        assertFalse(vm.state.value.userEntries.single().favorite);assertTrue(vm.state.value.userEntries.single().watchLater)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);card(1).assertIsFocused()
        holdOk();remote(KeyEvent.KEYCODE_BACK);card(1).assertIsFocused()
    }
    @Test fun savedListRemovalKeepsFocusAndPointerHoldDoesNotOpenDetails() {
        setup()
        card(1).performTouchInput { longClick() }
        compose.onNodeWithTag("catalog:list-menu").assertExists();compose.onNodeWithTag("catalog:details").assertDoesNotExist()
        chooseList(CatalogUserList.Favorites)
        compose.onNodeWithTag("catalog:list:Favorites").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        holdOk();chooseList(CatalogUserList.Favorites)
        compose.onNodeWithTag("catalog:card:movie:1").assertDoesNotExist()
        compose.onNodeWithTag("catalog:collection:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:list:Favorites").assertIsFocused()
        assertTrue(vm.state.value.userEntries.isEmpty())
    }
    @Test fun enterHoldOnTvSearchResultAddsToWatchLaterAndWorksInExpandedGrid() {
        setup();compose.runOnUiThread { vm.query("Series") }
        compose.waitUntil(5000) { vm.state.value.results.items.isNotEmpty() }
        compose.onNodeWithTag("catalog:card:tv:1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        holdOk(KeyEvent.KEYCODE_ENTER);chooseList(CatalogUserList.WatchLater)
        compose.onNodeWithTag("catalog:card:tv:1").assertIsFocused()
        compose.onNodeWithTag("catalog:list:WatchLater").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:card:tv:1").assertIsFocused()
        holdOk();chooseList(CatalogUserList.Favorites)
        assertEquals(CatalogType.Tv,vm.state.value.userEntries.single().ref.type)
        assertTrue(vm.state.value.userEntries.single().favorite);assertTrue(vm.state.value.userEntries.single().watchLater)
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:list:WatchLater").assertIsFocused()
        assertEquals("Series",vm.state.value.query)
    }
    @Test fun savedGridResolvesChangedLocaleEvenWhenFallbackTitleIsIdentical() {
        val lists=MemoryCatalogUserLists();val languages=MutableStateFlow("ru-RU")
        val ref=CatalogRef(1,CatalogType.Movie)
        runBlocking { lists.toggle(CatalogItem(ref,"Same title","Same title"),"ru-RU",CatalogUserList.Favorites) }
        val repo=setup(lists=lists,languages=languages)
        compose.onNodeWithTag("catalog:list:Favorites").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        assertTrue(repo.detailLocales.isEmpty())
        languages.value="en-US"
        compose.waitUntil(5000) { vm.state.value.userEntries.single().localized.containsKey("en-US") }
        assertEquals(listOf("en-US"),repo.detailLocales)
        compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER);remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        languages.value="ru-RU"
        compose.waitUntil(5000) { vm.state.value.language=="ru-RU" }
        assertEquals("Same title",vm.state.value.userEntries.single().item("ru-RU").title)
        assertEquals(2,repo.detailLocales.size)
    }
    @Test fun collectionCardShowsSixPostersAndOneRemoteTargetWithoutMoreText() {
        val colors=listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,
            android.graphics.Color.YELLOW,android.graphics.Color.CYAN,android.graphics.Color.MAGENTA)
        val files=colors.mapIndexed { index,color ->
            java.io.File(compose.activity.cacheDir,"collection-poster-$index.png").also { file ->
                val bitmap=android.graphics.Bitmap.createBitmap(20,30,android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
        var opened=0
        try {
            compose.runOnUiThread { compose.activity.setContent {
                TorrServeTheme(animationsEnabled=false) {
                    Box(Modifier.width(androidx.compose.ui.unit.Dp(116f)).height(androidx.compose.ui.unit.Dp(252f))) {
                        CatalogCollectionCard(files.mapIndexed { index,file -> CatalogItem(CatalogRef(index+1,CatalogType.Movie),"Title $index",poster=file.toURI().toString()) },
                            "Animation",Modifier.fillMaxSize().testTag("test:collection")) { opened++ }
                    }
                }
            } }
            for ((index,color) in colors.withIndex()) {
                val tile=compose.onNodeWithTag("collection:mini:$index",useUnmergedTree=true)
                compose.waitUntil(5000) {
                    val pixels=tile.captureToImage().toPixelMap()
                    val actual=pixels[pixels.width/2,pixels.height/2]
                    val expected=androidx.compose.ui.graphics.Color(color)
                    kotlin.math.abs(actual.red-expected.red)<.02f && kotlin.math.abs(actual.green-expected.green)<.02f && kotlin.math.abs(actual.blue-expected.blue)<.02f
                }
            }
            compose.onNodeWithText("More").assertDoesNotExist();compose.onNodeWithText("Больше").assertDoesNotExist()
            compose.onNodeWithTag("test:collection").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            compose.onAllNodes(isFocused()).assertCountEquals(1)
            remote(KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(1,opened)
        } finally { files.forEach { it.delete() } }
    }
    @Test fun previewsStopAtTenAndMoreOpensPaginatedGridWithFocusRestoration() {
        val repo=setup();val calls=repo.sectionCalls
        remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(9) { remote(KeyEvent.KEYCODE_DPAD_RIGHT) };card(10).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
        card(11).assertDoesNotExist();assertEquals(calls,repo.sectionCalls)
        assertEquals(1,vm.state.value.sections.getValue(CatalogSection.TrendingMovies).page)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:sections").assertDoesNotExist()
        compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("catalog:collection:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        compose.onNodeWithTag("catalog:collection:grid").performScrollToIndex(19)
        compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).page==2 }
        compose.onNodeWithTag("catalog:collection:grid").performScrollToNode(hasTestTag("catalog:card:movie:25"))
        compose.onNodeWithTag("catalog:card:movie:25").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        val before=compose.onNodeWithTag("catalog:card:movie:25").fetchSemanticsNode().boundsInRoot
        val loaded=repo.sectionCalls
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:card:movie:25").assertIsFocused()
        val after=compose.onNodeWithTag("catalog:card:movie:25").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top,after.top,2f);assertEquals(loaded,repo.sectionCalls)
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
        card(11).assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:collection:back").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
        assertEquals(loaded,repo.sectionCalls)
    }
    @Test fun genreTvMoreKeepsCategoryAndReturnsToItsOwnRow() {
        val repo=setup()
        compose.runOnUiThread { vm.filter(CatalogFilter.Comedy) }
        compose.waitUntil(5000) { vm.state.value.genreFeeds.getValue(CatalogType.Tv).items.isNotEmpty() }
        val calls=repo.discoveryCalls
        compose.onNodeWithTag("catalog:sections").performScrollToNode(hasTestTag("catalog:row:genre:tv"))
        compose.onNodeWithTag("catalog:row:genre:tv").performScrollToIndex(10)
        compose.onNodeWithTag("catalog:more:genre:tv").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        assertEquals(calls,repo.discoveryCalls)
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:card:tv:1").assertIsFocused()
        compose.onNodeWithText("Сериалы · Комедия").assertExists()
        compose.onNodeWithTag("catalog:card:movie:1").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_CENTER);remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("catalog:card:tv:1").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:more:genre:tv").assertIsFocused()
        assertEquals(CatalogFilter.Comedy,vm.state.value.filter)
    }
    @Test fun shortPreviewOpensWithoutExtraRequestsAndGridErrorPreservesItems() {
        val short=setup(Repository(count=3))
        compose.onNodeWithTag("catalog:row:TrendingMovies").performScrollToIndex(3)
        compose.onNodeWithTag("catalog:more:TrendingMovies").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        val calls=short.sectionCalls
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
        assertEquals(calls,short.sectionCalls)
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
        setup(Repository(failSecondPage=true))
        compose.onNodeWithTag("catalog:row:TrendingMovies").performScrollToIndex(10)
        compose.onNodeWithTag("catalog:more:TrendingMovies").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:collection:grid").performScrollToIndex(19)
        compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).error }
        assertEquals(20,vm.state.value.sections.getValue(CatalogSection.TrendingMovies).items.size)
        compose.onNodeWithTag("catalog:collection:back").assertExists()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:more:TrendingMovies").assertIsFocused()
    }
    @Test fun headerDownEntersMovieAndTvDetailsAndScrolledContent() {
        for (type in CatalogType.entries) {
            setup(header=true)
            compose.runOnUiThread { vm.open(CatalogRef(1,type)) }
            compose.onNodeWithTag("catalog:back").assertIsFocused()
            for (tab in listOf("Search", "Library", "Settings")) {
                compose.onNodeWithTag("nav:$tab").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithTag("catalog:back").assertIsFocused()
            }
            compose.onNodeWithTag("catalog:detail:scroll").performScrollToNode(hasText("Movie information and images from TMDB") or hasText("Информация и изображения предоставлены TMDB"))
            compose.onNodeWithTag("nav:Search").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("catalog:details"))).assertExists()
            remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("catalog:details").assertDoesNotExist()
        }
    }
    @Test fun dpadOpensMovieAndRestoresCardAndHorizontalPositionWithoutReloading() {
        val repo=setup();compose.onNodeWithTag("catalog:search").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("catalog:filter:All").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);card(1).assertIsFocused()
        repeat(7){remote(KeyEvent.KEYCODE_DPAD_RIGHT)};card(8).assertIsFocused()
        val before=card(8).fetchSemanticsNode().boundsInRoot;val calls=repo.sectionCalls
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:details").assertExists();compose.onNodeWithTag("catalog:back").assertIsFocused()
        compose.onNodeWithTag("catalog:detail:title").assertTextEquals("Details 8")
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        remote(KeyEvent.KEYCODE_BACK);card(8).assertIsFocused()
        val after=card(8).fetchSemanticsNode().boundsInRoot
        assertEquals(before.left,after.left,2f);assertEquals(before.top,after.top,2f);assertEquals(calls,repo.sectionCalls)
        compose.onNodeWithText("Watch").assertDoesNotExist();compose.onNodeWithText("Find Torrents").assertDoesNotExist()
    }
    @Test fun searchEditorUpdatesLiveAndTvDetailsReturnToQuery() {
        val repo=setup()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("dialog:editor").performTextReplacement("Интерстеллар")
        compose.waitUntil(5000){repo.searches>0};assertEquals("Интерстеллар",vm.state.value.query)
        compose.onNodeWithTag("dialog:editor").performTextReplacement("Series")
        compose.waitUntil(5000){vm.state.value.results.items.firstOrNull()?.ref?.type==CatalogType.Tv}
        remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:results").performScrollToNode(hasTestTag("catalog:card:tv:1"))
        compose.onNodeWithTag("catalog:card:tv:1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Сезонов: 3  ·  Эпизодов: 30").assertExists()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("catalog:card:tv:1").assertIsFocused()
        assertEquals("Series",vm.state.value.query)
    }
    @Test fun focusAndCardsFitLightThemeAt110AndDarkThemeAt70() {
        for((scale,light) in listOf(110 to true,70 to false)) {
            setup(scale=scale,light=light);remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_DOWN);card(1).assertIsFocused()
            val screen=compose.onNodeWithTag("catalog:screen").fetchSemanticsNode().boundsInRoot
            val bounds=card(1).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left>=screen.left);assertTrue(bounds.right<=screen.right);assertTrue(bounds.bottom<=screen.bottom)
            remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:back").assertIsFocused()
            remote(KeyEvent.KEYCODE_BACK);card(1).assertIsFocused()
        }
    }
    @Test fun filterButtonsSwitchCollectionsAndGenreDetailsRestorePositionWithoutReloading() {
        val repo=setup()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("catalog:filter:All").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:filter:Movies").assertIsSelected()
        compose.onNodeWithTag("catalog:row:TrendingMovies").assertExists()
        compose.onNodeWithTag("catalog:row:PopularTv").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT);remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:filter:Series").assertIsSelected()
        compose.onNodeWithTag("catalog:row:PopularTv").assertExists()
        compose.onNodeWithTag("catalog:row:TrendingMovies").assertDoesNotExist()
        compose.onNodeWithTag("catalog:filters").performScrollToNode(hasTestTag("catalog:filter:Comedy"))
        compose.onNodeWithTag("catalog:filter:Comedy").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("catalog:filter:Comedy").assertIsFocused().assertIsSelected()
        compose.waitUntil(5000) { vm.state.value.genreFeeds.getValue(CatalogType.Movie).items.isNotEmpty() }
        val selected=compose.onNode(hasTestTag("catalog:card:movie:8") and hasAnyAncestor(hasTestTag("catalog:row:genre:movie")))
        remote(KeyEvent.KEYCODE_DPAD_DOWN);repeat(7) { remote(KeyEvent.KEYCODE_DPAD_RIGHT) };selected.assertIsFocused()
        val before=selected.fetchSemanticsNode().boundsInRoot;val calls=repo.discoveryCalls
        remote(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("catalog:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);selected.assertIsFocused()
        val after=selected.fetchSemanticsNode().boundsInRoot
        assertEquals(before.left,after.left,2f);assertEquals(calls,repo.discoveryCalls)
        assertEquals(CatalogFilter.Comedy,vm.state.value.filter)
        remote(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("catalog:filter:Comedy").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("catalog:search").assertIsFocused()
    }
    @Test fun genreSelectionLeavesTextSearchAndReturnsToCollection() {
        setup();compose.runOnUiThread { vm.query("Series") }
        compose.waitUntil(5000) { vm.state.value.results.items.isNotEmpty() }
        compose.onNodeWithTag("catalog:filters").performScrollToNode(hasTestTag("catalog:filter:Horror"))
        compose.onNodeWithTag("catalog:filter:Horror").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { vm.state.value.genreFeeds.getValue(CatalogType.Movie).items.isNotEmpty() }
        assertEquals("",vm.state.value.query)
        compose.onNodeWithTag("catalog:results").assertDoesNotExist()
        compose.onNodeWithTag("catalog:row:genre:movie").assertExists()
        compose.onNodeWithTag("catalog:row:genre:tv").assertDoesNotExist()
        compose.onNodeWithTag("catalog:filter:Horror").assertIsSelected().assertIsFocused()
    }
    @Test fun browsingOffscreenGenreButtonsWithoutSelectingRetainsRemoteNavigation() {
        setup()
        compose.onNodeWithTag("catalog:filters").performScrollToNode(hasTestTag("catalog:filter:Romance"))
        compose.onNodeWithTag("catalog:filter:Romance").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        assertEquals(CatalogFilter.All,vm.state.value.filter)
        remote(KeyEvent.KEYCODE_DPAD_DOWN);card(1).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("catalog:filter:Romance").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("catalog:search").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("catalog:filter:Romance").assertIsFocused()
    }
    @Test fun actualAppearanceLanguageControlsCatalogWhileMetadataLanguageStaysSeparate() {
        val graph=(compose.activity.application as TorrServeApplication).graph
        runBlocking { graph.ready.await() };val original=graph.settings.state.value
        fun chooseLanguage(value:String) {
            compose.onNodeWithTag("nav:Settings").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:Appearance"))
            compose.onNodeWithTag("settings:category:Appearance").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("settings:detail:Appearance").performScrollToNode(hasText(value))
            compose.onNodeWithText(value).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
        }
        try {
            runBlocking { graph.settings.update { it.copy(uiLanguage="en",language="ru-RU") } }
            compose.onNodeWithTag("nav:Search").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            val catalog=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${CatalogViewModel::class.java.name}") as CatalogViewModel
            compose.waitUntil(5000) { catalog.state.value.language=="en-US" }
            compose.runOnUiThread { catalog.query("Interstellar") }
            compose.waitUntil(15_000) { catalog.state.value.results.items.any { it.ref.id==157336 } }
            assertEquals("Interstellar",catalog.state.value.results.items.first { it.ref.id==157336 }.title)
            compose.onNodeWithTag("catalog:card:movie:157336").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(15_000) { catalog.state.value.details.data!=null }
            val english=catalog.state.value.details.data!!.overview
            for (language in listOf("ru","en","ru")) {
                chooseLanguage(language)
                val locale=if(language=="ru")"ru-RU" else "en-US"
                val title=if(language=="ru")"Интерстеллар" else "Interstellar"
                compose.waitUntil(15_000) { catalog.state.value.language==locale && catalog.state.value.details.data?.item?.title==title }
                compose.onNodeWithTag("catalog:detail:title").assertTextEquals(title)
                assertEquals("Interstellar",catalog.state.value.query)
                val overview=catalog.state.value.details.data!!.overview
                if(language=="ru") { assertNotEquals(english,overview);assertFalse(catalog.state.value.details.data!!.englishOverview) }
                else assertEquals(english,overview)
                assertEquals("ru-RU",graph.settings.state.value.language)
            }
            remote(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) { catalog.state.value.results.items.any { it.ref.id==157336 } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("catalog:card:movie:157336").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused)==true } }
            compose.onNodeWithTag("catalog:card:movie:157336").assertIsFocused()
            assertEquals("Интерстеллар",catalog.state.value.results.items.first { it.ref.id==157336 }.title)
        } finally { runBlocking { graph.settings.save(original) } }
    }
    @Test fun diskCacheSurvivesReopenExpiresOnlyAtRetentionAndDoesNotTouchLibrary()=runBlocking {
        val context=compose.activity;val name="catalog-test-${System.nanoTime()}.sqlite";var clock=1000L
        val before=(context.application as TorrServeApplication).graph.store.list()
        try {
            SqliteCatalogCache(context,name){clock}.use { it.write("key","{}",100,300) }
            clock+=150
            SqliteCatalogCache(context,name){clock}.use { assertNotNull(it.read("key"));assertTrue(it.read("key")!!.expiresAt<clock) }
            clock+=200
            SqliteCatalogCache(context,name){clock}.use { assertNull(it.read("key")) }
            assertEquals(before,(context.application as TorrServeApplication).graph.store.list())
        } finally { context.deleteDatabase(name) }
    }
}
