package tv.torrserve.silicon

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.focus.FocusRequester
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrserve.silicon.core.localization.uiString
import tv.torrserve.silicon.core.models.*
import tv.torrserve.silicon.core.network.ServiceFailure
import tv.torrserve.silicon.core.server.*
import tv.torrserve.silicon.features.developer.DeveloperState
import tv.torrserve.silicon.features.developer.DeveloperControls
import tv.torrserve.silicon.features.catalog.domain.*
import tv.torrserve.silicon.features.catalog.presentation.*
import tv.torrserve.silicon.features.library.*
import tv.torrserve.silicon.features.metadata.BuiltInMetadataKeys
import tv.torrserve.silicon.ui.components.*
import tv.torrserve.silicon.ui.theme.TorrFrameTheme

@RunWith(AndroidJUnit4::class)
class DeveloperMenuNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=(compose.activity.application as TorrFrameApplication).graph
    private var language="en"
    private val hash="d".repeat(40)
    @Before fun defaults()=runBlocking {
        graph.ready.await();language=graph.settings.state.value.uiLanguage
        graph.developer.reset();graph.developer.showDiagnostics(false);graph.settings.update{it.copy(uiLanguage="en")}
    }
    @After fun restore()=runBlocking {
        graph.developer.reset();graph.developer.showDiagnostics(false);graph.store.remove(hash);graph.settings.update{it.copy(uiLanguage=language)}
    }
    private fun remote(key:Int){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle()}
    private fun click(tag:String){compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER)}
    private fun open() {
        click("nav:Settings")
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:Developer"))
        click("settings:category:Developer");compose.onNodeWithTag("settings:back").assertIsFocused()
    }
    private fun choose(tag:String) {
        compose.onNodeWithTag("settings:detail:Developer").performScrollToNode(hasTestTag(tag));click(tag)
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    @Test fun diagnosticsToggleShowsLiveMetricsAndDnsWithoutTakingDpadFocus() {
        compose.onNodeWithTag("developer:diagnostics").assertDoesNotExist()
        open();choose("developer:diagnostics-toggle")
        assertTrue(graph.developer.diagnosticsVisible.value)
        assertFalse(graph.developer.state.value.active)
        graph.networkDiagnostics.failure("diagnostic.example", java.net.UnknownHostException("api_key=private"))
        try {
            remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("developer:diagnostics").assertIsDisplayed()
            compose.onNodeWithTag("developer:network").assertTextContains("diagnostic.example", substring=true).assertTextContains("DNS",substring=true)
            compose.waitUntil(6000) {
                compose.onAllNodesWithTag("developer:metrics").fetchSemanticsNodes().any { node ->
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text.contains(Regex("CPU: [0-9]")) }
                }
            }
            compose.onNodeWithTag("nav:Settings").assertIsFocused()
            compose.onNodeWithTag("developer:metrics").assertTextContains("RAM PSS",substring=true).assertTextContains("GPU %: n/a",substring=true)
            val panel=compose.onNodeWithTag("developer:diagnostics").fetchSemanticsNode().boundsInRoot
            val content=compose.onNodeWithTag("app:content").fetchSemanticsNode().boundsInRoot
            assertTrue("Panel covers content",panel.top>=content.bottom)
            // Sampling and visibility survive Activity recreation, but remain session-only.
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("developer:diagnostics").assertIsDisplayed()
            open();choose("developer:diagnostics-toggle")
            remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("developer:diagnostics").assertDoesNotExist()
        } finally { graph.networkDiagnostics.recovered("diagnostic.example") }
    }

    @Test fun diagnosticsExplainsErrorsInBothLanguagesAndFitsLargeUiScale() {
        val language=mutableStateOf("en");val scale=mutableIntStateOf(100)
        val diagnostics=tv.torrserve.silicon.core.network.NetworkDiagnostics()
        diagnostics.failure("api.themoviedb.org",javax.net.ssl.SSLHandshakeException("private credentials"))
        compose.activity.setContent { TorrFrameTheme(scale.intValue) { AppLanguage(language.value) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Spacer(Modifier.weight(1f))
                tv.torrserve.silicon.features.developer.DiagnosticPanel(diagnostics)
            }
        } } }
        for (code in listOf("en","ru")) for (percent in listOf(80,100,120,140)) {
            compose.runOnUiThread { language.value=code;scale.intValue=percent }
            compose.onNodeWithTag("developer:diagnostics").assertIsDisplayed()
            compose.onNodeWithTag("developer:network").assertTextContains(if(code=="ru")"Проверьте дату и сертификаты."else"Check the date and certificates.",substring=true)
            compose.onNodeWithTag("developer:network").assertTextContains("api.themoviedb.org",substring=true)
            val panel=compose.onNodeWithTag("developer:diagnostics").fetchSemanticsNode().boundsInRoot
            val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(panel.left>=root.left && panel.right<=root.right && panel.bottom<=root.bottom)
            assertTrue("Diagnostics occupies too much screen",panel.height<root.height/4)
        }
        diagnostics.recovered("api.themoviedb.org")
        compose.onNodeWithTag("developer:network").assertTextContains("зарегистрированных ошибок нет",substring=true)
    }
    @Test fun everyToggleAndResetWorkWithDpadAndKeepRealKeys() {
        val keys=listOf("TMDB","OMDb","Kinopoisk").associateWith{BuiltInMetadataKeys.forProvider(it)}
        open()
        for(provider in keys.keys)choose("developer:key:$provider")
        assertEquals(keys.keys,graph.developer.state.value.missingKeys)
        choose("developer:metadata-offline");choose("developer:bypass-cache");choose("developer:server-offline")
        assertEquals(DeveloperState(keys.keys,true,true,true),graph.developer.state.value)
        compose.onNodeWithTag("developer:active").assertExists()
        assertTrue("A real key was modified",keys.all{(provider,key)->BuiltInMetadataKeys.forProvider(provider)==key})
        val revision=graph.developer.catalogReloads.value
        choose("developer:reload-catalog");assertTrue(graph.developer.catalogReloads.value>revision)
        choose("developer:reset");assertFalse(graph.developer.state.value.active)
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("settings:category:Developer").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("nav:Settings").assertIsFocused()
        compose.onNodeWithTag("developer:active").assertDoesNotExist()
    }
    @Test fun missingTmdbKeyFailsBeforeNetworkAndSurvivesActivityRecreationOnly()=runBlocking {
        open();choose("developer:key:TMDB")
        val failure=try { withTimeout(2000){graph.tmdb.get("movie/157336",mapOf("language" to "en-US"))};null }catch(e:ServiceFailure){e}
        assertNotNull(failure);assertEquals(401,failure!!.status)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings:detail:Developer").assertExists()
        assertTrue("TMDB" in graph.developer.state.value.missingKeys)
        choose("developer:reset")
        assertFalse(tv.torrserve.silicon.features.developer.DeveloperControls(true).state.value.active)
    }
    @Test fun resetAutomaticallyRecoversTheVisibleCatalogFromASimulatedFailure() {
        val controls=DeveloperControls(true).apply { update { it.copy(missingKeys=setOf("TMDB")) } }
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val repository=object:CatalogRepository {
            override fun related(ref:CatalogRef,source:CatalogRelatedSource,language:String,page:Int,force:Boolean)=emptyFlow<CatalogLoad<CatalogPage>>()
            override fun section(section:CatalogSection,language:String,page:Int,force:Boolean)=flow {
                calls.incrementAndGet()
                try {
                    controls.beforeRequest("TMDB")
                    emit(CatalogLoad(data=CatalogPage(listOf(CatalogItem(CatalogRef(157336,section.type),"Recovered title")),1,1)))
                } catch(e:ServiceFailure) { emit(CatalogLoad<CatalogPage>(error=true)) }
            }
            override fun search(query:String,language:String,page:Int,force:Boolean)=emptyFlow<CatalogLoad<CatalogPage>>()
            override fun details(ref:CatalogRef,language:String,force:Boolean)=emptyFlow<CatalogLoad<CatalogDetails>>()
            override fun discover(filter:CatalogFilter,type:CatalogType,language:String,page:Int,force:Boolean)=emptyFlow<CatalogLoad<CatalogPage>>()
        }
        lateinit var vm:CatalogViewModel
        compose.activity.setContent {
            TorrFrameTheme { AppLanguage("en") {
                vm=androidx.lifecycle.viewmodel.compose.viewModel(key="developer-reset-test",factory=object:androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>)=
                        CatalogViewModel(repository,flowOf("en-US"),SavedStateHandle()) as T
                })
                val state by vm.state.collectAsState()
                val revision by controls.catalogReloads.collectAsState()
                val focus=remember { FocusRequester() }
                Box(Modifier.fillMaxSize()) { CatalogScreen(state,vm,focus,refreshRevision=revision) }
            } }
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { vm.state.value.sections.values.all { it.error && !it.loading } }
        val failedCalls=calls.get()
        compose.runOnUiThread { controls.reset() }
        compose.waitUntil(5000) { vm.state.value.sections.values.all { !it.error && !it.loading && it.items.isNotEmpty() } }
        assertFalse(controls.state.value.active)
        assertEquals(failedCalls+CatalogSection.entries.size,calls.get())
        // Another reset must not be discarded as an already-consumed revision.
        compose.runOnUiThread { controls.reset() }
        compose.waitUntil(5000) { calls.get()==failedCalls+2*CatalogSection.entries.size }
        assertTrue(vm.state.value.sections.values.none { it.error })
    }
    private fun waitReady() {
        compose.waitUntil(15_000){graph.server.state.value is ServerState.Running && graph.connection.state.value.status==ConnectionStatus.Connected}
        val vm=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${LibraryViewModel::class.java.name}") as LibraryViewModel
        compose.waitUntil(15_000){!vm.state.value.busy};compose.waitForIdle()
    }
    @Test fun simulatedServerOutageShowsExplanationWithoutStoppingProcessAndResetRecovers() {
        waitReady();val pid=(graph.server.state.value as ServerState.Running).pid
        open();choose("developer:server-offline")
        remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("library:server-status").assertExists()
        compose.onNodeWithText("TorrServer is unavailable").assertExists()
        compose.onNodeWithText("Your library is empty.").assertDoesNotExist()
        assertEquals(pid,(graph.server.state.value as ServerState.Running).pid)
        click("library:server-settings");compose.onNodeWithTag("settings:detail:TorrServer").assertExists()
        compose.onNodeWithTag("settings:back").assertIsFocused()
        remote(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:Developer"));click("settings:category:Developer")
        choose("developer:reset");remote(KeyEvent.KEYCODE_BACK);remote(KeyEvent.KEYCODE_BACK)
        waitReady();compose.waitUntil(15_000){compose.onAllNodesWithTag("library:server-status").fetchSemanticsNodes().isEmpty()}
        assertEquals(pid,(graph.server.state.value as ServerState.Running).pid)
    }
    @Test fun realServerStopIsReportedAndRestartRestoresLibrary()=runBlocking {
        waitReady()
        try {
            graph.server.stop();compose.onNodeWithText("TorrServer is stopped").assertExists()
            compose.onNodeWithText("Your library is empty.").assertDoesNotExist()
            assertEquals(ServerState.Stopped,graph.server.state.value)
        } finally { graph.server.start();graph.connection.check() }
        waitReady();compose.waitUntil(15_000){compose.onAllNodesWithTag("library:server-status").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun cachedFilmsRemainInLibraryDuringOutage()=runBlocking {
        waitReady();graph.store.put(Torrent(hash,"Saved local film",emptyList()))
        graph.developer.update{it.copy(serverUnavailable=true)}
        compose.onNodeWithTag("library:server-status").assertExists()
        compose.onNodeWithText("Saved local film").assertExists()
        assertTrue(graph.store.list().any{it.hash==hash})
    }
    @Test fun connectingStoppedAndUnavailableNeverPretendLibraryIsEmptyInBothLanguages() {
        val status=mutableStateOf(LibraryAvailability.Connecting);val language=mutableStateOf("en")
        compose.activity.setContent { TorrFrameTheme { AppLanguage(language.value) {
            Box(Modifier.fillMaxSize()){LibraryScreen(LibraryUi(),{}, {},availability=status.value){}}
        } } }
        for(code in listOf("en","ru"))for(value in listOf(LibraryAvailability.Connecting,LibraryAvailability.Stopped,LibraryAvailability.Unavailable,LibraryAvailability.AccessDenied,LibraryAvailability.InvalidResponse)) {
            compose.runOnUiThread{language.value=code;status.value=value}
            compose.onNodeWithTag("library:server-status").assertExists()
            compose.onNodeWithText(if(code=="ru")"Медиатека пока пуста."else"Your library is empty.").assertDoesNotExist()
        }
        compose.runOnUiThread{status.value=LibraryAvailability.Ready}
        compose.onNodeWithText("Медиатека пока пуста.").assertExists()
    }
    @Test fun outageWarningLeavesCachedFilmsUsableAtBothScaleLimits() {
        val scale=mutableStateOf(70)
        compose.activity.setContent { TorrFrameTheme(scale.value) { AppLanguage("ru") {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Spacer(Modifier.height(60.dp))
                LibraryScreen(LibraryUi(items=listOf(LibraryItem(hash,"Saved local film",0))),{}, {},availability=LibraryAvailability.Unavailable){}
            }
        } } }
        for(value in listOf(70,110)) {
            compose.runOnUiThread{scale.value=value}
            compose.onNodeWithTag("library:server-settings").assertIsDisplayed()
            val film=compose.onNodeWithText("Saved local film").fetchSemanticsNode().boundsInRoot
            assertTrue("Cached film has no space at $value",film.width>0 && film.height>0)
        }
    }

    @Test fun focusRemainsVisibleWhenServerRecoversWhileSettingsButtonIsFocused() {
        val status=mutableStateOf(LibraryAvailability.Unavailable)
        compose.activity.setContent { TorrFrameTheme { AppLanguage("en") {
            Box(Modifier.fillMaxSize()){LibraryScreen(LibraryUi(),{}, {},availability=status.value){}}
        } } }
        compose.onNodeWithTag("library:server-settings").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("library:server-settings").assertIsFocused()
        compose.runOnUiThread{status.value=LibraryAvailability.Ready}
        compose.waitForIdle()
        assertTrue("Focus was lost on server recovery",compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
    }

}
