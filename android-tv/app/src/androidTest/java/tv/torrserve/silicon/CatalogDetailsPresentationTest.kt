package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.presentation.*
import tv.torrserve.silicon.ui.components.LocalUiLanguage
import tv.torrserve.silicon.ui.theme.TorrServeTheme

@RunWith(AndroidJUnit4::class)
class CatalogDetailsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var backCalls = 0
    private fun details(backdrop: String = "", type: CatalogType = CatalogType.Movie) = CatalogDetails(
        CatalogItem(CatalogRef(157336, type), "Интерстеллар", "Interstellar", backdrop = backdrop,
            date = "2014-11-05", rating = 8.6, votes = 12345),
        "Полное описание фильма. ".repeat(25) + "Конец описания.", listOf("Драма", "Фантастика"), 169,
        listOf("Кристофер Нолан"), listOf("Мэттью Макконахи", "Энн Хэтэуэй"),
        if (type == CatalogType.Tv) 3 else null, if (type == CatalogType.Tv) 30 else null,
        if (type == CatalogType.Tv) "Returning Series" else "", "2026-01-02")

    private fun setup(value: CatalogDetails, scale: Int = 85, light: Boolean = false, language: String = "ru"): MutableState<CatalogDetails> {
        val data = mutableStateOf(value)
        compose.runOnUiThread {
            compose.activity.setContent {
                TorrServeTheme(scale, light, animationsEnabled = false) {
                    CompositionLocalProvider(LocalUiLanguage provides language) {
                        BackHandler { backCalls++ }
                        // Match the bounded viewport supplied by the application's main content area.
                        Box(Modifier.fillMaxSize()) {
                            CatalogDetailsScreen(CatalogUi(selected = data.value.item.ref,
                                details = CatalogLoad(data.value)), back = { backCalls++ }, retry = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("catalog:back").assertIsFocused()
        return data
    }
    private fun remote(key: Int) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
    private fun waitForPosterLayout() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("catalog:detail:posterLayout").fetchSemanticsNodes().isNotEmpty()
    }
    private fun image(): File = File(compose.activity.cacheDir, "detail-backdrop-${System.nanoTime()}.png").also { file ->
        compose.activity.resources.openRawResource(R.drawable.brand_mark).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private fun textLayout(tag: String): TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun baseline(tag: String, last: Boolean): Float {
        val result=textLayout(tag)
        val top=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        return top + if(last) result.lastBaseline else result.firstBaseline
    }

    @Test fun missingBackdropUsesLargePosterAndFullSynopsisWithRemoteFocus() {
        val value = details()
        setup(value)
        compose.onNodeWithTag("catalog:detail:posterLayout").assertExists()
        compose.onNodeWithTag("catalog:detail:cinematic").assertDoesNotExist()
        compose.onNodeWithTag("catalog:detail:fullOverview").assertTextEquals(value.overview)
        compose.onAllNodesWithText(value.overview).assertCountEquals(1)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("catalog:detail:overview").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, backCalls)
    }

    @Test fun validBackdropUsesCinemaAndChangingTitleResetsImageFailure() {
        val file = image()
        try {
            val data = setup(details("file:///missing-backdrop-${System.nanoTime()}.jpg"))
            waitForPosterLayout()
            compose.onNodeWithTag("catalog:back").assertIsFocused()
            compose.runOnUiThread { data.value = details(file.toURI().toString()).let {
                it.copy(item = it.item.copy(ref = CatalogRef(999, CatalogType.Movie)))
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("catalog:detail:cinematic").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithTag("catalog:detail:posterLayout").assertDoesNotExist()
            compose.onNodeWithTag("catalog:detail:preview").assertDoesNotExist()
            compose.onNodeWithText("↓  Полное описание и актёры").assertDoesNotExist()
            compose.onNodeWithTag("catalog:back").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("catalog:detail:overview").assertIsFocused()
            compose.onNodeWithTag("catalog:detail:fullOverview").assertTextEquals(data.value.overview)
            compose.onAllNodesWithText(data.value.overview).assertCountEquals(1)
            remote(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
        } finally { file.delete() }
    }

    @Test fun delayedImageErrorSwitchesToPosterAndKeepsFocusedHeading() {
        ServerSocket(0).use { server ->
            val requested = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = thread(name = "failed-catalog-image", isDaemon = true) {
                runCatching { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrBlank()) { }
                    requested.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } }
            }
            try {
                setup(details("http://127.0.0.1:${server.localPort}/missing.jpg"))
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
                release.countDown()
                waitForPosterLayout()
                compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
                remote(KeyEvent.KEYCODE_DPAD_UP)
                compose.onNodeWithTag("catalog:back").assertIsFocused()
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(1, backCalls)
            } finally { release.countDown(); worker.join(1000) }
        }
    }

    @Test fun fallbackRetainsTvMetadataAndFitsBothThemeScaleExtremes() {
        for ((scale, light) in listOf(110 to true, 70 to false)) {
            setup(details(type = CatalogType.Tv), scale, light)
            val screen = compose.onNodeWithTag("catalog:details").fetchSemanticsNode().boundsInRoot
            val poster = compose.onNodeWithTag("catalog:detail:poster").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("catalog:detail:title").fetchSemanticsNode().boundsInRoot
            assertTrue(poster.right < title.left)
            assertTrue(poster.left >= screen.left && title.right <= screen.right)
            compose.onNodeWithTag("catalog:detail:scroll").performScrollToNode(hasTestTag("catalog:detail:credits"))
            compose.onNodeWithText("Сезонов: 3  ·  Эпизодов: 30").assertExists()
            compose.onNodeWithText("Статус: Продолжается").assertExists()
            compose.onNodeWithText("Последний выпуск: 2 января 2026").assertExists()
        }
    }

    @Test fun cinemaBackIsInsideBannerTitleIsAtBottomAndLargerPosterOverlapsOnlyTheEdge() {
        val file = image()
        try {
            for ((scale, light) in listOf(110 to true, 70 to false)) {
                setup(details(file.toURI().toString()), scale, light)
                val screen = compose.onNodeWithTag("catalog:details").fetchSemanticsNode().boundsInRoot
                val banner = compose.onNodeWithTag("catalog:detail:cinematic").fetchSemanticsNode().boundsInRoot
                val button = compose.onNodeWithTag("catalog:back").fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("catalog:detail:title").fetchSemanticsNode().boundsInRoot
                val poster = compose.onNodeWithTag("catalog:detail:poster").fetchSemanticsNode().boundsInRoot
                assertTrue("Banner starts directly below the main navigation", banner.top - screen.top < screen.height * .03f)
                assertTrue("Back is on the banner", button.top >= banner.top && button.bottom < banner.bottom)
                assertTrue("Title is at the bottom left", title.center.y > banner.center.y && title.left < banner.center.x)
                assertTrue("Title stays clear of the poster", title.right < poster.left)
                assertTrue("Poster is larger: poster=$poster banner=$banner scale=$scale", poster.width / banner.width >= .18f)
                assertTrue("Poster hangs below the banner", poster.top < banner.bottom && poster.bottom > banner.bottom)
                assertTrue("Most of the banner remains uncovered", (banner.bottom - poster.top) * poster.width < banner.width * banner.height * .2f)
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithTag("catalog:detail:hero").assertIsFocused()
                remote(KeyEvent.KEYCODE_DPAD_UP)
                compose.onNodeWithTag("catalog:back").assertIsFocused()
            }
        } finally { file.delete() }
    }

    @Test fun accentValuesAndCaptionsAlignForWrappedTextBothLanguagesAndScaleExtremes() {
        val file=image()
        try {
            for ((scale,light,language) in listOf(Triple(110,true,"ru"),Triple(70,false,"en"))) {
                val value=details(file.toURI().toString()).copy(
                    directors=listOf("Кристофер Нолан", "Очень длинное имя второго режиссёра"),
                    genres=listOf("Приключения", "Драма", "Научная фантастика", "Мистический триллер"))
                setup(value,scale,light,language)
                compose.onNodeWithTag("catalog:detail:stats").performScrollTo()
                for (keys in listOf(listOf("rating","director","premiere"),listOf("runtime","genres","original"))) {
                    val values=keys.map { baseline("catalog:stat:$it:value",last=true) }
                    val captions=keys.map { baseline("catalog:stat:$it:label",last=false) }
                    keys.forEachIndexed { index,key ->
                        assertEquals("Value baseline for $key",values.first(),values[index],2f)
                        assertEquals("Caption baseline for $key",captions.first(),captions[index],2f)
                        assertEquals("Value-to-caption interval for $key",captions.first()-values.first(),captions[index]-values[index],2f)
                        assertEquals(14.sp,textLayout("catalog:stat:$key:label").layoutInput.style.fontSize)
                    }
                }
                compose.onAllNodesWithText(value.item.originalTitle).assertCountEquals(1)
                compose.onNodeWithTag("catalog:stat:director:value").assertTextEquals(value.directors.joinToString(", "))
                compose.onNodeWithTag("catalog:stat:genres:value").assertTextEquals(value.genres.joinToString(" · "))
                val stats=compose.onNodeWithTag("catalog:detail:stats").fetchSemanticsNode().boundsInRoot
                val poster=compose.onNodeWithTag("catalog:detail:poster").fetchSemanticsNode().boundsInRoot
                assertTrue("Information stays left of the poster",stats.right < poster.left)
            }
        } finally { file.delete() }
    }
    @Test fun missingOptionalMetadataDoesNotCreateEmptyCaptionsOrDuplicateSynopsis() {
        val file=image()
        try {
            val base=details(file.toURI().toString())
            val value=base.copy(item=base.item.copy(originalTitle="",date="",votes=0),runtime=null,genres=emptyList(),directors=emptyList())
            setup(value)
            for(key in listOf("rating","director","premiere","runtime","genres","original"))
                compose.onNodeWithTag("catalog:stat:$key:label").assertDoesNotExist()
            remote(KeyEvent.KEYCODE_DPAD_DOWN);remote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("catalog:detail:overview").assertIsFocused()
            compose.onAllNodesWithText(value.overview).assertCountEquals(1)
        } finally { file.delete() }
    }
}
