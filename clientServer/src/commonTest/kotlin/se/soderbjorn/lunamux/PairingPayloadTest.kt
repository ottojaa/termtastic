/**
 * Tests for the QR pairing payload wire format ([PairingPayload], [HostPort]).
 *
 * Runs on every :clientServer target (jvm, js, wasmJs, android, ios) because
 * the encoder lives on the desktop server while the parser runs on mobile —
 * both sides must agree on every platform.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * `fp` travels as base64url but every caller compares hex, so the
     * conversion must be invisible: what goes in as hex comes back as the
     * same hex, and the field on the wire is the shorter form.
     */
    @Test
    fun fingerprintTravelsAsBase64UrlAndReturnsAsHex() {
        val encoded = samplePayload().encode()
        val fpField = encoded.substringAfter("&fp=").substringBefore("&")
        assertEquals(43, fpField.length, "SHA-256 as unpadded base64url is 43 chars")
        assertFalse(encoded.contains(fp), "the 64-char hex form must not be on the wire")
        assertEquals(fp, PairingPayload.parse(encoded)!!.fingerprintHex)
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
        val fpField = good.substringAfter("&fp=").substringBefore("&")
        assertNull(PairingPayload.parse("https://example.com/?v=${PairingPayload.VERSION}"))
        assertNull(PairingPayload.parse(good.replace("v=${PairingPayload.VERSION}", "v=99")))
        assertNull(PairingPayload.parse(good.replace("&t=", "&tt=")))
        // 41 chars cannot be unpadded base64url of anything (4n+1).
        assertNull(PairingPayload.parse(good.replace("&fp=$fpField", "&fp=${fpField.dropLast(2)}")))
        // Outside the base64url alphabet.
        assertNull(PairingPayload.parse(good.replace("&fp=$fpField", "&fp=${"*".repeat(43)}")))
        // Well-formed base64url, wrong digest length.
        assertNull(PairingPayload.parse(good.replace("&fp=$fpField", "&fp=AAAA")))
        assertNull(PairingPayload.parse(good.replace("&p=8443", "&p=99999")))
        assertNull(PairingPayload.parse(good.replace("&p=8443", "&p=abc")))
        // Truncated percent escape in the token.
        assertNull(PairingPayload.parse(good.dropLast(token.length) + "%A"))
        // Empty candidate entry.
        assertNull(PairingPayload.parse(good.replace("&h=", "&h=,")))
    }

    @Test
    fun tooManyCandidatesRejected() {
        val fpField = samplePayload(name = null).encode().substringAfter("&fp=").substringBefore("&")
        fun uriWith(n: Int): String {
            val hosts = (1..n).joinToString(",") { "10.0.0.$it" }
            return "${PairingPayload.URI_PREFIX}v=${PairingPayload.VERSION}" +
                "&h=$hosts&p=8443&fp=$fpField&t=$token"
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

    /**
     * A host with many VPN/container/VM interfaces can enumerate well past a
     * "handful" of addresses. encode() used to emit all of them, minting a QR
     * that parse() rejected outright as malformed (> MAX_CANDIDATES) — pairing
     * became impossible, reporting "that isn't a pairing code" about a code the
     * server itself had just produced. Whatever encode() is handed, its output
     * must be something parse() accepts.
     */
    @Test
    fun encodeNeverEmitsAPayloadItsOwnParserRejects() {
        val many = (1..40).map { HostPort("192.168.1.$it", 8443) }
        val encoded = samplePayload(candidates = many).encode()

        val parsed = PairingPayload.parse(encoded)
        assertNotNull(parsed, "encode() produced a URI that parse() rejects")
        assertTrue(parsed.candidates.size <= PairingPayload.MAX_CANDIDATES)
        assertTrue(
            encoded.length <= PairingPayload.MAX_LENGTH,
            "encoded URI is ${encoded.length} chars, past the scannable ceiling",
        )
    }

    /**
     * Bare IPv4 candidates are short enough that the [PairingPayload.MAX_CANDIDATES]
     * cap binds before the length ceiling — which only became true once `fp`
     * moved to base64url and handed back 21 characters. Before that the
     * ceiling bound first and a machine could not offer 12 addresses.
     */
    @Test
    fun ipv4CandidatesAreBoundedByTheCapNotTheLength() {
        val many = (1..40).map { HostPort("192.168.1.$it", 8443) }
        val encoded = samplePayload(candidates = many).encode()
        val parsed = PairingPayload.parse(encoded)
        assertNotNull(parsed)
        assertEquals(PairingPayload.MAX_CANDIDATES, parsed.candidates.size)
        assertTrue(encoded.length <= PairingPayload.MAX_LENGTH)
    }

    /**
     * Hostnames are long enough that the length ceiling still binds first, so
     * the tail-trim path stays exercised. This is the case that matters in
     * practice: a machine on a VPN offers resolved names alongside its IPs.
     */
    @Test
    fun hostnameCandidatesAreTrimmedToStayScannable() {
        val many = (1..12).map { HostPort("machine-$it.tailnet-example.ts.net", 8443) }
        val encoded = samplePayload(candidates = many).encode()
        val parsed = PairingPayload.parse(encoded)
        assertNotNull(parsed)
        assertTrue(
            parsed.candidates.size < PairingPayload.MAX_CANDIDATES,
            "expected the length ceiling to bind first, kept ${parsed.candidates.size}",
        )
        assertTrue(encoded.length <= PairingPayload.MAX_LENGTH)
    }

    /** Trimming is tail-first, so the most-reachable address keeps its slot. */
    @Test
    fun encodeKeepsLeadingCandidatesWhenTrimming() {
        val many = listOf(HostPort("192.168.1.5", 8443)) +
            (1..40).map { HostPort("10.99.99.$it", 8443) }
        val parsed = PairingPayload.parse(samplePayload(candidates = many).encode())
        assertNotNull(parsed)
        assertEquals(HostPort("192.168.1.5", 8443), parsed.candidates.first())
    }

    /**
     * A lone candidate too long for the ceiling is still emitted: an over-long
     * URI scans less reliably, but an endpoint-less one cannot work at all.
     */
    @Test
    fun encodeKeepsFirstCandidateEvenWhenOversized() {
        val huge = HostPort("a".repeat(300) + ".example.com", 8443)
        val parsed = PairingPayload.parse(samplePayload(candidates = listOf(huge)).encode())
        assertNotNull(parsed)
        assertEquals(1, parsed.candidates.size)
    }

    /** The optional name is shed before any candidate is. */
    @Test
    fun encodeShedsNameBeforeCandidates() {
        val many = (1..40).map { HostPort("192.168.1.$it", 8443) }
        val encoded = samplePayload(candidates = many, name = "Otto's Mac").encode()
        assertFalse(encoded.contains("&n="), "the name should be dropped before candidates are")
        assertTrue(PairingPayload.parse(encoded)!!.candidates.isNotEmpty())
    }

    /** Mirrors the payload's private percent-encoder for assertion strings. */
    private fun percent(s: String): String = s.replace(":", "%3a").replace("'", "%27")
}
