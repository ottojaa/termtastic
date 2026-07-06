/**
 * Shared model and wire format for the QR device-pairing payload.
 *
 * The desktop server encodes a [PairingPayload] into a `termtastic://pair?…`
 * URI, renders it as a QR code in the pairing dialog, and the mobile client
 * decodes it after scanning (or when the OS routes a `termtastic://` deep link
 * to the app). The payload carries everything a client needs to connect with
 * no typing and no trust-on-first-use gamble: candidate endpoints, the
 * server's TLS certificate fingerprint, and a one-time pairing token.
 *
 * Both the encoder and parser are hand-rolled on pure kotlin-stdlib because
 * this module compiles to js/wasmJs where `java.net.URI` is unavailable.
 *
 * Also contains [HostPort], the shared `host[:port]` candidate-endpoint
 * representation used by the multi-candidate connect path in `:client`.
 */
package se.soderbjorn.termtastic

/**
 * One candidate endpoint a client can try when connecting to a server.
 *
 * Candidates travel inside [PairingPayload.candidates] (a server usually has
 * several LAN addresses) and are persisted client-side as `host[:port]`
 * strings on the host entry, in the order the server suggested trying them.
 *
 * @property host IP literal or hostname. IPv6 literals are stored *without*
 *   brackets; brackets are added only in the string form (see
 *   [toCandidateString]).
 * @property port TCP port of the server's TLS listener at this address.
 * @see PairingPayload
 */
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
        val h = if (host.contains(':')) "[$host]" else host
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
 * Wire format (`v=1`):
 * ```
 * termtastic://pair?v=1&h=<csv of host[:port]>&p=<default port>&fp=<64 hex>&t=<token>&n=<name>
 * ```
 *
 * - `h` — ordered, percent-encoded candidate endpoints; entries without a
 *   `:port` suffix default to `p`. IPv6 literals are bracketed.
 * - `p` — the default port for bare candidates.
 * - `fp` — lowercase-hex SHA-256 of the server's TLS leaf certificate. The
 *   client pins this from the very first connect (verify mode), which is
 *   strictly stronger than blind trust-on-first-use.
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
     * Encodes this payload as a `termtastic://pair?…` URI suitable for QR
     * rendering. Candidate entries equal to [defaultPort] omit their port.
     * [serverName] is dropped when including it would push the URI past
     * [MAX_LENGTH].
     *
     * @return the URI string; feed it to [parse] to get the payload back.
     */
    fun encode(): String {
        val sb = StringBuilder(URI_PREFIX)
        sb.append("v=").append(VERSION)
        sb.append("&h=").append(candidates.joinToString(",") { percentEncode(it.toCandidateString(defaultPort)) })
        sb.append("&p=").append(defaultPort)
        sb.append("&fp=").append(fingerprintHex.lowercase())
        sb.append("&t=").append(percentEncode(token))
        val name = serverName?.takeIf { it.isNotBlank() }?.let { percentEncode(it) }
        if (name != null && sb.length + "&n=".length + name.length <= MAX_LENGTH) {
            sb.append("&n=").append(name)
        }
        return sb.toString()
    }

    companion object {
        /** Payload format version emitted by [encode] and required by [parse]. */
        const val VERSION = 1

        /** URI scheme + path prefix every pairing payload starts with. */
        const val URI_PREFIX = "termtastic://pair?"

        /**
         * Soft ceiling on the encoded URI length. Beyond ~280 chars the QR
         * matrix gets dense enough that scanning at dialog size becomes
         * unreliable, so [encode] sheds the optional server name first.
         */
        const val MAX_LENGTH = 280

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
            val candidates = hRaw.split(',').map { entry ->
                val decoded = percentDecode(entry) ?: return null
                HostPort.parseCandidate(decoded, defaultPort) ?: return null
            }
            val fp = params["fp"]?.lowercase() ?: return null
            if (fp.length != 64 || fp.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
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
