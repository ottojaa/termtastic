/**
 * Shared model and wire format for the QR device-pairing payload.
 *
 * The desktop server encodes a [PairingPayload] into a `lunamux://pair?…`
 * URI, renders it as a QR code in the pairing dialog, and the mobile client
 * decodes it after scanning (or when the OS routes a `lunamux://` deep link
 * to the app). The payload carries everything a client needs to connect with
 * no typing and no trust-on-first-use gamble: candidate endpoints, the
 * server's TLS certificate fingerprint, and a one-time pairing token.
 *
 * Both the encoder and parser are hand-rolled on pure kotlin-stdlib because
 * this module compiles to js/wasmJs where `java.net.URI` is unavailable.
 *
 * Also contains [HostPort], the shared endpoint representation used by the
 * multi-candidate connect path in `:client` and stored on the host entry.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.Serializable

/**
 * One endpoint a client can try when connecting to a server.
 *
 * Endpoints travel inside [PairingPayload.candidates] (a server usually has
 * several LAN addresses) and are persisted client-side, in order, as
 * [se.soderbjorn.lunamux.client.HostEntry.addresses].
 *
 * Serializable because it *is* the stored form: the host entry keeps a typed
 * list of these rather than `host[:port]` strings, so nothing has to reparse a
 * string to learn which port an address uses. [toCandidateString] and
 * [parseCandidate] survive only as the QR wire format and the text form the
 * host editor types in — both boundaries, not storage.
 *
 * @property host IP literal or hostname. IPv6 literals are stored *without*
 *   brackets; brackets are added only in the string form (see
 *   [toCandidateString]).
 * @property port TCP port of the server's TLS listener at this address.
 * @see PairingPayload
 * @see se.soderbjorn.lunamux.client.HostEntry
 */
@Serializable
data class HostPort(val host: String, val port: Int) {

    /**
     * Formats this endpoint as a candidate string: `host`, `host:port`,
     * `[v6]`, or `[v6]:port`. IPv6 literals are bracketed so the `:port`
     * suffix stays unambiguous.
     *
     * @param defaultPort when non-null and equal to [port], the port suffix
     *   is omitted to keep the QR payload compact; parsers fill it back in.
     * @return the candidate string form of this endpoint.
     * @see parseCandidate
     */
    fun toCandidateString(defaultPort: Int? = null): String {
        // Bracket only a bare IPv6 literal; leave an already-bracketed host
        // alone so we never emit "[[…]]" (matches ServerUrl.hostForUrl).
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == defaultPort) h else "$h:$port"
    }

    companion object {
        /**
         * Parses a candidate string produced by [toCandidateString] (or typed
         * by a user): `host`, `host:port`, `[v6]`, `[v6]:port`. As a
         * leniency, an unbracketed string containing more than one `:` is
         * treated as a raw IPv6 literal on [defaultPort] rather than
         * rejected.
         *
         * @param entry the candidate string; surrounding whitespace ignored.
         * @param defaultPort port to use when the entry has no `:port` suffix.
         * @return the parsed endpoint, or null when the entry is empty, has
         *   unbalanced brackets, or carries an out-of-range port.
         */
        fun parseCandidate(entry: String, defaultPort: Int): HostPort? {
            val s = entry.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close <= 1) return null
                val host = s.substring(1, close)
                val rest = s.substring(close + 1)
                return when {
                    rest.isEmpty() -> HostPort(host, defaultPort)
                    rest.startsWith(":") -> parsePortOrNull(rest.substring(1))?.let { HostPort(host, it) }
                    else -> null
                }
            }
            val firstColon = s.indexOf(':')
            if (firstColon == -1) return HostPort(s, defaultPort)
            if (s.indexOf(':', firstColon + 1) != -1) return HostPort(s, defaultPort)
            val host = s.substring(0, firstColon)
            if (host.isEmpty()) return null
            return parsePortOrNull(s.substring(firstColon + 1))?.let { HostPort(host, it) }
        }
    }
}

