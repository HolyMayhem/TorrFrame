package tv.torrframe.phoneRemote.security

import java.security.MessageDigest
import java.security.SecureRandom

internal fun secureToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
internal fun fingerprint(value: String) = digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }

data class PairingLink(val url: String, val expiresAt: Long, val code: String)
class PairingManager(private val now: () -> Long = System::currentTimeMillis) {
    private var hash: ByteArray? = null
    private var origin = ""
    private var expires = 0L
    private var codeHash: ByteArray? = null
    private var codeAttempts = 0
    @Synchronized fun issue(origin: String): PairingLink {
        val token = secureToken()
        val random = SecureRandom()
        var code: String
        do { code = random.nextInt(1_000_000).toString().padStart(6, '0') }
        while (codeHash?.let { MessageDigest.isEqual(it, digest(code)) } == true)
        codeHash = digest(code); codeAttempts = 0
        hash = digest(token); this.origin = origin; expires = now() + 120_000
        return PairingLink("$origin/#pair=$token", expires, code)
    }
    @Synchronized fun consume(token: String, origin: String): Boolean {
        val expected = hash ?: return false
        if (token.length != 64 || expires <= now() || this.origin != origin || !MessageDigest.isEqual(expected, digest(token))) return false
        clear(); return true
    }
    @Synchronized fun consumeCode(code: String, origin: String): Boolean {
        val expected = codeHash ?: return false
        if (expires <= now() || this.origin != origin || codeAttempts >= 8) return false
        codeAttempts++
        if (!code.matches(Regex("[0-9]{6}")) || !MessageDigest.isEqual(expected, digest(code))) {
            // Bound guesses globally across clients as well as by peer address.
            if (codeAttempts >= 8) codeHash = null
            return false
        }
        clear(); return true
    }
    @Synchronized fun clear() { hash = null; codeHash = null; expires = 0; codeAttempts = 0 }
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
