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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import se.soderbjorn.lunamux.HostPort

/**
 * A single saved Lunamux server shown in the host picker UI.
 *
 * The server speaks TLS only (see `SERVER_TLS_PORT`); every connection is
 * `https`/`wss`. [pinnedFingerprintHex] is filled in the first time a
 * connect succeeds — until then the client runs in TOFU capture mode and
 * the server's `DeviceAuth.ApprovalDialog` provides the trust ceremony. A
 * subsequent connect uses the stored pin in strict-verify mode; a mismatch
 * throws and surfaces the cert-changed UI.
 *
 * One server, many addresses: a Mac is reachable at a different address on
 * every network it joins, and [addresses] holds all of them in the order the
 * connect walk should try them. There is deliberately no privileged "primary"
 * address beside a list of backups — that shape stored the same fact twice
 * (the preferred endpoint was also the head of the walk) and every caller had
 * to reassemble the flat list anyway. [addresses] `[0]` *is* the preferred
 * address, [promoting] is how the winner gets there, and the user can reorder
 * the list by hand in the host editor.
 *
 * @property id                   Unique identifier for this entry (typically a UUID).
 * @property label                Human-readable display name chosen by the user.
 * @property addresses            Every endpoint this server is known to answer
 *   at, in the order [CandidateConnector] walks them: `[0]` is tried first and
 *   is whatever last connected (see [promoting]). Never empty for a stored
 *   entry — [HostEntryMigratingSerializer] fills it in for entries written in
 *   the old format, and [se.soderbjorn.lunamux.client.storage.LocalState.hosts]
 *   discards anything still empty afterwards.
 * @property pinnedFingerprintHex Lowercase hex SHA-256 of the server's leaf cert
 *   captured on the first successful connect, or `null` until then. Used by
 *   `createPinnedHttpClient` to decide between capture and verify modes.
 *   QR-paired entries are born with the fingerprint from the payload, so they
 *   start in verify mode and never go through TOFU capture.
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
    // Defaulted so an entry carrying neither `addresses` nor a migratable
    // `host`/`port` decodes to empty and gets dropped at load, rather than
    // failing the whole LocalState blob (which shares the file with the
    // onboarding flag and news dismissals). See HostEntryMigratingSerializer.
    val addresses: List<HostPort> = emptyList(),
    val pinnedFingerprintHex: String? = null,
    val pairingToken: String? = null,
) {
    /**
     * The address a connect tries first: the last one that worked.
     *
     * Null only for the transient empty-list case the store discards, so UI
     * that has an entry in hand can treat this as present.
     *
     * @return the head of [addresses], or `null` when there are none.
     */
    val primary: HostPort? get() = addresses.firstOrNull()

    /**
     * This entry with [endpoint] moved to the front of [addresses].
     *
     * Called on every successful connect: the address that answered is the one
     * most likely to answer next time, and position is worth up to
     * [CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS] per address ahead
     * of it in the walk. Idempotent when [endpoint] is already the head, which
     * is the common case — callers compare against the original and skip the
     * write when nothing moved.
     *
     * [endpoint] is inserted if absent rather than ignored, so this is also
     * total for an address that came from outside the list.
     *
     * @param endpoint the address that just connected.
     * @return a copy with [endpoint] first and the remaining order preserved.
     */
    fun promoting(endpoint: HostPort): HostEntry =
        copy(addresses = listOf(endpoint) + (addresses - endpoint))

    companion object {
        /**
         * Ceiling on a stored address list. Not the same concern as
         * `PairingPayload.MAX_CANDIDATES` (which bounds what one QR can carry
         * and what a hostile QR could induce): this bounds what accumulates
         * across *many* pairings over time.
         *
         * It exists because [CandidateConnector] walks addresses sequentially
         * with a 12s timeout each, so every stale entry is up to 12 seconds of
         * spinner before a live one is reached. Without a cap, a laptop paired
         * at work, home, and a café collects dead addresses forever and
         * connects get slower every time.
         */
        const val MAX_STORED_ADDRESSES = 12

        /**
         * Merge the endpoints from a freshly-scanned QR into what an entry
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
         * previous order, and the tail past [MAX_STORED_ADDRESSES] is dropped:
         * those are the least-recently-seen networks, so they are the ones
         * least likely to be the current one.
         *
         * Called by the Android hosts screen's `handlePairingUri` and the iOS
         * `HostsViewModel.handlePairingUri` when a scan matches a known server.
         *
         * @param fresh endpoints from the scanned payload, in the server's
         *   suggested order.
         * @param existing the entry's current [addresses].
         * @return the merged list, deduplicated, newest-first, capped at
         *   [MAX_STORED_ADDRESSES].
         * @see CandidateConnector
         */
        fun mergeAddresses(fresh: List<HostPort>, existing: List<HostPort>): List<HostPort> =
            (fresh + existing).distinct().take(MAX_STORED_ADDRESSES)
    }
}

/**
 * Decodes host entries written in the old `host`/`port` + `candidates` format
 * into the current [HostEntry.addresses] shape.
 *
 * The old model kept the preferred endpoint in `host`/`port` and the rest of
 * the walk in a `candidates` list of `host[:port]` strings — which is the same
 * ordered address list [HostEntry.addresses] holds, just stored twice and in
 * two representations. So the migration is exactly the flattening every caller
 * used to do by hand: preferred first, then the candidates, deduplicated.
 *
 * Applied at the [se.soderbjorn.lunamux.client.storage.LocalState.hosts] use
 * site rather than on [HostEntry] itself, so it can delegate to the generated
 * serializer without recursing into itself.
 *
 * Migration happens on read and is made durable by the next write of any kind
 * (`local_state.json` is rewritten whole on every mutation), so no explicit
 * save-back is needed and a read-only launch simply migrates again. The old
 * keys are dropped from the object rather than left to `ignoreUnknownKeys`,
 * which keeps the migration total: nothing downstream can read a stale `host`.
 *
 * @see HostEntry.addresses
 */
object HostEntryMigratingSerializer : JsonTransformingSerializer<HostEntry>(HostEntry.serializer()) {

    /**
     * Rewrite a legacy entry's JSON into the current shape, leaving anything
     * already carrying `addresses` untouched.
     *
     * @param element one element of the `hosts` array as read from disk.
     * @return the element with `host`/`port`/`candidates` folded into
     *   `addresses`, or [element] unchanged when there is nothing to migrate.
     */
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        // Already current: `addresses` is authoritative and any legacy keys
        // beside it (an entry written by an older build after this one) are
        // stale by definition.
        if (obj["addresses"] != null) return element
        val host = (obj["host"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return element
        // The old `port` was non-null and doubled as the default for bare
        // candidate strings, so without it there is nothing to migrate *to* —
        // leave the entry to decode empty and be dropped.
        val port = (obj["port"] as? JsonPrimitive)?.intOrNull ?: return element
        val legacyCandidates = (obj["candidates"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val addresses = (
            listOf(HostPort(host, port)) +
                legacyCandidates.mapNotNull { HostPort.parseCandidate(it, port) }
            )
            .distinct()
            .take(HostEntry.MAX_STORED_ADDRESSES)
        return JsonObject(
            obj - "host" - "port" - "candidates" +
                ("addresses" to Json.encodeToJsonElement(ListSerializer(HostPort.serializer()), addresses)),
        )
    }
}