/**
 * The device-pairing payload carried by the QR code shown in the desktop
 * pairing dialog.
 *
 * Wire format (`v=0`, pre-release — see [PairingPayload.VERSION]):
 * ```
 * lunamux://pair?v=0&h=<csv of host[:port]>&p=<default port>&fp=<43 base64url>&t=<token>&n=<name>
 * ```
 *
 * - `h` — ordered, percent-encoded candidate endpoints; entries without a
 *   `:port` suffix default to `p`. IPv6 literals are bracketed.
 * - `p` — the default port for bare candidates.
 * - `fp` — SHA-256 of the server's TLS leaf certificate as unpadded base64url
 *   (43 chars, vs 64 for hex — the field is a fifth of the budget, so the
 *   encoding is worth the conversion). Exposed to callers as hex via
 *   [fingerprintHex]. The client pins this from the very first connect
 *   (verify mode), which is strictly stronger than blind trust-on-first-use.
 * - `t` — single-use pairing token minted while the pairing dialog is open;
 *   possession proves the user is looking at the server's screen, so the
 *   server trusts the device without an approval dialog.
 * - `n` — optional human-readable server name; dropped by [encode] when the
 *   payload would exceed [MAX_LENGTH] (QR codes get hard to scan past that).
 *
 * @property candidates ordered endpoints the client should try; first
 *   reachable wins.
 * @property defaultPort port assumed for candidates without an explicit one.
 * @property fingerprintHex lowercase-hex SHA-256 of the server's TLS leaf.
 * @property token the one-time pairing token, as minted (un-hashed).
 * @property serverName optional display name for the new host entry.
 * @see HostPort
 */
