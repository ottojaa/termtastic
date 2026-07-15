/**
 * Tests for [PairingTokens]: per-device claim semantics, TTL expiry (driven via
 * the injectable clock, no sleeping), and explicit invalidation.
 */
package se.soderbjorn.lunamux.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTokensTest {

    private val deviceA = "a".repeat(64)
    private val deviceB = "b".repeat(64)

    /**
     * The pairing panel polls this to know when to replace the code on screen:
     * a claimed QR is dead for everyone but its claimant, so a second phone
     * scanning it would land in the approval dialog instead of pairing.
     */
    @Test
    fun isClaimedReportsWhetherTheCodeOnScreenIsStillUsable() {
        val now = 1_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.isClaimed(token, nowMs = now + 1))

        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 2))
        assertTrue(PairingTokens.isClaimed(token, nowMs = now + 3))

        // An expired token is not "claimed" — it is simply gone.
        assertFalse(PairingTokens.isClaimed(token, nowMs = now + PairingTokens.TTL_MS + 1))
        assertFalse(PairingTokens.isClaimed("never-minted", nowMs = now))
    }

    @Test
    fun mintedTokenIsClaimedByFirstDevice() {
        val now = 1_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(token.length >= 43) // 32 bytes base64url, no padding
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
    }

    /**
     * The race this whole design exists for: one device attaches its pairing
     * token to /window, /api/ui-settings and several /pty sockets at once, and
     * every one of them must agree rather than fall through to the dialog.
     */
    @Test
    fun claimingDeviceMayConsumeRepeatedly() {
        val now = 1_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 2))
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 3))
    }

    /** Single-use where it matters: a photographed QR can't pair a 2nd device. */
    @Test
    fun claimedTokenIsRefusedForAnotherDevice() {
        val now = 1_200_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume(token, deviceB, nowMs = now + 2))
    }

    @Test
    fun claimDoesNotSurviveTtl() {
        val now = 1_300_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + PairingTokens.TTL_MS + 1))
    }

    @Test
    fun tokenExpiresAfterTtl() {
        val now = 2_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + PairingTokens.TTL_MS + 1))
    }

    @Test
    fun tokenValidJustBeforeTtl() {
        val now = 3_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + PairingTokens.TTL_MS - 1))
    }

    @Test
    fun invalidateKillsOutstandingToken() {
        val now = 4_000_000L
        val token = PairingTokens.mint(nowMs = now)
        PairingTokens.invalidate(token)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 1))
    }

    /** Closing the pairing panel must revoke even an already-claimed token. */
    @Test
    fun invalidateKillsClaimedToken() {
        val now = 4_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        PairingTokens.invalidate(token)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 2))
    }

    @Test
    fun unknownAndBlankCandidatesAreRejected() {
        val now = 5_000_000L
        PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume("not-a-real-token", deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume("", deviceA, nowMs = now + 1))
    }

    @Test
    fun blankDeviceHashIsRejected() {
        val now = 5_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume(token, "", nowMs = now + 1))
    }

    @Test
    fun tokensAreIndependent() {
        val now = 6_000_000L
        val a = PairingTokens.mint(nowMs = now)
        val b = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(b, deviceA, nowMs = now + 1))
        assertTrue(PairingTokens.consume(a, deviceB, nowMs = now + 2))
    }
}
