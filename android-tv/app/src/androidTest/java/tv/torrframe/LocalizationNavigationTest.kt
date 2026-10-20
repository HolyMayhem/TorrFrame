package tv.torrframe

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.torrframe.core.localization.*
import tv.torrframe.core.storage.Settings
import tv.torrframe.features.settings.SettingsCategory
import tv.torrframe.features.settings.TorrServerSettingsViewModel
import tv.torrframe.ui.components.*
import tv.torrframe.ui.theme.TorrFrameTheme

@RunWith(AndroidJUnit4::class)
class LocalizationNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=(compose.activity.application as TorrFrameApplication).graph
    private lateinit var original:Settings
    @Before fun english()=runBlocking {
        graph.ready.await();original=graph.settings.state.value
        graph.settings.update { it.copy(uiLanguage="en") }
    }
    @After fun restore()=runBlocking { graph.settings.update { it.copy(uiLanguage=original.uiLanguage) } }
    private fun remote(key:Int) { compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle() }
    private fun clickTag(tag:String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus);remote(KeyEvent.KEYCODE_DPAD_CENTER) }
    private fun openSettings(){clickTag("nav:Settings");compose.onNodeWithTag("settings:category:PhoneRemote").assertIsFocused()}
    private fun category(value:SettingsCategory) {
        compose.onNodeWithTag("settings:categories").performScrollToNode(hasTestTag("settings:category:${value.name}"))
        clickTag("settings:category:${value.name}");compose.onNodeWithTag("settings:back").assertIsFocused()
    }
    private fun assertEnglish(matcher:SemanticsMatcher) {
        val texts=compose.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map{it.text} + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        assertTrue("No UI text was checked",texts.isNotEmpty())
        assertTrue("Russian text in English UI: ${texts.filter{it.contains(Regex("[А-Яа-яЁё]"))}}",texts.none{it.contains(Regex("[А-Яа-яЁё]"))})
    }
    @Test fun englishResourcesAreCompleteRegardlessOfDeviceLanguage() {
        val russianDevice=compose.activity.withUiLanguage("ru")
        val english=russianDevice.withUiLanguage("en")
        assertEquals("Настройки",russianDevice.getString(R.string.settings_title))
        assertEquals("Settings",english.getString(R.string.settings_title))
        val ids=R.string::class.java.fields.filter{it.type==Int::class.javaPrimitiveType}.map{it.getInt(null)}
        assertTrue(ids.size>=239)
        ids.forEach { id -> assertFalse("${english.resources.getResourceEntryName(id)} has Russian fallback",english.getString(id).contains(Regex("[А-Яа-яЁё]"))) }
        assertEquals("Enter Port",english.getString(R.string.field_enter,"Port"))
        assertEquals("Response time: 12.5 ms",english.getString(R.string.server_latency,12.5))
        assertEquals("Download speed: 1.25 Mbps · 1024 bytes",english.getString(R.string.server_bandwidth,1.25,1024))
        assertEquals("Settings",graph.app.uiString(R.string.settings_title))
        runBlocking{graph.settings.update{it.copy(uiLanguage="ru")}}
        assertEquals("Настройки",graph.app.uiString(R.string.settings_title))
        runBlocking{graph.settings.update{it.copy(uiLanguage="en")}}
        assertEquals("Settings",graph.app.uiString(R.string.settings_title))
        assertEquals(original.language,graph.settings.state.value.language)
    }
    @Test fun everySettingsCategoryAndDialogsUseEnglish() {
        openSettings()
        for(value in SettingsCategory.entries) {
            category(value)
            val list=compose.onNodeWithTag("settings:detail:${value.name}")
            assertEnglish(hasAnyAncestor(hasTestTag("settings:drawer")))
            // Visit the bottom and intermediate lazy items rather than only the first screenful.
            val anchors=when(value) {
                SettingsCategory.TorrentSearch -> listOf("ts:source:rutorinfo","ts:resolution:2160p","ts:format:BluRay","ts:min-size:plus","ts:seeds:5","ts:zero","ts:timeout:plus","ts:title:Both","ts:reset")
                SettingsCategory.TorrServer -> listOf("server:engine","server:boot","server:diagnostics")
                SettingsCategory.Appearance -> listOf("scale:plus","appearance:light","appearance:accent:blue")
                else -> emptyList()
            }
            for(tag in anchors) {
                list.performScrollToNode(hasTestTag(tag))
                assertEnglish(hasAnyAncestor(hasTestTag("settings:drawer")))
            }
            if(value==SettingsCategory.PhoneRemote) {
                list.performScrollToNode(hasTestTag("field:Phone Remote port"));clickTag("field:Phone Remote port")
                compose.onNodeWithTag("dialog:done").assertTextEquals("Done")
                compose.onNodeWithTag("dialog:cancel").assertTextEquals("Cancel")
                remote(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("dialog:done").assertDoesNotExist()
            }
            if(value==SettingsCategory.About) {
                list.performScrollToNode(hasText("Open source licenses"))
                compose.onNodeWithText("Open source licenses").performClick()
                compose.onNodeWithText("TorrServer by YouROK. Kotlin, AndroidX, OkHttp and Coil are distributed under Apache-2.0.").assertExists()
                remote(KeyEvent.KEYCODE_BACK)
            }
            remote(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("settings:category:${value.name}").assertIsFocused()
        }
    }
    @Test fun languageSwitchUpdatesOpenDrawerDialogsAndKeepsDpadFocus() {
        openSettings();category(SettingsCategory.Appearance)
        val list=compose.onNodeWithTag("settings:detail:Appearance")
        for((code,title) in listOf("ru" to "Язык приложения","en" to "App language","ru" to "Язык приложения","en" to "App language")) {
            list.performScrollToNode(hasText(code));compose.onNodeWithText(code).performSemanticsAction(SemanticsActions.RequestFocus)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5000){graph.settings.state.value.uiLanguage==code}
            compose.onNodeWithText(code).assertIsFocused();compose.onNodeWithText(title).assertExists()
            assertEquals(original.language,graph.settings.state.value.language)
        }
        remote(KeyEvent.KEYCODE_BACK);category(SettingsCategory.PhoneRemote)
        clickTag("field:Phone Remote port");compose.onNodeWithTag("dialog:done").assertTextEquals("Done")
        runBlocking{graph.settings.update{it.copy(uiLanguage="ru")}}
        compose.onNodeWithTag("dialog:done").assertTextEquals("Готово");compose.onNodeWithTag("dialog:cancel").assertTextEquals("Отмена")
        compose.onNodeWithTag("dialog:editor").assertIsFocused()
        runBlocking{graph.settings.update{it.copy(uiLanguage="en")}}
        compose.onNodeWithTag("dialog:done").assertTextEquals("Done");compose.onNodeWithTag("dialog:editor").assertIsFocused()
    }
    @Test fun errorsFromViewModelFollowAppLanguageAndStaleMessagesAreCleared() {
        openSettings();category(SettingsCategory.TorrServer)
        val vm=compose.activity.viewModelStore.get("androidx.lifecycle.ViewModelProvider.DefaultKey:${TorrServerSettingsViewModel::class.java.name}") as TorrServerSettingsViewModel
        compose.waitUntil(30_000){!vm.state.value.busy}
        compose.runOnUiThread { vm.address("invalid address") }
        compose.onNodeWithText(graph.app.uiString(R.string.server_invalid_url)).assertExists()
        assertFalse(vm.state.value.message.contains(Regex("[А-Яа-яЁё]")))
        runBlocking{graph.settings.update{it.copy(uiLanguage="ru")}}
        compose.waitUntil(5000){vm.state.value.message.isEmpty()}
        compose.runOnUiThread { vm.address("invalid address") }
        compose.onNodeWithText(graph.app.uiString(R.string.server_invalid_url)).assertExists()
        assertTrue(vm.state.value.message.contains("Введите"))
    }
    @Test fun serviceNotificationLanguageUpdatesWithoutRestartingTorrServer():Unit=runBlocking {
        val boot=graph.settings.state.value.startOnBoot
        try {
            graph.serverActions.boot(true)
            compose.waitUntil(15_000){graph.server.state.value is tv.torrframe.core.server.ServerState.Running}
            val pid=(graph.server.state.value as tv.torrframe.core.server.ServerState.Running).pid
            val notifications=compose.activity.getSystemService(android.app.NotificationManager::class.java)
            for(language in listOf("ru","en")) {
                graph.settings.update{it.copy(uiLanguage=language)}
                val channel=graph.app.uiString(R.string.local_service_channel)
                compose.waitUntil(5000){notifications.getNotificationChannel("local-torrserver")?.name?.toString()==channel}
                assertEquals(pid,(graph.server.state.value as tv.torrframe.core.server.ServerState.Running).pid)
            }
        } finally { graph.serverActions.boot(boot) }
    }
    @Test fun localizedContextKeepsActivityOwnerAndUserInputIsNeverTranslated() {
        assertTrue(compose.activity.withUiLanguage("en") is android.content.ContextWrapper)
        var foundActivityOwner=false
        val language=mutableStateOf("en")
        compose.activity.setContent {
            TorrFrameTheme {
                AppLanguage(language.value) {
                    var owner:android.content.Context?=LocalContext.current
                    while(owner is android.content.ContextWrapper && owner !is android.app.Activity)owner=owner.baseContext
                    val isActivity=owner===compose.activity
                    SideEffect{foundActivityOwner=isActivity}
                    EditField(stringResource(R.string.metadata_rename),"Watch"){}
                }
            }
        }
        compose.onNodeWithTag("field:Film title").assertTextEquals("Watch");assertTrue(foundActivityOwner)
        compose.runOnUiThread{language.value="ru"}
        compose.onNodeWithTag("field:Название фильма").assertTextEquals("Watch")
        clickTag("field:Название фильма");compose.onNodeWithTag("dialog:done").assertTextEquals("Готово")
    }
}