data class PairingPayload(
    val candidates: List<HostPort>,
    val defaultPort: Int,
    val fingerprintHex: String,
    val token: String,
    val serverName: String? = null,
) {

    /**
     * Encodes this payload as a `lunamux://pair?…` URI suitable for QR
     * rendering. Candidate entries equal to [defaultPort] omit their port.
     *
     * The output is always something [parse] accepts and a scanner can read,
     * whatever it is handed: the list is capped at [MAX_CANDIDATES] (beyond
     * which [parse] rejects the payload outright) and then trimmed until the
     * URI fits [MAX_LENGTH]. Enforcing both here rather than in the caller
     * means every producer is covered — a host with a pile of VPN, container,
     * and VM interfaces cannot mint a QR its own client refuses to read.
     *
     * Shedding order is cheapest-loss-first: the optional [serverName] goes
     * before any candidate, and candidates are dropped from the tail, so put
     * the most-reachable endpoint first. The leading candidate is always kept
     * — a payload with no endpoint is useless, and an over-long URI still
     * scans (just less reliably) whereas an empty one cannot work at all.
     *
     * @return the URI string; feed it to [parse] to get the payload back.
     * @see MAX_CANDIDATES
     * @see MAX_LENGTH
     */
    fun encode(): String {
        val head = "${URI_PREFIX}v=$VERSION&h="
        // p/fp/t are mandatory and fixed-width, so their cost is known up
        // front and every trim decision below is measured against it.
        val fpBytes = hexToBytes(fingerprintHex.lowercase())
        require(fpBytes != null && fpBytes.size == FINGERPRINT_BYTES) {
            "fingerprintHex must be $FINGERPRINT_BYTES bytes of hex"
        }
        val tail = StringBuilder()
            .append("&p=").append(defaultPort)
            .append("&fp=").append(base64UrlEncode(fpBytes))
            .append("&t=").append(percentEncode(token))
            .toString()

        val kept = StringBuilder()
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val encoded = percentEncode(candidate.toCandidateString(defaultPort))
            val addition = if (kept.isEmpty()) encoded.length else encoded.length + 1
            if (kept.isNotEmpty() && head.length + kept.length + addition + tail.length > MAX_LENGTH) break
            if (kept.isNotEmpty()) kept.append(',')
            kept.append(encoded)
        }

        val sb = StringBuilder(head).append(kept).append(tail)
        val name = serverName?.takeIf { it.isNotBlank() }?.let { percentEncode(it) }
        if (name != null && sb.length + "&n=".length + name.length <= MAX_LENGTH) {
            sb.append("&n=").append(name)
        }
        return sb.toString()
    }

    companion object {
        /**
         * Payload format version emitted by [encode] and required by [parse].
         *
         * 0 means pre-release: the format is still free to change, because
         * nothing in the wild parses it. Bump to 1 in the release that first
         * puts QR pairing in front of a user; after that, every format change
         * costs a version and needs a compatibility story.
         */
        const val VERSION = 0

        /** SHA-256 digest length; the only [PairingPayload.fingerprintHex] size accepted. */
        const val FINGERPRINT_BYTES = 32

        /** URI scheme + path prefix every pairing payload starts with. */
        const val URI_PREFIX = "lunamux://pair?"

        /**
         * Ceiling on the encoded URI length. Beyond ~280 chars the QR matrix
         * gets dense enough that scanning at dialog size becomes unreliable,
         * so [encode] sheds the optional server name first and then trailing
         * candidates. The fixed fields (`fp` is 68 chars, `t` about 46) eat
         * roughly half of this, leaving room for ~9 IPv4 candidates.
         */
        const val MAX_LENGTH = 280

        /**
         * Hard cap on candidate endpoints, enforced on both sides: [encode]
         * emits no more, and [parse] rejects a payload carrying more.
         *
         * On the parse side it bounds the work a *hostile* QR could induce —
         * each candidate costs the phone up to a ~12 s WebSocket handshake
         * attempt, so an unbounded list would turn a scan into an arbitrary
         * host:port probe sweep.
         *
         * On the encode side it keeps a legitimate server from tripping that
         * same check: a host with many VPN/container/VM interfaces can enumerate
         * well past a "handful", and emitting them all would mint a QR this
         * very parser rejects as malformed.
         */
        const val MAX_CANDIDATES = 12

        /**
         * Parses a scanned/deep-linked URI back into a [PairingPayload].
         * Unknown query parameters are ignored so older apps tolerate future
         * additive fields; anything structurally wrong yields null rather
         * than an exception (scanner input is untrusted).
         *
         * @param uri the raw string from the QR scanner or deep-link intent.
         * @return the payload, or null when the scheme, version, candidates,
         *   port, fingerprint, or token are missing or malformed.
         */
        fun parse(uri: String): PairingPayload? {
            if (!uri.startsWith(URI_PREFIX)) return null
            val params = mutableMapOf<String, String>()
            for (piece in uri.removePrefix(URI_PREFIX).split('&')) {
                if (piece.isEmpty()) continue
                val eq = piece.indexOf('=')
                if (eq <= 0) return null
                params[piece.substring(0, eq)] = piece.substring(eq + 1)
            }
            if (params["v"] != VERSION.toString()) return null
            val defaultPort = params["p"]?.let(::parsePortOrNull) ?: return null
            val hRaw = params["h"]?.takeIf { it.isNotEmpty() } ?: return null
            val entries = hRaw.split(',')
            if (entries.size > MAX_CANDIDATES) return null
            val candidates = entries.map { entry ->
                val decoded = percentDecode(entry) ?: return null
                HostPort.parseCandidate(decoded, defaultPort) ?: return null
            }
            val fpBytes = params["fp"]?.let(::base64UrlDecode) ?: return null
            if (fpBytes.size != FINGERPRINT_BYTES) return null
            val fp = bytesToHex(fpBytes)
            val token = params["t"]?.let(::percentDecode)?.takeIf { it.isNotBlank() } ?: return null
            val name = params["n"]?.let(::percentDecode)?.takeIf { it.isNotBlank() }
            return PairingPayload(candidates, defaultPort, fp, token, name)
        }
    }
}

/**
 * Parses a decimal TCP port, returning null unless the text is 1-5 digits
 * and the value falls in 1..65535.
 *
 * @param text the digits to parse (no sign, no whitespace).
 * @return the port number, or null when out of range or non-numeric.
 */
private fun parsePortOrNull(text: String): Int? {
    if (text.isEmpty() || text.length > 5 || text.any { it !in '0'..'9' }) return null
    val port = text.toInt()
    return if (port in 1..65535) port else null
}

/** RFC 4648 §5 base64url alphabet. No padding: `=` would cost characters. */
private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * Encodes [bytes] as unpadded base64url.
 *
 * Hand-rolled for the same reason as [percentEncode]: this module compiles to
 * js/wasmJs, where `java.util.Base64` does not exist. Used only for `fp`,
 * where it costs 43 characters against hex's 64 — a fifth of [PairingPayload.MAX_LENGTH]
 * bought back, which is the difference between the server name fitting in the
 * QR and being silently shed.
 *
 * Every output character is URL-safe, so the result never needs percent-encoding.
 *
 * @param bytes the raw bytes.
 * @return the unpadded base64url text; reversible via [base64UrlDecode].
 */
