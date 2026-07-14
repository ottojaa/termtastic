/**
 * In-memory registry of one-time QR pairing tokens.
 *
 * The desktop pairing dialog mints a token when it opens ([PairingTokens.mint])
 * and kills it when it closes ([PairingTokens.invalidate]); a scanning client
 * sends the raw token on its first connect, and [DeviceAuth] spends it via
 * [PairingTokens.consume] to trust the device without an approval dialog.
 *
 * Tokens are 256-bit SecureRandom values. Only their SHA-256 hashes are held
 * here (never the raw string), each entry is single-use, and every entry
 * expires [PairingTokens.TTL_MS] after minting — so a photographed QR code
 * goes stale in minutes and can never be replayed after use.
 *
 * State is process-lifetime and deliberately not persisted: a pairing token
 * is only meaningful while the pairing dialog is on screen.
 *
 * @see DeviceAuth
 */
package se.soderbjorn.lunamux.auth

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Thread-safe (`@Synchronized`) mint/consume registry for pairing tokens.
 *
 * All entry points take an injectable `nowMs` so tests can drive expiry
 * without sleeping; production callers use the default clock.
 */
object PairingTokens {

    private val log = LoggerFactory.getLogger(PairingTokens::class.java)

    /** Lifetime of a minted token; QR codes older than this must be re-shown. */
    const val TTL_MS: Long = 5 * 60_000

    /** sha256-hex(raw token) → expiry epoch-millis. Guarded by @Synchronized. */
    private val pending = HashMap<String, Long>()

    /**
     * Mint a fresh single-use pairing token and register its hash for
     * [TTL_MS]. Called by the pairing dialog each time it opens, so every
     * showing of the QR carries a brand-new secret.
     *
     * @param nowMs the current clock, injectable for tests.
     * @return the raw base64url token to embed in the QR payload — the only
     *   copy of it; this registry keeps just the hash.
     * @see consume
     * @see invalidate
     */
    @Synchronized
    fun mint(nowMs: Long = System.currentTimeMillis()): String {
        evictExpired(nowMs)
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pending[sha256Hex(raw)] = nowMs + TTL_MS
        log.info("PairingTokens: minted pairing token (ttl {} s, {} outstanding)", TTL_MS / 1000, pending.size)
        return raw
    }

    /**
     * Spend [candidate] if it matches a live token. Comparison is
     * constant-time on the hash, the entry is removed on success
     * (single-use), and stale entries are evicted first.
     *
     * Called by [DeviceAuth]'s pairing approval path on every connect that
     * carries a `pairToken`.
     *
     * @param candidate the raw token string received from the client.
     * @param nowMs the current clock, injectable for tests.
     * @return `true` exactly once per minted token, within its TTL.
     */
    @Synchronized
    fun consume(candidate: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        evictExpired(nowMs)
        if (candidate.isBlank()) return false
        val hash = sha256Hex(candidate)
        val match = pending.keys.firstOrNull {
            MessageDigest.isEqual(it.toByteArray(), hash.toByteArray())
        } ?: return false
        pending.remove(match)
        log.info("PairingTokens: pairing token consumed ({} outstanding)", pending.size)
        return true
    }

    /**
     * Kill a specific outstanding token. Called by the pairing dialog's
     * dispose hook so a token dies the moment its QR leaves the screen.
     *
     * @param rawToken the raw token returned by [mint].
     */
    @Synchronized
    fun invalidate(rawToken: String) {
        if (pending.remove(sha256Hex(rawToken)) != null) {
            log.info("PairingTokens: pairing token invalidated ({} outstanding)", pending.size)
        }
    }

    /** Drop entries whose expiry has passed; callers hold the monitor. */
    private fun evictExpired(nowMs: Long) {
        pending.entries.removeAll { it.value <= nowMs }
    }

    /** Lowercase-hex SHA-256, matching [DeviceAuth]'s token hashing. */
    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
