package tv.torrframe

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrframe.core.models.*
import tv.torrframe.features.library.*
import tv.torrframe.ui.components.AppLanguage
import tv.torrframe.ui.theme.TorrFrameTheme

@RunWith(AndroidJUnit4::class)
class LibraryDetailNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private var played: TorrentFile? = null
    private var searched = false
    private var jackettSearched = false
    private val jackettEnabled = mutableStateOf(true)
    private val item = LibraryItem("f".repeat(40), "TV fixture", 0,
        files = listOf(TorrentFile(1, "TV.Fixture.S01E01.mkv", 1000), TorrentFile(2, "TV.Fixture.S01E02.mkv", 1000), TorrentFile(3, "info.nfo", 10)))
    private val displayedItem = mutableStateOf(item)
    private val light = mutableStateOf(false)
    private val scale = mutableIntStateOf(85)
    private val language = mutableStateOf("en")
    private val images = mutableListOf<java.io.File>()
    @Before fun setup() = runBlocking {
        val graph = (compose.activity.application as TorrFrameApplication).graph
        graph.ready.await()
        val vm = LibraryViewModel(graph); models.put("detail", vm)
        compose.activity.setContent {
            TorrFrameTheme(light = light.value, uiScalePercent = scale.intValue) { AppLanguage(language.value) {
                MovieDetailScreen(displayedItem.value, LibraryUi(), vm, {}, torrentSearch = { searched = true },
                    jackettEnabled = jackettEnabled.value, jackettSearch = { jackettSearched = true }) { hash, file ->
                    assertEquals(item.hash, hash); played = file
                }
            } }
        }
        compose.waitForIdle()
    }
    @After fun teardown() { models.clear(); images.forEach { it.delete() } }
    private fun remote(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focus(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); compose.waitForIdle() }
    @Test fun actionOrderIconsAndInlineFilesWorkWithDpad() {
        val tags = listOf("movie:watch", "movie:remove", "movie:metadata", "movie:torrentsearch", "movie:jackett")
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (a,b) -> assertTrue(a.right < b.left); assertEquals(a.center.y,b.center.y,1f) }
        compose.onNodeWithTag("movie:watch").assertContentDescriptionEquals("Watch")
        compose.onNodeWithTag("movie:remove").assertContentDescriptionEquals("Remove")
        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("Files").assertHasNoClickAction()
        focus("movie:watch")
        tags.drop(1).forEach { tag -> remote(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag(tag).assertIsFocused() }
        // Jackett invokes the real search entry point without opening a placeholder.
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(jackettSearched)
        compose.onNodeWithTag("movie:jackett:placeholder").assertDoesNotExist()
        compose.onNodeWithTag("movie:jackett").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_LEFT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(searched)
    }
    @Test fun disabledJackettIsAbsentWithoutBreakingSearchFocus() {
        focus("movie:jackett")
        compose.runOnIdle { jackettEnabled.value = false }
        compose.onNodeWithTag("movie:jackett").assertDoesNotExist()
        compose.onNodeWithTag("movie:torrentsearch").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(searched); assertFalse(jackettSearched)
    }
    @Test fun playWithMultipleVideosFocusesInlineListAndSelectingFileStartsIt() {
        focus("movie:watch"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("movie:file:1") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertNull(played)
        compose.onNodeWithTag("movie:file:3").assertDoesNotExist()
        remote(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("movie:file:2").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(2, played?.id)
    }
    @Test fun cancellingRemovalKeepsTitleAndRestoresTrashFocus() {
        focus("movie:remove"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Remove from library?").assertExists()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("movie:remove").assertIsFocused()
        compose.onNodeWithTag("movie:title").assertTextEquals(item.title)
    }
    private fun withBackdrop(): LibraryItem {
        val file = java.io.File(compose.activity.cacheDir, "library-hero-${System.nanoTime()}.png")
        images += file
        val bitmap = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return item.copy(metadata = Metadata("TMDB", "fixture", "Movie title", "Original", 2024, MediaKind.Movie,
            overview = "A complete description that must appear only once.", poster = file.toURI().toString(),
            backdrop = file.toURI().toString(), rating = 8.1, genres = "Drama", runtime = 110))
    }
    private fun showBackdrop(value: LibraryItem = withBackdrop()) {
        compose.runOnIdle { displayedItem.value = value }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("movie:backdrop:loaded").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun cinematicHeaderContainsBackTextAndActionsAndFilesStayBelow() {
        showBackdrop()
        compose.onNodeWithTag("movie:poster").assertDoesNotExist()
        val hero = compose.onNodeWithTag("movie:hero").fetchSemanticsNode().boundsInRoot
        listOf("movie:back", "movie:title", "movie:overview", "movie:actions").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is inside the hero", bounds.left >= hero.left && bounds.right <= hero.right && bounds.bottom <= hero.bottom)
        }
        compose.onAllNodesWithTag("movie:overview").assertCountEquals(1)
        compose.onNodeWithTag("movie:files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("movie:file:1").performScrollTo().assertIsDisplayed()
        focus("movie:back"); remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("movie:watch").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("movie:file:1") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun failedBackdropFallsBackToPosterAndKeepsRemoteFocus() {
        val movie = withBackdrop()
        showBackdrop(movie)
        focus("movie:torrentsearch")
        compose.runOnIdle { displayedItem.value = movie.copy(metadata = movie.metadata!!.copy(backdrop = "file:///missing-library-backdrop.png")) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("movie:poster:loaded").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("movie:backdrop:loading").assertDoesNotExist()
        compose.onNodeWithTag("movie:torrentsearch").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(searched)
        compose.onAllNodesWithTag("movie:overview").assertCountEquals(1)
    }
    @Test fun largeRussianLightThemeKeepsActionsInsideHeaderAndFilePlaybackWorks() {
        compose.runOnIdle { light.value = true; scale.intValue = 110; language.value = "ru" }
        showBackdrop(withBackdrop().let { it.copy(metadata = it.metadata!!.copy(title = "Очень длинное название фильма для проверки масштаба интерфейса")) })
        compose.onNodeWithText("В вашей медиатеке").assertExists()
        val hero = compose.onNodeWithTag("movie:hero").fetchSemanticsNode().boundsInRoot
        listOf("movie:watch", "movie:remove", "movie:metadata", "movie:torrentsearch", "movie:jackett").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fits horizontally", bounds.left >= hero.left && bounds.right <= hero.right)
        }
        focus("movie:watch"); remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("movie:remove").assertIsFocused()
        focus("movie:watch"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("movie:file:1") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, played?.id)
    }
}
