package tv.torrframe

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import tv.torrframe.features.catalog.domain.*
import tv.torrframe.features.catalog.presentation.*
import tv.torrframe.ui.components.LocalUiLanguage
import tv.torrframe.ui.theme.TorrFrameTheme

@RunWith(AndroidJUnit4::class)
class CatalogRetryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private lateinit var vm: CatalogViewModel
    private class Repository : CatalogRepository {
        var failed = true
        var gate: CompletableDeferred<Unit>? = null
        private fun load(page: Int) = flow {
            emit(CatalogLoad<CatalogPage>(loading = true))
            gate?.await()
            emit(if (failed) CatalogLoad(error = true) else CatalogLoad(CatalogPage((1..12).map {
                CatalogItem(CatalogRef((page - 1) * 12 + it, CatalogType.Movie), "Movie $it")
            }, page, 1)))
        }
        override fun section(section: CatalogSection, language: String, page: Int, force: Boolean) = load(page)
        override fun search(query: String, language: String, page: Int, force: Boolean) = load(page)
        override fun discover(filter: CatalogFilter, type: CatalogType, language: String, page: Int, force: Boolean) = load(page)
        override fun related(ref: CatalogRef, source: CatalogRelatedSource, language: String, page: Int, force: Boolean) = load(page)
        override fun details(ref: CatalogRef, language: String, force: Boolean) = flowOf(CatalogLoad(
            CatalogDetails(CatalogItem(ref, "Details"), "Synopsis", emptyList(), null, emptyList(), emptyList())))
    }
    private fun setup(scale: Int = 85): Repository {
        val repo = Repository()
        compose.runOnUiThread {
            models.clear()
            vm = CatalogViewModel(repo, MutableStateFlow("en-US"), SavedStateHandle())
            models.put("retry", vm)
            compose.activity.setContent {
                val state by vm.state.collectAsState()
                val entry = remember { FocusRequester() }
                TorrFrameTheme(scale, animationsEnabled = false) {
                    CompositionLocalProvider(LocalUiLanguage provides "en") {
                        CatalogScreen(state, vm, entry, showFavorites = false, showWatchLater = false)
                    }
                }
                LaunchedEffect(Unit) { entry.requestFocus() }
            }
        }
        compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).error }
        return repo
    }
    private fun remote(key: Int) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
    private fun retry() {
        compose.onAllNodesWithText("Retry").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun focusedPoster() = compose.onNode(isFocused() and hasTestTag("catalog:card:movie:1"))
    @After fun clear() { compose.runOnUiThread { models.clear() } }

    @Test fun successfulShelfRetryRestoresPosterAndAllRemoteDirectionsAtBothScales() {
        for (scale in listOf(70, 110)) {
            val repo = setup(scale)
            repo.failed = false; repo.gate = CompletableDeferred()
            retry()
            compose.onNodeWithTag("catalog:retry:TrendingMovies").assertIsFocused()
            repo.gate!!.complete(Unit)
            compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).items.isNotEmpty() }
            focusedPoster().assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(isFocused() and hasTestTag("catalog:card:movie:2")).assertExists()
            remote(KeyEvent.KEYCODE_DPAD_LEFT); focusedPoster().assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("catalog:filter:All").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_DOWN); focusedPoster().assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("catalog:details").assertExists()
            remote(KeyEvent.KEYCODE_BACK); focusedPoster().assertIsFocused()
        }
    }
    @Test fun searchRetryRestoresGridAndDoesNotStealFocusAfterUserLeaves() {
        val repo = setup()
        compose.runOnUiThread { vm.query("recover") }
        compose.waitUntil(5000) { vm.state.value.results.error }
        repo.failed = false; repo.gate = CompletableDeferred()
        retry(); compose.onNodeWithTag("catalog:retry:results").assertIsFocused(); repo.gate!!.complete(Unit)
        compose.waitUntil(5000) { vm.state.value.results.items.isNotEmpty() }
        focusedPoster().assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("catalog:card:movie:2").assertIsFocused()
        // Recreate the failure, then leave its retry while the request is in flight.
        repo.failed = true; repo.gate = null
        compose.runOnUiThread { vm.query("another") }
        compose.waitUntil(5000) { vm.state.value.results.error }
        repo.failed = false; repo.gate = CompletableDeferred()
        retry()
        compose.onNodeWithTag("catalog:search").performSemanticsAction(SemanticsActions.RequestFocus)
        repo.gate!!.complete(Unit)
        compose.waitUntil(5000) { !vm.state.value.results.loading && vm.state.value.results.items.isNotEmpty() }
        compose.onNodeWithTag("catalog:search").assertIsFocused()
    }
    @Test fun collectionRetryRestoresGridWithAndWithoutCachedCards() {
        for (cached in listOf(false, true)) {
            val items=(1..12).map { CatalogItem(CatalogRef(it,CatalogType.Movie),"Movie $it") }
            val feed=mutableStateOf(CatalogFeed(items=if(cached)items else emptyList(),loading=false,error=true))
            compose.runOnUiThread { compose.activity.setContent {
                val grid=rememberLazyGridState();val back=remember { FocusRequester() };val restore=remember { FocusRequester() }
                TorrFrameTheme(animationsEnabled=false) { CompositionLocalProvider(LocalUiLanguage provides "en") {
                    CatalogCollectionScreen("Collection",feed.value,grid,120.dp,back,null,restore,false,{},
                        retry={feed.value=feed.value.copy(error=false,loading=!cached,loadingMore=cached)},
                        more={},open={},focused={},held={_,_->})
                } }
            } }
            compose.onNodeWithTag("catalog:retry:collection").performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("catalog:retry:collection").assertIsFocused()
            compose.runOnIdle { feed.value=CatalogFeed(items=items,page=1,totalPages=1,loading=false) }
            compose.onNodeWithTag("catalog:card:movie:1").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("catalog:card:movie:2").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_LEFT);remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("catalog:collection:back").assertIsFocused()
        }
    }
    @Test fun failedRetryKeepsTheButtonUsableForTheNextAttempt() {
        val repo=setup()
        retry() // still failing; an immediate response must not leave a permanently busy control.
        compose.onNodeWithTag("catalog:retry:TrendingMovies").assertIsFocused().assertTextEquals("Retry")
        repo.failed=false; retry()
        compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).items.isNotEmpty() }
        focusedPoster().assertIsFocused()
    }
    @Test fun scrolledRetryCannotLeaveHeaderPointingAtAnUncomposedPoster() {
        val repo = setup()
        repo.failed = false; retry()
        compose.waitUntil(5000) { vm.state.value.sections.getValue(CatalogSection.TrendingMovies).items.isNotEmpty() }
        compose.onNodeWithTag("catalog:sections").performScrollToIndex(4)
        compose.onNodeWithTag("catalog:filter:All").performSemanticsAction(SemanticsActions.RequestFocus)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("catalog:sections"))).assertExists()
    }
}
