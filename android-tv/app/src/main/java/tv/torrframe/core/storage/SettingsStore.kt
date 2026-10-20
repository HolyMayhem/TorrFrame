package tv.torrframe.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.torrframe.core.server.ServerAddress
import tv.torrserve.torrentsearch.model.TorrentSearchOptions

data class Settings(val jackettUrl: String = "", val jackettKey: String = "",
    val metadataOrder: List<String> = MetadataPriority.defaultOrder, val language: String = "ru-RU",
    val port: Int = 8090, val cacheMb: Int = 64, val resume: Boolean = true,
    val audioLanguage: String = "", val subtitleLanguage: String = "",
    val updateRepository: String = "", val uiLanguage: String = "ru", val aniListEnabled: Boolean = false,
    val torrServerUrl: String = ServerAddress.DEFAULT, val useLocalServer: Boolean = true,
    val startOnBoot: Boolean = false, val engineSettingsInitialized: Boolean = false,
    val lanAccess: Boolean = true, val uiScalePercent: Int = UiScale.DEFAULT,
    val uiTheme: String = "dark", val showNavigationHints: Boolean = false,
    val uiAccent: String = UiAccent.DEFAULT, val animationsEnabled: Boolean = true,
    val torrentSearch: TorrentSearchOptions = TorrentSearchOptions(),
    val catalogNetwork: tv.torrframe.core.network.CatalogNetworkSettings = tv.torrframe.core.network.CatalogNetworkSettings(),
    val playerMode: tv.torrframe.features.player.PlayerMode = tv.torrframe.features.player.PlayerMode.Internal,
    val externalPlayer: String = "",
    val builtInPlayer: tv.torrframe.features.player.PlayerPreferences = tv.torrframe.features.player.PlayerPreferences(),
    val jackettEnabled: Boolean = false, val uiSoundsEnabled: Boolean = true) {
    val jackettConfigured get() = jackettUrl.isNotBlank() && jackettKey.isNotBlank()
    val needsLocalService get() = useLocalServer && (lanAccess || startOnBoot)
    val serverBindAddress get() = if (lanAccess) "0.0.0.0" else "127.0.0.1"
}
class SettingsConflict : IllegalStateException("Настройки изменились. Обновите данные и повторите сохранение.")
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Settings())
    private val lock = Mutex()
    private var persisted: Settings? = null
    val state = mutable.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private fun secretKey(): SecretKey {
        // Stable storage identity: retain the alias across app renames to decrypt saved settings.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("torrserve-api-keys", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("torrserve-api-keys", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encode(value: String): String {
        if (value.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decode(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    suspend fun hasStoredSettings(): Boolean = withContext(Dispatchers.IO) { prefs.all.isNotEmpty() }
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        persisted = null
        mutable.value = Settings(
            jackettUrl = prefs.getString("jackettUrl", "").orEmpty(),
            jackettEnabled = prefs.getBoolean("jackettEnabled", !prefs.getString("jackettUrl", "").isNullOrBlank() && !prefs.getString("jackettKey", "").isNullOrBlank()),
            jackettKey = decode(prefs.getString("jackettKey", "")),
            metadataOrder = MetadataPriority.normalized(prefs.getString("metadataOrder", "").orEmpty().split(",")),
            language = prefs.getString("language", "ru-RU") ?: "ru-RU",
            port = prefs.getInt("port", 8090), cacheMb = prefs.getInt("cacheMb", 64),
            resume = prefs.getBoolean("resume", true), audioLanguage = prefs.getString("audio", "").orEmpty(),
            subtitleLanguage = prefs.getString("subtitles", "").orEmpty(),
            playerMode = tv.torrframe.features.player.PlayerMode.from(prefs.getString("playerMode", null)),
            externalPlayer = prefs.getString("externalPlayer", "").orEmpty(),
            builtInPlayer = tv.torrframe.features.player.PlayerPreferences.decode(prefs.getString("builtInPlayer", null)),
            updateRepository = prefs.getString("updates", "").orEmpty(), uiLanguage = prefs.getString("uiLanguage", "ru") ?: "ru",
            aniListEnabled = prefs.getBoolean("aniListEnabled", prefs.getString("provider", "") == "AniList"),
            torrServerUrl = prefs.getString("torrServerUrl", null) ?: ServerAddress.local(prefs.getInt("port", 8090)),
            useLocalServer = prefs.getBoolean("useLocalServer", true), startOnBoot = prefs.getBoolean("startOnBoot", false),
            engineSettingsInitialized = prefs.getBoolean("engineSettingsInitialized", false),
            lanAccess = prefs.getBoolean("lanAccess", true),
            uiScalePercent = UiScale.normalized(prefs.getInt("uiScalePercent", UiScale.DEFAULT)),
            uiTheme = prefs.getString("uiTheme", "dark").takeIf { it == "light" } ?: "dark",
            showNavigationHints = prefs.getBoolean("showNavigationHints", false),
            animationsEnabled = prefs.getBoolean("animationsEnabled", true),
            uiSoundsEnabled = prefs.getBoolean("uiSoundsEnabled", true),
            torrentSearch = TorrentSearchSettingsCodec.decode(prefs.getString("torrentSearch", null)),
            catalogNetwork = tv.torrframe.core.network.CatalogNetworkSettings.decode(prefs.getString("catalogNetwork", null), decode(prefs.getString("cubToken", ""))),
            uiAccent = UiAccent.normalized(prefs.getString("uiAccent", UiAccent.DEFAULT)))
        mutableRevision.value = prefs.getLong("revision", 0)
        // Persist migration before removing legacy keys; Jackett's encrypted key is retained.
        val migration = prefs.edit().putBoolean("aniListEnabled", mutable.value.aniListEnabled)
            .putString("metadataOrder", mutable.value.metadataOrder.joinToString(",")).remove("provider")
        prefs.all.keys.filter { it.startsWith("metadataKey_") }.forEach { migration.remove(it) }
        check(migration.commit()) { "Could not migrate metadata settings." }
        persisted = mutable.value
    } }

    suspend fun save(s: Settings) = withContext(Dispatchers.IO) { lock.withLock { persist(s, full = true) } }
    suspend fun update(expectedRevision: Long? = null, block: (Settings) -> Settings) = withContext(Dispatchers.IO) { lock.withLock {
        if (expectedRevision != null && expectedRevision != mutableRevision.value) throw SettingsConflict()
        persist(block(mutable.value))
    } }
    suspend fun snapshot(): Pair<Long, Settings> = lock.withLock { mutableRevision.value to mutable.value }
    private fun persist(s: Settings, full: Boolean = false) {
        require(s.port in 1024..65535 && s.cacheMb in 16..4096) { "Некорректный порт или размер кеша." }
        require(s.uiScalePercent == UiScale.normalized(s.uiScalePercent)) { "Некорректный масштаб интерфейса." }
        require(s.uiTheme in setOf("dark", "light"))
        require(s.uiAccent in UiAccent.colors)
        require(s.torrentSearch==s.torrentSearch.normalized()) { "Некорректные настройки TorrentSearch." }
        require(s.builtInPlayer == s.builtInPlayer.normalized())
        s.catalogNetwork.validated()
        val address = ServerAddress.normalize(s.torrServerUrl)
        require(!s.useLocalServer || ServerAddress.isLocal(address, s.port)) { "Адрес встроенного сервера должен соответствовать локальному порту." }
        val revision = mutableRevision.value + 1
        val next = s.copy(torrServerUrl = address, metadataOrder = MetadataPriority.normalized(s.metadataOrder))
        val previous = persisted.takeUnless { full || prefs.getLong("revision", 0) != mutableRevision.value }
        val editor = prefs.edit()
        editor.putLong("revision", revision)
        fun <T> write(old: T?, value: T, put: () -> Unit) { if (previous == null || old != value) put() }
        write(previous?.jackettUrl?.trim(), next.jackettUrl.trim()) { editor.putString("jackettUrl", next.jackettUrl.trim()) }
        write(previous?.jackettKey?.trim(), next.jackettKey.trim()) { editor.putString("jackettKey", encode(next.jackettKey.trim())) }
        write(previous?.jackettEnabled, next.jackettEnabled) { editor.putBoolean("jackettEnabled", next.jackettEnabled) }
        write(previous?.metadataOrder, next.metadataOrder) { editor.putString("metadataOrder", next.metadataOrder.joinToString(",")) }
        write(previous?.aniListEnabled, next.aniListEnabled) { editor.putBoolean("aniListEnabled", next.aniListEnabled) }
        write(previous?.torrServerUrl, address) { editor.putString("torrServerUrl", address) }
        write(previous?.useLocalServer, next.useLocalServer) { editor.putBoolean("useLocalServer", next.useLocalServer) }
        write(previous?.startOnBoot, next.startOnBoot) { editor.putBoolean("startOnBoot", next.startOnBoot) }
        write(previous?.engineSettingsInitialized, next.engineSettingsInitialized) { editor.putBoolean("engineSettingsInitialized", next.engineSettingsInitialized) }
        write(previous?.lanAccess, next.lanAccess) { editor.putBoolean("lanAccess", next.lanAccess) }
        write(previous?.uiScalePercent, next.uiScalePercent) { editor.putInt("uiScalePercent", next.uiScalePercent) }
        write(previous?.uiTheme, next.uiTheme) { editor.putString("uiTheme", next.uiTheme) }
        write(previous?.showNavigationHints, next.showNavigationHints) { editor.putBoolean("showNavigationHints", next.showNavigationHints) }
        write(previous?.uiAccent, next.uiAccent) { editor.putString("uiAccent", next.uiAccent) }
        write(previous?.uiSoundsEnabled, next.uiSoundsEnabled) { editor.putBoolean("uiSoundsEnabled", next.uiSoundsEnabled) }
        write(previous?.animationsEnabled, next.animationsEnabled) { editor.putBoolean("animationsEnabled", next.animationsEnabled) }
        write(previous?.torrentSearch, next.torrentSearch) { editor.putString("torrentSearch", TorrentSearchSettingsCodec.encode(next.torrentSearch)) }
        write(previous?.catalogNetwork?.copy(cubToken = ""), next.catalogNetwork.copy(cubToken = "")) { editor.putString("catalogNetwork", next.catalogNetwork.encode()) }
        write(previous?.catalogNetwork?.cubToken, next.catalogNetwork.cubToken) { editor.putString("cubToken", encode(next.catalogNetwork.cubToken)) }
        write(previous?.language, next.language) { editor.putString("language", next.language) }
        write(previous?.port, next.port) { editor.putInt("port", next.port) }
        write(previous?.cacheMb, next.cacheMb) { editor.putInt("cacheMb", next.cacheMb) }
        write(previous?.resume, next.resume) { editor.putBoolean("resume", next.resume) }
        write(previous?.audioLanguage, next.audioLanguage) { editor.putString("audio", next.audioLanguage) }
        write(previous?.subtitleLanguage, next.subtitleLanguage) { editor.putString("subtitles", next.subtitleLanguage) }
        write(previous?.builtInPlayer, next.builtInPlayer) { editor.putString("builtInPlayer", next.builtInPlayer.encode()) }
        write(previous?.playerMode, next.playerMode) { editor.putString("playerMode", next.playerMode.name) }
        write(previous?.externalPlayer, next.externalPlayer) { editor.putString("externalPlayer", next.externalPlayer) }
        write(previous?.updateRepository?.trim(), next.updateRepository.trim()) { editor.putString("updates", next.updateRepository.trim()) }
        write(previous?.uiLanguage, next.uiLanguage) { editor.putString("uiLanguage", next.uiLanguage) }
        // A failed commit may have changed SharedPreferences' memory image. Rebuild it on the next write.
        persisted = null
        check(editor.commit()) { "Не удалось сохранить настройки." }
        persisted = next
        mutableRevision.value = revision; mutable.value = next
    }
}
