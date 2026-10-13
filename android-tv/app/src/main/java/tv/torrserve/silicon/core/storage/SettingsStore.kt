package tv.torrserve.silicon.core.storage

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
import tv.torrserve.silicon.core.server.ServerAddress

data class Settings(val jackettUrl: String = "", val jackettKey: String = "",
    val metadataOrder: List<String> = MetadataPriority.defaultOrder, val language: String = "en-US",
    val port: Int = 8090, val cacheMb: Int = 64, val resume: Boolean = true,
    val audioLanguage: String = "", val subtitleLanguage: String = "",
    val updateRepository: String = "", val uiLanguage: String = "en", val aniListEnabled: Boolean = false,
    val torrServerUrl: String = ServerAddress.DEFAULT, val useLocalServer: Boolean = true,
    val startOnBoot: Boolean = false, val engineSettingsInitialized: Boolean = false,
    val lanAccess: Boolean = true) {
    val jackettConfigured get() = jackettUrl.isNotBlank() && jackettKey.isNotBlank()
    val needsLocalService get() = useLocalServer && (lanAccess || startOnBoot)
    val serverBindAddress get() = if (lanAccess) "0.0.0.0" else "127.0.0.1"
}
class SettingsConflict : IllegalStateException("Настройки изменились. Обновите данные и повторите сохранение.")
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Settings())
    private val lock = Mutex()
    val state = mutable.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private fun secretKey(): SecretKey {
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
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        mutable.value = Settings(
            jackettUrl = prefs.getString("jackettUrl", "").orEmpty(),
            jackettKey = decode(prefs.getString("jackettKey", "")),
            metadataOrder = MetadataPriority.normalized(prefs.getString("metadataOrder", "").orEmpty().split(",")),
            language = prefs.getString("language", "en-US") ?: "en-US",
            port = prefs.getInt("port", 8090), cacheMb = prefs.getInt("cacheMb", 64),
            resume = prefs.getBoolean("resume", true), audioLanguage = prefs.getString("audio", "").orEmpty(),
            subtitleLanguage = prefs.getString("subtitles", "").orEmpty(),
            updateRepository = prefs.getString("updates", "").orEmpty(), uiLanguage = prefs.getString("uiLanguage", "en") ?: "en",
            aniListEnabled = prefs.getBoolean("aniListEnabled", prefs.getString("provider", "") == "AniList"),
            torrServerUrl = prefs.getString("torrServerUrl", null) ?: ServerAddress.local(prefs.getInt("port", 8090)),
            useLocalServer = prefs.getBoolean("useLocalServer", true), startOnBoot = prefs.getBoolean("startOnBoot", false),
            engineSettingsInitialized = prefs.getBoolean("engineSettingsInitialized", false),
            lanAccess = prefs.getBoolean("lanAccess", true))
        mutableRevision.value = prefs.getLong("revision", 0)
        // Persist migration before removing legacy keys; Jackett's encrypted key is retained.
        val migration = prefs.edit().putBoolean("aniListEnabled", mutable.value.aniListEnabled)
            .putString("metadataOrder", mutable.value.metadataOrder.joinToString(",")).remove("provider")
        prefs.all.keys.filter { it.startsWith("metadataKey_") }.forEach { migration.remove(it) }
        check(migration.commit()) { "Could not migrate metadata settings." }
    } }

    suspend fun save(s: Settings) = withContext(Dispatchers.IO) { lock.withLock { persist(s) } }
    suspend fun update(expectedRevision: Long? = null, block: (Settings) -> Settings) = withContext(Dispatchers.IO) { lock.withLock {
        if (expectedRevision != null && expectedRevision != mutableRevision.value) throw SettingsConflict()
        persist(block(mutable.value))
    } }
    suspend fun snapshot(): Pair<Long, Settings> = lock.withLock { mutableRevision.value to mutable.value }
    private fun persist(s: Settings) {
        require(s.port in 1024..65535 && s.cacheMb in 16..4096) { "Некорректный порт или размер кеша." }
        val address = ServerAddress.normalize(s.torrServerUrl)
        require(!s.useLocalServer || ServerAddress.isLocal(address, s.port)) { "Адрес встроенного сервера должен соответствовать локальному порту." }
        val revision = mutableRevision.value + 1
        val success = prefs.edit().putLong("revision", revision).putString("jackettUrl", s.jackettUrl.trim()).putString("jackettKey", encode(s.jackettKey.trim()))
            .putString("metadataOrder", MetadataPriority.normalized(s.metadataOrder).joinToString(","))
            .putBoolean("aniListEnabled", s.aniListEnabled)
            .putString("torrServerUrl", address).putBoolean("useLocalServer", s.useLocalServer)
            .putBoolean("startOnBoot", s.startOnBoot).putBoolean("engineSettingsInitialized", s.engineSettingsInitialized)
            .putBoolean("lanAccess", s.lanAccess)
            .putString("language", s.language).putInt("port", s.port).putInt("cacheMb", s.cacheMb)
            .putBoolean("resume", s.resume).putString("audio", s.audioLanguage).putString("subtitles", s.subtitleLanguage)
            .putString("updates", s.updateRepository.trim()).putString("uiLanguage", s.uiLanguage).commit()
        check(success) { "Не удалось сохранить настройки." }; mutableRevision.value = revision; mutable.value = s.copy(torrServerUrl = address, metadataOrder = MetadataPriority.normalized(s.metadataOrder))
    }
}
