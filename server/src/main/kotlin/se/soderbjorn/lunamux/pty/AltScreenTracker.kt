/**
 * Alternate-screen span tracker for recorded PTY output.
 *
 * This file contains [AltScreenTracker], a streaming state-machine parser that
 * partitions a PTY byte stream into *normal-buffer* and *alternate-buffer*
 * spans, so [se.soderbjorn.lunamux.TerminalSession] can keep the two in
 * separate ring buffers instead of interleaving them in one.
 *
 * Why the split matters: a replay ring is a raw byte ring with no notion of
 * terminal state, and a full-screen program (vim, htop, Claude Code) emits far
 * more redraw traffic than the ring can hold. Once the ring's head slides past
 * the `DECSET ?1049h` that entered the alternate buffer, the surviving redraw
 * bytes are *orphaned*: replaying them into a fresh terminal paints the TUI's
 * frame into the **normal** buffer, on top of the shell scrollback that should
 * be there. Tracking the spans lets the session route those bytes somewhere
 * they can never contaminate the scrollback.
 *
 * Boundary rule: the switching sequence itself belongs to the alternate span —
 * the enter (`?1049h`) opens it and the exit (`?1049l`) closes it, both
 * *inside*. This keeps the normal-buffer stream free of the cursor save/restore
 * those sequences imply, so a replay of it leaves the cursor exactly where the
 * shell left it rather than teleporting to a stale saved position.
 *
 * @see se.soderbjorn.lunamux.TerminalSession
 * @see ReplaySanitizer
 */
package se.soderbjorn.lunamux.pty

/**
 * Streaming parser that reports which alternate-screen span each byte of a PTY
 * stream belongs to.
 *
 * Designed to be fed in arbitrary chunks: a switching sequence split across two
 * [feed] calls is held back until it resolves, so its bytes are never routed to
 * the wrong span. Deferred bytes are always a partial escape sequence — inert
 * without their head — so a snapshot taken while bytes are pending loses
 * nothing renderable.
 *
 * Recognizes the three xterm private modes that swap the screen buffer: 1049
 * (save cursor + switch + clear, the modern default), 1047 (switch only) and
 * 47 (the legacy form). Mode 1048 is deliberately ignored — it saves and
 * restores the cursor without touching the buffer.
 *
 * @see AltScreenTracker.Sink
 */
internal class AltScreenTracker {

    /**
     * Receives the partitioned stream. Segments arrive in stream order and
     * together reproduce the input byte-for-byte, minus any bytes still
     * deferred inside an unresolved escape sequence.
     */
    interface Sink {
        /**
         * A run of bytes `chunk[from, until)` belonging to one span.
         *
         * @param alt true when these bytes are alternate-buffer traffic.
         */
        fun onSegment(chunk: ByteArray, from: Int, until: Int, alt: Boolean)

        /**
         * The alternate span just closed: the program returned to the normal
         * buffer, so everything reported with `alt = true` since it opened is
         * now dead pixels and may be discarded.
         */
        fun onSpanClosed()

        /**
         * An alternate-buffer *exit* was seen while no span was open — the
         * stream began part-way inside an alternate span whose enter sequence
         * is gone. Every byte reported before this point is orphaned TUI paint
         * rather than scrollback.
         */
        fun onOrphanExit()
    }

    /** Whether the stream is currently inside an alternate-buffer span. */
    var inAltScreen: Boolean = false
        private set

    private enum class State { IDLE, ESC, CSI }

    private var state = State.IDLE

    /**
     * Bytes of an in-flight escape sequence that started in an earlier [feed]
     * call. Replayed ahead of the current chunk once the sequence resolves.
     */
    private val pending = ByteArray(MAX_PENDING)
    private var pendingLen = 0

    /** Parameter bytes (`0x30..0x3F`) of the CSI sequence being scanned. */
    private val params = ByteArray(MAX_PARAMS)
    private var paramsLen = 0
    private var paramsOverflowed = false
    private var sawIntermediate = false

    /**
     * Feed [len] bytes from [chunk], reporting each span run to [sink].
     *
     * Called from the PTY read loop for every byte the session produces, and
     * from the restored-scrollback ingestion path (where the orphan rule
     * cleans blobs persisted before spans were tracked).
     *
     * @param chunk raw PTY output bytes
     * @param len number of valid bytes in [chunk] (defaults to `chunk.size`)
     * @param sink receives the partitioned output
     */
    fun feed(chunk: ByteArray, len: Int = chunk.size, sink: Sink) {
        // Start of the run of ordinary bytes not yet handed to the sink. A
        // sequence that turns out to switch buffers is emitted separately, so
        // the run is flushed at its introducer.
        var runStart = 0
        var i = 0
        while (i < len) {
            val b = chunk[i].toInt() and 0xFF
            when (state) {
                State.IDLE -> if (b == ESC) {
                    // Might introduce a switch: hold everything from here.
                    flushRun(chunk, runStart, i, sink)
                    runStart = i
                    state = State.ESC
                }
                State.ESC -> when (b) {
                    LBRACKET -> {
                        state = State.CSI
                        paramsLen = 0
                        paramsOverflowed = false
                        sawIntermediate = false
                    }
                    // ESC ESC: the previous ESC was not an introducer after
                    // all, but this one might be. Keep holding from the new one.
                    ESC -> {
                        flushRun(chunk, runStart, i, sink)
                        runStart = i
                    }
                    else -> state = State.IDLE
                }
                State.CSI -> when {
                    b in 0x30..0x3F -> {
                        if (paramsLen < MAX_PARAMS) params[paramsLen++] = b.toByte()
                        else paramsOverflowed = true
                    }
                    b in 0x20..0x2F -> sawIntermediate = true
                    b in 0x40..0x7E -> {
                        val end = i + 1
                        val switch = classifySwitch(b)
                        if (switch != null) {
                            emitSwitch(chunk, runStart, end, switch, sink)
                            runStart = end
                        }
                        state = State.IDLE
                    }
                    // Malformed CSI (e.g. an embedded control byte): abandon
                    // the scan and let the bytes flow through as ordinary run.
                    else -> state = State.IDLE
                }
            }
            i++
        }
        if (state == State.IDLE) {
            flushRun(chunk, runStart, len, sink)
        } else {
            // Mid-sequence at the chunk boundary: defer the tail so a switch
            // split across reads is still recognized as one unit.
            deferTail(chunk, runStart, len, sink)
        }
    }

