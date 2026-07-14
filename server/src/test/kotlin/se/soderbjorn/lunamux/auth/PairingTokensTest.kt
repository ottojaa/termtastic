/**
 * Tests for [PairingTokens]: single-use semantics, TTL expiry (driven via the
 * injectable clock, no sleeping), and explicit invalidation.
 */
package se.soderbjorn.lunamux.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTokensTest {

    @Test
    fun mintedTokenConsumesExactlyOnce() {
        val now = 1_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(token.length >= 43) // 32 bytes base64url, no padding
        assertTrue(PairingTokens.consume(token, nowMs = now + 1))
        assertFalse(PairingTokens.consume(token, nowMs = now + 2))
    }

    @Test
    fun tokenExpiresAfterTtl() {
        val now = 2_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume(token, nowMs = now + PairingTokens.TTL_MS + 1))
    }

    @Test
    fun tokenValidJustBeforeTtl() {
        val now = 3_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, nowMs = now + PairingTokens.TTL_MS - 1))
    }

    @Test
    fun invalidateKillsOutstandingToken() {
        val now = 4_000_000L
        val token = PairingTokens.mint(nowMs = now)
        PairingTokens.invalidate(token)
        assertFalse(PairingTokens.consume(token, nowMs = now + 1))
    }

    @Test
    fun unknownAndBlankCandidatesAreRejected() {
        val now = 5_000_000L
        PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume("not-a-real-token", nowMs = now + 1))
        assertFalse(PairingTokens.consume("", nowMs = now + 1))
    }

    @Test
    fun tokensAreIndependent() {
        val now = 6_000_000L
        val a = PairingTokens.mint(nowMs = now)
        val b = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(b, nowMs = now + 1))
        assertTrue(PairingTokens.consume(a, nowMs = now + 2))
    }
}