private fun base64UrlEncode(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xff) shl 16) or
            ((bytes[i + 1].toInt() and 0xff) shl 8) or
            (bytes[i + 2].toInt() and 0xff)
        sb.append(B64URL[(n ushr 18) and 0x3f])
        sb.append(B64URL[(n ushr 12) and 0x3f])
        sb.append(B64URL[(n ushr 6) and 0x3f])
        sb.append(B64URL[n and 0x3f])
        i += 3
    }
    // Tail: 1 leftover byte yields 2 chars, 2 bytes yield 3. Never padded.
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xff) shl 16
            sb.append(B64URL[(n ushr 18) and 0x3f])
            sb.append(B64URL[(n ushr 12) and 0x3f])
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            sb.append(B64URL[(n ushr 18) and 0x3f])
            sb.append(B64URL[(n ushr 12) and 0x3f])
            sb.append(B64URL[(n ushr 6) and 0x3f])
        }
    }
    return sb.toString()
}

/**
 * Reverses [base64UrlEncode]. Returns null rather than throwing on any
 * character outside [B64URL] or a length that cannot come from unpadded
 * base64url, because input comes from untrusted QR scans.
 *
 * Standard base64's `+` and `/` are rejected too: nothing this codebase emits
 * uses them, so accepting them would only widen what a hostile QR can feed in.
 *
 * @param value the unpadded base64url text.
 * @return the decoded bytes, or null when malformed.
 */
private fun base64UrlDecode(value: String): ByteArray? {
    // A 4n+1 length is unreachable: no byte count encodes to it.
    if (value.length % 4 == 1) return null
    val out = ArrayList<Byte>(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in value) {
        val v = B64URL.indexOf(c)
        if (v < 0) return null
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer ushr bits) and 0xff).toByte())
        }
    }
    return out.toByteArray()
}

/**
 * Parses an even-length hex string into bytes.
 *
 * @param hex lowercase or uppercase hex, no prefix or separators.
 * @return the bytes, or null on odd length or a non-hex character.
 */
private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = hexValue(hex[i * 2])
        val lo = hexValue(hex[i * 2 + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = (((hi shl 4) or lo) and 0xff).toByte()
    }
    return out
}

/**
 * Renders [bytes] as lowercase hex — the form every caller of
 * [PairingPayload.fingerprintHex] compares against, so `fp` is decoded back
 * to it at the parse boundary and base64url never leaks past this file.
 *
 * @param bytes the raw bytes.
 * @return lowercase hex, two characters per byte.
 */
private fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
        sb.append(HEX[b.toInt() and 0x0f])
    }
    return sb.toString()
}

/**
 * Percent-encodes [value] for embedding in the pairing URI query. Only RFC
 * 3986 unreserved ASCII stays literal; everything else (including non-ASCII,
 * which `Char.isLetterOrDigit` would wrongly pass through) becomes `%XX`
 * UTF-8 escapes. Kept file-private: the payload is the module's only URI.
 *
 * @param value the raw string.
 * @return the escaped form; reversible via [percentDecode].
 */
private fun percentEncode(value: String): String {
    val sb = StringBuilder(value.length)
    for (b in value.encodeToByteArray()) {
        val c = b.toInt().toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' ->
                sb.append(c)
            else -> {
                sb.append('%')
                sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
                sb.append(HEX[b.toInt() and 0x0f])
            }
        }
    }
    return sb.toString()
}

/**
 * Reverses [percentEncode]. Returns null on truncated or non-hex `%XX`
 * escapes instead of throwing, because input comes from untrusted QR scans.
 *
 * @param value the escaped string.
 * @return the decoded string, or null when an escape sequence is malformed.
 */
private fun percentDecode(value: String): String? {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            if (i + 2 >= value.length) return null
            val hi = hexValue(value[i + 1])
            val lo = hexValue(value[i + 2])
            if (hi < 0 || lo < 0) return null
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            for (b in c.toString().encodeToByteArray()) bytes.add(b)
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * Maps a hex digit (either case) to its value, or -1 when not a hex digit.
 *
 * @param c the character to interpret.
 * @return 0..15, or -1 for non-hex input.
 */
private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private val HEX = "0123456789abcdef".toCharArray()
