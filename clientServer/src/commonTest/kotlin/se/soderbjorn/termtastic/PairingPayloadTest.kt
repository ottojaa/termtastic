/**
 * Tests for the QR pairing payload wire format ([PairingPayload], [HostPort]).
 *
 * Runs on every :clientServer target (jvm, js, wasmJs, android, ios) because
 * the encoder lives on the desktop server while the parser runs on mobile —
 * both sides must agree on every platform.
 */
package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingPayloadTest {

    private val fp = "ab".repeat(32)
    private val token = "u0aXbeIhbmVYq4EW7HZWDPnhhVYD4K2Bv7VgY1yu3Wk"

    private fun samplePayload(
        candidates: List<HostPort> = listOf(
            HostPort("192.168.1.5", 8443),
            HostPort("10.0.0.7", 8443),
        ),
        name: String? = "Otto's Mac",
    ) = PairingPayload(
        candidates = candidates,
        defaultPort = 8443,
        fingerprintHex = fp,
        token = token,
        serverName = name,
    )

    @Test
    fun roundTripBasic() {
        val payload = samplePayload()
        assertEquals(payload, PairingPayload.parse(payload.encode()))
    }

    @Test
    fun roundTripExplicitPortAndIpv6() {
        val payload = samplePayload(
            candidates = listOf(
                HostPort("192.168.1.5", 8443),
                HostPort("my-vps.example.com", 9443),
                HostPort("2001:db8::1", 8443),
                HostPort("2001:db8::2", 9001),
            ),
        )
        val encoded = payload.encode()
        // Default-port entries stay bare; explicit ports survive; v6 brackets.
        assertTrue(encoded.contains("192.168.1.5,"))
        assertTrue(encoded.contains(percent("my-vps.example.com:9443")))
        assertEquals(payload, PairingPayload.parse(encoded))
    }

    @Test
    fun roundTripNonAsciiName() {
        val payload = samplePayload(name = "Åke's Mäc 🖥")
        assertEquals(payload, PairingPayload.parse(payload.encode()))
    }

    @Test
    fun nameDroppedWhenPayloadWouldExceedMax() {
        val payload = samplePayload(
            candidates = (1..4).map { HostPort("192.168.100.10$it", 60000 + it) },
            name = "x".repeat(120),
        )
        val encoded = payload.encode()
        assertTrue(encoded.length <= PairingPayload.MAX_LENGTH)
        assertFalse(encoded.contains("&n="))
        assertEquals(payload.copy(serverName = null), PairingPayload.parse(encoded))
    }

    @Test
    fun unknownParamsIgnored() {
        val encoded = samplePayload(name = null).encode() + "&future=stuff&x=1"
        assertEquals(samplePayload(name = null), PairingPayload.parse(encoded))
    }

    @Test
    fun malformedInputsReturnNull() {
        val good = samplePayload(name = null).encode()
        assertNull(PairingPayload.parse("https://example.com/?v=1"))
        assertNull(PairingPayload.parse(good.replace("v=1", "v=2")))
        assertNull(PairingPayload.parse(good.replace("&t=", "&tt=")))
        assertNull(PairingPayload.parse(good.replace("&fp=$fp", "&fp=${fp.dropLast(2)}")))
        assertNull(PairingPayload.parse(good.replace("&fp=$fp", "&fp=${"zz".repeat(32)}")))
        assertNull(PairingPayload.parse(good.replace("&p=8443", "&p=99999")))
        assertNull(PairingPayload.parse(good.replace("&p=8443", "&p=abc")))
        // Truncated percent escape in the token.
        assertNull(PairingPayload.parse(good.dropLast(token.length) + "%A"))
        // Empty candidate entry.
        assertNull(PairingPayload.parse(good.replace("&h=", "&h=,")))
    }

    @Test
    fun tooManyCandidatesRejected() {
        fun uriWith(n: Int): String {
            val hosts = (1..n).joinToString(",") { "10.0.0.$it" }
            return "${PairingPayload.URI_PREFIX}v=1&h=$hosts&p=8443&fp=$fp&t=$token"
        }
        // Exactly the cap parses; one past it is treated as malformed (null),
        // which bounds the host:port probe sweep a hostile QR could induce.
        assertEquals(
            PairingPayload.MAX_CANDIDATES,
            PairingPayload.parse(uriWith(PairingPayload.MAX_CANDIDATES))!!.candidates.size,
        )
        assertNull(PairingPayload.parse(uriWith(PairingPayload.MAX_CANDIDATES + 1)))
    }

    @Test
    fun candidateParsingVariants() {
        assertEquals(HostPort("192.168.1.5", 8443), HostPort.parseCandidate("192.168.1.5", 8443))
        assertEquals(HostPort("192.168.1.5", 9001), HostPort.parseCandidate("192.168.1.5:9001", 8443))
        assertEquals(HostPort("2001:db8::1", 8443), HostPort.parseCandidate("[2001:db8::1]", 8443))
        assertEquals(HostPort("2001:db8::1", 9001), HostPort.parseCandidate("[2001:db8::1]:9001", 8443))
        // Lenient: unbracketed v6 → whole string is the host, default port.
        assertEquals(HostPort("2001:db8::1", 8443), HostPort.parseCandidate("2001:db8::1", 8443))
        assertNull(HostPort.parseCandidate("", 8443))
        assertNull(HostPort.parseCandidate("[2001:db8::1", 8443))
        assertNull(HostPort.parseCandidate("[2001:db8::1]x", 8443))
        assertNull(HostPort.parseCandidate("host:0", 8443))
        assertNull(HostPort.parseCandidate("host:65536", 8443))
        assertNull(HostPort.parseCandidate(":8443", 8443))
    }

    @Test
    fun candidateFormattingBracketsAndDefaultPort() {
        assertEquals("192.168.1.5", HostPort("192.168.1.5", 8443).toCandidateString(8443))
        assertEquals("192.168.1.5:9001", HostPort("192.168.1.5", 9001).toCandidateString(8443))
        assertEquals("[2001:db8::1]:9001", HostPort("2001:db8::1", 9001).toCandidateString(8443))
        assertEquals("[2001:db8::1]", HostPort("2001:db8::1", 8443).toCandidateString(8443))
        assertEquals("192.168.1.5:8443", HostPort("192.168.1.5", 8443).toCandidateString(null))
        // An already-bracketed host must not be double-bracketed (regression).
        assertEquals("[2001:db8::1]:9001", HostPort("[2001:db8::1]", 9001).toCandidateString(8443))
        assertEquals(
            HostPort("2001:db8::1", 9001),
            HostPort.parseCandidate(HostPort("[2001:db8::1]", 9001).toCandidateString(8443), 8443),
        )
    }

    /** Mirrors the payload's private percent-encoder for assertion strings. */
    private fun percent(s: String): String = s.replace(":", "%3a").replace("'", "%27")
}
