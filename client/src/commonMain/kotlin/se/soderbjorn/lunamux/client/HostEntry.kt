/**
 * Data model for a saved server/host entry in the Lunamux connection list.
 *
 * Mobile clients (Android, iOS) persist a list of [HostEntry] items so users
 * can switch between multiple Lunamux servers without re-typing addresses.
 *
 * @see se.soderbjorn.lunamux.client.ServerUrl
 */
package se.soderbjorn.lunamux.client

import kotlinx.serialization.Serializable

/**
 * A single saved Lunamux server endpoint shown in the host picker UI.
 *
 * The server speaks TLS only (see `SERVER_TLS_PORT`); every connection is
 * `https`/`wss`. [pinnedFingerprintHex] is filled in the first time a
 * connect succeeds — until then the client runs in TOFU capture mode and
 * the server's `DeviceAuth.ApprovalDialog` provides the trust ceremony. A
 * subsequent connect uses the stored pin in strict-verify mode; a mismatch
 * throws and surfaces the cert-changed UI.
 *
 * Entries created by scanning a pairing QR also carry the full candidate
 * endpoint set the server advertised ([candidates]) and, until the first
 * successful connect consumes it, the one-time pairing token that lets the
 * server trust this device without an approval dialog ([pairingToken]).
 * Manually-added entries leave both at their defaults and serialize exactly
 * as before (`encodeDefaults` is off), so old and new app versions can share
 * the same `local_state.json`.
 *
 * @property id                   Unique identifier for this entry (typically a UUID).
 * @property label                Human-readable display name chosen by the user.
 * @property host                 Hostname or IP address of the Lunamux server.
 * @property port                 TCP port the server listens on.
 * @property pinnedFingerprintHex Lowercase hex SHA-256 of the server's leaf cert
 *   captured on the first successful connect, or `null` until then. Used by
 *   `createPinnedHttpClient` to decide between capture and verify modes.
 *   QR-paired entries are born with the fingerprint from the payload, so they
 *   start in verify mode and never go through TOFU capture.
 * @property candidates           Every candidate endpoint from the pairing
 *   payload as `host[:port]` strings (bracketed IPv6; a bare host implies
 *   [port]). [host]/[port] hold the currently-preferred endpoint — the last
 *   one that actually connected — while this list is what the multi-candidate
 *   connect walks in order. Empty for manually-added hosts.
 * @property pairingToken         One-time pairing token from the QR payload;
 *   sent on the next connect and cleared once that connect succeeds. `null`
 *   for manually-added hosts and for paired hosts after first contact.
 *
 * @see se.soderbjorn.lunamux.client.ServerUrl
 * @see se.soderbjorn.lunamux.client.createPinnedHttpClient
 * @see se.soderbjorn.lunamux.client.CandidateConnector
 * @see se.soderbjorn.lunamux.PairingPayload
 */
@Serializable
data class HostEntry(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val pinnedFingerprintHex: String? = null,
    val candidates: List<String> = emptyList(),
    val pairingToken: String? = null,
) {
    companion object {
        /**
         * Ceiling on a stored candidate list. Not the same concern as
         * `PairingPayload.MAX_CANDIDATES` (which bounds what one QR can carry
         * and what a hostile QR could induce): this bounds what accumulates
         * across *many* pairings over time.
         *
         * It exists because `CandidateConnector` walks candidates
         * sequentially with a 12s timeout each, so every stale entry is up to
         * 12 seconds of spinner before a live one is reached. Without a cap,
         * a laptop paired at work, home, and a café collects dead addresses
         * forever and connects get slower every time.
         */
        const val MAX_STORED_CANDIDATES = 12

        /**
         * Merge the candidates from a freshly-scanned QR into what an entry
         * already knows, newest-first.
         *
         * Re-pairing exists to *add* a network, not swap one for another: a
         * laptop paired at work and then re-paired at home should be reachable
         * from both, whereas replacing the list would make the phone
         * ping-pong, re-pairing every time it changes buildings.
         *
         * [fresh] leads because it describes the network the user is standing
         * on right now — the one most likely to connect, and position is worth
         * up to 12s each in the sequential walk. Older entries follow in their
         * previous order, and the tail past [MAX_STORED_CANDIDATES] is dropped:
         * those are the least-recently-seen networks, so they are the ones
         * least likely to be the current one.
         *
         * Called by the Android hosts screen's `handlePairingUri` and the iOS
         * `HostsViewModel.handlePairingUri` when a scan matches a known server.
         *
         * @param fresh candidate strings from the scanned payload, in the
         *   server's suggested order.
         * @param existing the entry's current [candidates].
         * @return the merged list, deduplicated, newest-first, capped at
         *   [MAX_STORED_CANDIDATES].
         * @see CandidateConnector
         */
        fun mergeCandidates(fresh: List<String>, existing: List<String>): List<String> =
            (fresh + existing).distinct().take(MAX_STORED_CANDIDATES)
    }
}
