package tv.torrframe.core.storage

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.torrframe.R
import tv.torrframe.core.server.ApplicationActionFailure
import java.net.URI

enum class JacRedMode(val publicUrl: String? = null) {
    Auto, JacBlack("https://jac.black"), RuJacBlack("https://ru.jac.black"), Custom;

    companion object {
        val publicUrls = entries.mapNotNull { it.publicUrl }
    }
}

data class JacRedSettings(val url: String = "", val apiKey: String = "",
    val mode: JacRedMode = defaultMode(url)) {
    val configured get() = mode != JacRedMode.Custom || url.isNotBlank()

    fun updated(address: String = url, key: String? = null,
        mode: JacRedMode = if (address == url) this.mode else defaultMode(address)): JacRedSettings {
        val input = address.trim()
        val normalized = if (input.isEmpty()) "" else {
            val uri = runCatching { URI(input) }.getOrNull()
            val parsed = input.toHttpUrlOrNull()
            if (input.length > 4096 || uri == null || parsed == null || uri.rawAuthority.isNullOrEmpty() ||
                uri.rawUserInfo != null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                uri.rawQuery != null || uri.rawFragment != null) {
                throw ApplicationActionFailure(R.string.jacred_invalid_url)
            }
            parsed.toString().trimEnd('/')
        }
        val secret = (key ?: apiKey.takeIf { normalized == url }.orEmpty()).trim()
        if (secret.length > 4096 || secret.any { it !in ' '..'~' }) {
            throw ApplicationActionFailure(R.string.jacred_invalid_key_format)
        }
        return JacRedSettings(normalized, secret, mode)
    }
}

private fun defaultMode(address: String) = if (address.isBlank()) JacRedMode.Auto else JacRedMode.Custom
