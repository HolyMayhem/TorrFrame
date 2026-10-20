package tv.torrframe

import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import tv.torrframe.core.models.LibraryItem
import tv.torrframe.features.library.LibraryScreen
import tv.torrframe.features.library.LibraryUi
import tv.torrframe.ui.components.AppLanguage
import tv.torrframe.ui.theme.TorrFrameTheme

@RunWith(AndroidJUnit4::class)
class LibraryOrderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun libraryHasNoSortControlsAndNewestAdditionComesFirst() {
        compose.activity.setContent {
            TorrFrameTheme { AppLanguage("en") { Box(Modifier.fillMaxSize()) {
                LibraryScreen(LibraryUi(items = listOf(LibraryItem("old", "A Older", 1), LibraryItem("new", "Z Newer", 2))), {}, {}) {}
            } } }
        }
        compose.onNodeWithText("Recently Added").assertDoesNotExist()
        compose.onNodeWithText("Title").assertDoesNotExist()
        compose.onNodeWithText("Year").assertDoesNotExist()
        val old = compose.onNodeWithText("A Older").fetchSemanticsNode().boundsInRoot
        val newer = compose.onNodeWithText("Z Newer").fetchSemanticsNode().boundsInRoot
        assertTrue(newer.left < old.left)
    }
}
