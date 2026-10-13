package tv.torrserve.silicon.phoneRemote.security

import java.security.MessageDigest
import java.security.SecureRandom

internal fun secureToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
internal fun fingerprint(value: String) = digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }

data class PairingLink(val url: String, val expiresAt: Long)
class PairingManager(private val now: () -> Long = System::currentTimeMillis) {
    private var hash: ByteArray? = null
    private var origin = ""
    private var expires = 0L
    @Synchronized fun issue(origin: String): PairingLink {
        val token = secureToken()
        hash = digest(token); this.origin = origin; expires = now() + 120_000
        return PairingLink("$origin/#pair=$token", expires)
    }
    @Synchronized fun consume(token: String, origin: String): Boolean {
        val expected = hash ?: return false
        if (token.length != 64 || expires <= now() || this.origin != origin || !MessageDigest.isEqual(expected, digest(token))) return false
        hash = null; return true
    }
    @Synchronized fun clear() { hash = null; expires = 0 }
}

/** Bounded protection against unauthenticated pairing bursts; no background timer. */
class PairingRateLimit(private val now: () -> Long = System::currentTimeMillis) {
    private val attempts = LinkedHashMap<String, Pair<Long, Int>>()
    @Synchronized fun allow(address: String): Boolean {
        val old = attempts[address]
        val count = if (old != null && now() - old.first < 60_000) old.second else 0
        val start = if (count == 0) now() else old!!.first
        if (attempts.size >= 128 && address !in attempts) attempts.remove(attempts.keys.first())
        attempts[address] = start to count + 1
        return count < 8
    }
}
