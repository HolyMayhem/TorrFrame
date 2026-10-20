package tv.torrframe.phoneRemote.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

data class RemoteSession(val id: String, val secretHash: String, val csrf: String,
    val label: String, val createdAt: Long, val expiresAt: Long)
data class SessionGrant(val session: RemoteSession, val cookie: String)
interface SessionPersistence {
    suspend fun read(): List<RemoteSession>
    suspend fun write(sessions: List<RemoteSession>)
}
class SessionManager(private val persistence: SessionPersistence, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessions = mutable.asStateFlow()
    suspend fun load() = lock.withLock { mutable.value = persistence.read().filter { it.expiresAt > now() }.takeLast(16) }
    suspend fun create(label: String): SessionGrant = lock.withLock {
        val secret = secureToken()
        val record = RemoteSession(UUID.randomUUID().toString(), fingerprint(secret), secureToken(),
            label.filterNot { it.isISOControl() }.take(60).ifBlank { "Телефон" }, now(), now() + 7 * 86_400_000L)
        val next = (mutable.value.filter { it.expiresAt > now() } + record).takeLast(16)
        persistence.write(next); mutable.value = next
        SessionGrant(record, secret)
    }
    fun authenticate(cookie: String?): RemoteSession? {
        if (cookie == null || cookie.length != 64) return null
        val hashed = fingerprint(cookie)
        return mutable.value.firstOrNull { it.expiresAt > now() && MessageDigest.isEqual(it.secretHash.toByteArray(), hashed.toByteArray()) }
    }
    fun csrf(session: RemoteSession, value: String?): Boolean = value != null && value.length == 64 && MessageDigest.isEqual(session.csrf.toByteArray(), value.toByteArray())
    suspend fun revoke(id: String) = lock.withLock { val next = mutable.value.filterNot { it.id == id }; mutable.value = next; persistence.write(next) }
    suspend fun clear() = lock.withLock { mutable.value = emptyList(); persistence.write(emptyList()) }
}
