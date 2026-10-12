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

data class Settings(val jackettUrl: String = "", val jackettKey: String = "",
    val metadataProvider: String = "TMDB", val metadataKey: String = "", val language: String = "en-US",
    val port: Int = 8090, val cacheMb: Int = 64, val resume: Boolean = true,
    val audioLanguage: String = "", val subtitleLanguage: String = "",
    val updateRepository: String = "", val uiLanguage: String = "en") {
    val jackettConfigured get() = jackettUrl.isNotBlank() && jackettKey.isNotBlank()
}
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Settings())
    val state = mutable.asStateFlow()
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
    suspend fun load() = withContext(Dispatchers.IO) {
        val provider = prefs.getString("provider", "TMDB") ?: "TMDB"
        mutable.value = Settings(prefs.getString("jackettUrl", "") ?: "", decode(prefs.getString("jackettKey", "")),
            provider, decode(prefs.getString("metadataKey_$provider", "")),
            prefs.getString("language", "en-US") ?: "en-US", prefs.getInt("port", 8090), prefs.getInt("cacheMb", 64),
            prefs.getBoolean("resume", true), prefs.getString("audio", "") ?: "", prefs.getString("subtitles", "") ?: "",
            prefs.getString("updates", "") ?: "", prefs.getString("uiLanguage", "en") ?: "en")
    }
    suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        require(s.port in 1024..65535 && s.cacheMb in 32..256) { "Use port 1024–65535 and cache 32–256 MB." }
        val success = prefs.edit().putString("jackettUrl", s.jackettUrl.trim()).putString("jackettKey", encode(s.jackettKey.trim()))
            .putString("provider", s.metadataProvider).putString("metadataKey_${s.metadataProvider}", encode(s.metadataKey.trim()))
            .putString("language", s.language).putInt("port", s.port).putInt("cacheMb", s.cacheMb)
            .putBoolean("resume", s.resume).putString("audio", s.audioLanguage).putString("subtitles", s.subtitleLanguage)
            .putString("updates", s.updateRepository.trim()).putString("uiLanguage", s.uiLanguage).commit()
        check(success) { "Could not save settings." }; mutable.value = s
    }
    suspend fun providerKey(provider: String): String = withContext(Dispatchers.IO) { decode(prefs.getString("metadataKey_$provider", "")) }
}