    /**
     * Forget all state: no span open, no sequence in flight.
     *
     * Called after ingesting a persisted blob, so that a blob whose recording
     * stopped mid-span cannot misattribute the live stream's first bytes to an
     * alternate span that no running program owns.
     */
    fun reset() {
        inAltScreen = false
        state = State.IDLE
        pendingLen = 0
        paramsLen = 0
        paramsOverflowed = false
        sawIntermediate = false
    }

    /**
     * Classify a completed CSI sequence by its [final] byte.
     *
     * @return true to enter the alternate buffer, false to leave it, or null
     *   when the sequence is not a buffer switch.
     */
    private fun classifySwitch(final: Int): Boolean? {
        if (final != 'h'.code && final != 'l'.code) return null
        if (sawIntermediate || paramsOverflowed) return null
        if (paramsLen < 2 || params[0] != QUESTION) return null
        if (!hasSwitchingMode()) return null
        return final == 'h'.code
    }

    /**
     * True when any `;`-separated parameter after the `?` prefix is one of the
     * screen-swapping private modes (47, 1047, 1049).
     */
    private fun hasSwitchingMode(): Boolean {
        var value = 0
        var digits = 0
        var i = 1
        while (i <= paramsLen) {
            val c = if (i < paramsLen) params[i].toInt() and 0xFF else SEMICOLON_INT
            if (c == SEMICOLON_INT) {
                if (digits > 0 && (value == 47 || value == 1047 || value == 1049)) return true
                value = 0
                digits = 0
            } else if (c in DIGIT_0..DIGIT_9) {
                value = value * 10 + (c - DIGIT_0)
                digits++
            } else {
                // A non-digit, non-separator parameter byte (`<`, `=`, `>`, a
                // second `?`) means this is not a plain private-mode list.
                return false
            }
            i++
        }
        return false
    }

    /**
     * Emit a buffer-switch sequence spanning `chunk[from, until)` (plus any
     * deferred head) and update [inAltScreen].
     *
     * The sequence lands in the alternate span in both directions: the enter
     * opens the span, the exit is the span's last bytes.
     */
    private fun emitSwitch(chunk: ByteArray, from: Int, until: Int, enter: Boolean, sink: Sink) {
        if (enter) {
            if (!inAltScreen) inAltScreen = true
            flushRun(chunk, from, until, sink, alt = true)
        } else {
            val wasInAlt = inAltScreen
            flushRun(chunk, from, until, sink, alt = true)
            inAltScreen = false
            if (wasInAlt) sink.onSpanClosed() else sink.onOrphanExit()
        }
    }

    /**
     * Hand `chunk[from, until)` to [sink], prefixed by any deferred bytes.
     *
     * @param alt span to attribute the bytes to; defaults to the current one.
     */
    private fun flushRun(
        chunk: ByteArray,
        from: Int,
        until: Int,
        sink: Sink,
        alt: Boolean = inAltScreen,
    ) {
        if (pendingLen > 0) {
            sink.onSegment(pending, 0, pendingLen, alt)
            pendingLen = 0
        }
        if (until > from) sink.onSegment(chunk, from, until, alt)
    }

    /**
     * Stash `chunk[from, len)` until the next [feed] resolves the sequence it
     * belongs to. A sequence longer than [MAX_PENDING] is not a mode set, so
     * the scan is abandoned and the bytes are released.
     */
    private fun deferTail(chunk: ByteArray, from: Int, len: Int, sink: Sink) {
        val size = len - from
        if (pendingLen + size > MAX_PENDING) {
            flushRun(chunk, from, len, sink)
            state = State.IDLE
            return
        }
        System.arraycopy(chunk, from, pending, pendingLen, size)
        pendingLen += size
    }

    private companion object {
        /**
         * Cap on a held-back sequence. The longest switch we recognize is
         * ~10 bytes; the slack absorbs multi-parameter mode lists.
         */
        const val MAX_PENDING = 64
        const val MAX_PARAMS = 32

        const val ESC = 0x1B
        const val LBRACKET = '['.code
        const val SEMICOLON_INT = ';'.code
        const val DIGIT_0 = '0'.code
        const val DIGIT_9 = '9'.code
        val QUESTION = '?'.code.toByte()
    }
}
