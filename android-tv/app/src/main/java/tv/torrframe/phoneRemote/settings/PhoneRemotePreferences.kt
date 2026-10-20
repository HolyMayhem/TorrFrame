package tv.torrframe.phoneRemote.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PhoneRemotePreference(val enabled: Boolean = true, val port: Int = 8765)
class PhoneRemotePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("phone-remote", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PhoneRemotePreference())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        mutable.value = PhoneRemotePreference(prefs.getBoolean("enabled", true), prefs.getInt("port", 8765).takeIf { it in 1024..65535 } ?: 8765)
    } }
    suspend fun save(value: PhoneRemotePreference) = withContext(Dispatchers.IO) { lock.withLock {
        require(value.port in 1024..65535)
        check(prefs.edit().putBoolean("enabled", value.enabled).putInt("port", value.port).commit())
        mutable.value = value
    } }
}
