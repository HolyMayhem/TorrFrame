package tv.torrframe

import android.content.Context
import android.content.ContextWrapper
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
import tv.torrframe.core.network.*
import tv.torrframe.core.storage.Settings
import tv.torrframe.core.storage.SettingsStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CatalogNetworkSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as TorrFrameApplication).graph
    private lateinit var original: Settings
    @Before fun setup() = runBlocking {
        graph.ready.await(); original = graph.settings.state.value
        graph.settings.update { it.copy(uiLanguage = "en", catalogNetwork = CatalogNetworkSettings()) }
    }
    @After fun restore() = runBlocking { graph.settings.save(original) }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    @Test fun catalogSourcesAndHomeRowsCanBeChangedByDpadWithoutCredentials() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav:Settings").fetchSemanticsNodes().isNotEmpty() }
        click("nav:Settings")
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:Catalog"))
        click("settings:category:Catalog")
        val detail = compose.onNodeWithTag("settings:detail:Catalog")
        click("catalog:source:Cub")
        compose.waitUntil(5000) { graph.settings.state.value.catalogNetwork.source == CatalogSource.Cub }
        assertTrue(graph.settings.state.value.catalogNetwork.cubToken.isBlank())
        click("catalog:source:Auto")
        compose.waitUntil(5000) { graph.settings.state.value.catalogNetwork.source == CatalogSource.Auto }
        detail.performScrollToNode(hasTestTag("catalog:home:Favorites")); click("catalog:home:Favorites")
        compose.waitUntil(5000) { !graph.settings.state.value.catalogNetwork.showFavorites }
        detail.performScrollToNode(hasTestTag("catalog:home:WatchLater")); click("catalog:home:WatchLater")
        compose.waitUntil(5000) { !graph.settings.state.value.catalogNetwork.showWatchLater }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings:category:Catalog").assertIsFocused()
    }

    @Test fun cubTokenIsEncryptedAndAllConnectionPreferencesSurviveReopeningStorage() = runBlocking {
        val app = compose.activity.applicationContext
        val name = "catalog-network-test-" + UUID.randomUUID()
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = app.getSharedPreferences(name, mode)
        }
        try {
            val network = CatalogNetworkSettings(CatalogRoute.Auto, "https://proxy.example/3/", listOf("https://image.example/"),
                CatalogSource.Auto, "https://catalog.example/", "test-issued-private-token", showFavorites = false, showWatchLater = false)
            SettingsStore(isolated).save(Settings(catalogNetwork = network))
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse(prefs.getString("catalogNetwork", "").orEmpty().contains(network.cubToken))
            assertNotEquals(network.cubToken, prefs.getString("cubToken", ""))
            assertFalse(prefs.all.values.any { it.toString().contains(network.cubToken) })
            val reopened = SettingsStore(isolated); reopened.load()
            assertEquals(network, reopened.state.value.catalogNetwork)
        } finally { app.deleteSharedPreferences(name) }
    }
}
