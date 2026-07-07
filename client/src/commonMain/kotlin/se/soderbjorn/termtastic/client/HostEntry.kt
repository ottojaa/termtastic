/**
 * Data model for a saved server/host entry in the Termtastic connection list.
 *
 * Mobile clients (Android, iOS) persist a list of [HostEntry] items so users
 * can switch between multiple Termtastic servers without re-typing addresses.
 *
 * @see se.soderbjorn.termtastic.client.ServerUrl
 */
package se.soderbjorn.termtastic.client

import kotlinx.serialization.Serializable

/**
 * A single saved Termtastic server endpoint shown in the host picker UI.
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
 * @property host                 Hostname or IP address of the Termtastic server.
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
 * @see se.soderbjorn.termtastic.client.ServerUrl
 * @see se.soderbjorn.termtastic.client.createPinnedHttpClient
 * @see se.soderbjorn.termtastic.client.CandidateConnector
 * @see se.soderbjorn.termtastic.PairingPayload
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
)
