/**
 * Tests for the saved-host model ([HostEntry]), its address-list helpers, and
 * the [HostEntryMigratingSerializer] that reads entries written in the old
 * `host`/`port` + `candidates` format.
 *
 * The migration is the load-bearing part: it runs exactly once per user, on a
 * file this code can no longer produce, so a bug in it is invisible in
 * development and silently costs a real user their saved servers.
 */
package se.soderbjorn.lunamux.client

import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.client.storage.LocalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Matches the repository's decoder — old keys must be tolerated, not fatal. */
private val json = Json { ignoreUnknownKeys = true }

class HostEntryMigrationTest {

    /**
     * The core migration: the old preferred `host`/`port` becomes the head of
     * [HostEntry.addresses] and the old `candidates` follow it in order.
     */
    @Test
    fun migratesLegacyHostPortAndCandidates() {
        val text = """
            {
              "hosts": [
                {
                  "id": "a",
                  "label": "Work Mac",
                  "host": "192.168.1.5",
                  "port": 8443,
                  "pinnedFingerprintHex": "abc123",
                  "candidates": ["10.0.0.7", "mac.tail-abc.ts.net:9001"],
                  "pairingToken": "tok"
                }
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(
            listOf(
                HostPort("192.168.1.5", 8443),
                // Bare candidate inherits the old `port`, which was its default.
                HostPort("10.0.0.7", 8443),
                HostPort("mac.tail-abc.ts.net", 9001),
            ),
            entry.addresses,
        )
        // Everything not addressing-related survives untouched.
        assertEquals("Work Mac", entry.label)
        assertEquals("abc123", entry.pinnedFingerprintHex)
        assertEquals("tok", entry.pairingToken)
    }

    /**
     * The old `host`/`port` was also the head of the walk, so it is usually
     * duplicated in `candidates`. Migrating must not leave the address twice —
     * a duplicate is a second full connect timeout on the same dead endpoint.
     */
    @Test
    fun migrationDeduplicatesPreferredAgainstCandidates() {
        val text = """
            {
              "hosts": [
                {
                  "id": "a",
                  "label": "Mac",
                  "host": "192.168.1.5",
                  "port": 8443,
                  "candidates": ["192.168.1.5:8443", "10.0.0.7"]
                }
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(
            listOf(HostPort("192.168.1.5", 8443), HostPort("10.0.0.7", 8443)),
            entry.addresses,
        )
    }

    /** A manually-added legacy host had no candidates at all. */
    @Test
    fun migratesLegacyHostWithNoCandidates() {
        val text = """
            {"hosts":[{"id":"a","label":"Mac","host":"mac.local","port":9443}]}
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(listOf(HostPort("mac.local", 9443)), entry.addresses)
    }

    /** Legacy IPv6 candidates are bracketed; they must survive the round trip. */
    @Test
    fun migratesLegacyIpv6Candidates() {
        val text = """
            {
              "hosts": [
                {
                  "id": "a",
                  "label": "Mac",
                  "host": "2001:db8::1",
                  "port": 8443,
                  "candidates": ["[2001:db8::2]:9001"]
                }
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(
            listOf(HostPort("2001:db8::1", 8443), HostPort("2001:db8::2", 9001)),
            entry.addresses,
        )
    }

    /**
     * A legacy entry that has accumulated addresses across many pairings must
     * not migrate into a list longer than the walk's cap, or the first connect
     * after upgrading would be slower than the last one before it.
     */
    @Test
    fun migrationCapsAddressesAtMaxStored() {
        val candidates = (1..40).joinToString(",") { "\"10.0.0.$it\"" }
        val text = """
            {"hosts":[{"id":"a","label":"Mac","host":"192.168.1.5","port":8443,
              "candidates":[$candidates]}]}
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(HostEntry.MAX_STORED_ADDRESSES, entry.addresses.size)
        // The preferred address keeps its place at the head of the walk.
        assertEquals(HostPort("192.168.1.5", 8443), entry.addresses.first())
    }

    /** An unparseable legacy candidate is dropped without taking the entry with it. */
    @Test
    fun migrationSkipsUnparseableCandidates() {
        val text = """
            {"hosts":[{"id":"a","label":"Mac","host":"mac.local","port":8443,
              "candidates":["","host:0","10.0.0.7"]}]}
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(listOf(HostPort("mac.local", 8443), HostPort("10.0.0.7", 8443)), entry.addresses)
    }

    /** Current-format entries must pass through the serializer unchanged. */
    @Test
    fun leavesCurrentFormatUntouched() {
        val text = """
            {
              "hosts": [
                {
                  "id": "a",
                  "label": "Mac",
                  "addresses": [
                    {"host": "10.0.0.7", "port": 8443},
                    {"host": "192.168.1.5", "port": 9001}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString<LocalState>(text).hosts.single()

        assertEquals(
            listOf(HostPort("10.0.0.7", 8443), HostPort("192.168.1.5", 9001)),
            entry.addresses,
        )
    }

    /**
     * The rest of `local_state.json` shares the blob with the host list, so a
     * legacy file must not cost the user their onboarding state or news
     * dismissals on upgrade.
     */
    @Test
    fun migrationPreservesUnrelatedLocalState() {
        val text = """
            {
              "hosts": [{"id":"a","label":"Mac","host":"mac.local","port":8443}],
              "onboardingSeen": true,
              "dismissedNewsIds": ["n1"],
              "dismissedUpdateVersionCode": 42,
              "lastCheckEpochMillis": 1700000000000
            }
        """.trimIndent()

        val state = json.decodeFromString<LocalState>(text)

        assertTrue(state.onboardingSeen)
        assertEquals(setOf("n1"), state.dismissedNewsIds)
        assertEquals(42L, state.dismissedUpdateVersionCode)
        assertEquals(1700000000000L, state.lastCheckEpochMillis)
    }

    /**
     * An entry with neither `addresses` nor a legacy `host` decodes to an empty
     * address list rather than failing the whole blob — the repository drops it
     * at that point. Decoding must not throw, or one bad entry would wipe the
     * unrelated state above.
     */
    @Test
    fun entryWithNothingToMigrateDecodesEmptyRatherThanThrowing() {
        val text = """{"hosts":[{"id":"a","label":"Mac"}],"onboardingSeen":true}"""

        val state = json.decodeFromString<LocalState>(text)

        assertEquals(emptyList(), state.hosts.single().addresses)
        assertTrue(state.onboardingSeen)
    }

    /** Round-tripping a current entry through encode/decode is stable. */
    @Test
    fun currentFormatRoundTrips() {
        val state = LocalState(
            hosts = listOf(
                HostEntry(
                    id = "a",
                    label = "Mac",
                    addresses = listOf(HostPort("10.0.0.7", 8443), HostPort("mac.local", 9001)),
                    pinnedFingerprintHex = "abc",
                ),
            ),
        )

        val decoded = json.decodeFromString<LocalState>(
            json.encodeToString(LocalState.serializer(), state),
        )

        assertEquals(state, decoded)
    }
}

class HostEntryAddressListTest {

    private fun entry(vararg addresses: HostPort) =
        HostEntry(id = "a", label = "Mac", addresses = addresses.toList())

    /** The winner leads the next walk; the rest keep their relative order. */
    @Test
    fun promotingMovesEndpointToFront() {
        val e = entry(HostPort("a", 1), HostPort("b", 2), HostPort("c", 3))

        assertEquals(
            listOf(HostPort("c", 3), HostPort("a", 1), HostPort("b", 2)),
            e.promoting(HostPort("c", 3)).addresses,
        )
    }

    /**
     * Promoting the head is the common case — it must be a no-op so callers
     * comparing against the original skip the write.
     */
    @Test
    fun promotingHeadIsIdentity() {
        val e = entry(HostPort("a", 1), HostPort("b", 2))

        assertEquals(e, e.promoting(HostPort("a", 1)))
    }

    /** An address from outside the list is inserted rather than ignored. */
    @Test
    fun promotingUnknownEndpointInsertsIt() {
        val e = entry(HostPort("a", 1))

        assertEquals(
            listOf(HostPort("z", 9), HostPort("a", 1)),
            e.promoting(HostPort("z", 9)).addresses,
        )
    }

    /** Same host, different port is a different endpoint. */
    @Test
    fun promotingDistinguishesPort() {
        val e = entry(HostPort("a", 1), HostPort("a", 2))

        assertEquals(
            listOf(HostPort("a", 2), HostPort("a", 1)),
            e.promoting(HostPort("a", 2)).addresses,
        )
    }

    @Test
    fun primaryIsHeadOfAddresses() {
        assertEquals(HostPort("a", 1), entry(HostPort("a", 1), HostPort("b", 2)).primary)
        assertEquals(null, entry().primary)
    }

    /** Re-pairing adds a network; the scanned set leads, the old order follows. */
    @Test
    fun mergeAddressesPutsFreshFirstAndKeepsExisting() {
        val merged = HostEntry.mergeAddresses(
            fresh = listOf(HostPort("new", 1)),
            existing = listOf(HostPort("old1", 1), HostPort("old2", 2)),
        )

        assertEquals(listOf(HostPort("new", 1), HostPort("old1", 1), HostPort("old2", 2)), merged)
    }

    /** Re-pairing on a known network must not duplicate its address. */
    @Test
    fun mergeAddressesDeduplicates() {
        val merged = HostEntry.mergeAddresses(
            fresh = listOf(HostPort("a", 1)),
            existing = listOf(HostPort("a", 1), HostPort("b", 2)),
        )

        assertEquals(listOf(HostPort("a", 1), HostPort("b", 2)), merged)
    }

    /** The least-recently-seen tail is what gets dropped at the cap. */
    @Test
    fun mergeAddressesCapsAtMaxStored() {
        val merged = HostEntry.mergeAddresses(
            fresh = (1..8).map { HostPort("fresh$it", it) },
            existing = (1..20).map { HostPort("old$it", it) },
        )

        assertEquals(HostEntry.MAX_STORED_ADDRESSES, merged.size)
        assertEquals(HostPort("fresh1", 1), merged.first())
    }
}
