/**
 * Recognises a program declaring that it is about to redraw the entire visible screen.
 *
 * This file contains [RepaintDeclaration], a small scanner over the leading control
 * sequences of a PTY output chunk. It exists so the canonical grid can distinguish
 * "this program owns the whole screen and is rewriting it" from "this is ordinary
 * output" without guessing — the distinction decides whether a resize's archival into
 * scrollback was redundant and should be withdrawn.
 *
 * @see SessionGrid.feed the sole caller, on the first chunk after a resize
 * @see se.soderbjorn.lunamux.pty.TakeOverRepaintTest the behaviour this enables
 */
package se.soderbjorn.lunamux.pty

/**
 * Classifies the start of a PTY output chunk as a full-screen repaint or not.
 *
 * A repaint is declared when, before the program draws anything, it erases the whole
 * screen. Three spellings are accepted, all of which mean the same thing:
 *
 *  - `ESC[2J` — erase the entire display.
 *  - home (`ESC[H`, `ESC[1;1H`, …) followed by `ESC[J` / `ESC[0J` — erase from the top
 *    of the screen to the end of the display.
 *  - home followed by one `ESC[2K` (erase line) per screen row — the form Claude Code's
 *    renderer uses. Captured live: every post-SIGWINCH chunk opened `ESC[?25l` … `ESC[H`
 *    then exactly `rows` × (`ESC[2K` `ESC[1B`), across 22 consecutive resizes in both
 *    directions, with the count tracking the new screen height each time.
 *
 * Cursor moves, mode sets (`ESC[?25l`), colour changes and OSC strings are all
 * transparent — they neither declare nor disqualify. Anything that puts a glyph on the
 * screen, or a line feed (which scrolls, and so commits a row to history), ends the
 * prologue: past that point the program has started drawing and its intent is settled.
 *
 * Deliberately conservative in both directions. A false positive would withdraw rows the
 * program never redraws, so the sequence must be explicit and complete before any output;
 * a false negative merely leaves today's duplicate in place.
 */
internal object RepaintDeclaration {

    /**
     * Does [buf] `[0, len)` open by erasing the whole screen?
     *
     * Called from [SessionGrid.feed] for the first chunk after a resize.
     *
     * @param buf raw PTY output bytes.
     * @param len number of valid bytes in [buf].
     * @param screenRows the current screen height, used as the threshold for the
     *   erase-every-line form.
     * @return true when the chunk declares a full-screen repaint before drawing anything.
     */
    fun declaresFullScreenRepaint(buf: ByteArray, len: Int, screenRows: Int): Boolean {
        var i = 0
        var homed = false
        var erasedLines = 0
        while (i < len) {
            val b = buf[i].toInt() and 0xff
            when {
                b == ESC -> {
                    val seq = readEscape(buf, i, len) ?: return false
                    when (classify(buf, i, seq)) {
                        Kind.ERASE_DISPLAY_ALL -> return true
                        Kind.ERASE_DISPLAY_TO_END -> if (homed) return true
                        Kind.HOME -> {
                            homed = true
                            // A second home restarts the erase run rather than continuing it;
                            // the count that matters is the one immediately before drawing.
                            erasedLines = 0
                        }
                        Kind.ERASE_LINE_ALL -> {
                            if (!homed) return false
                            if (++erasedLines >= screenRows) return true
                        }
                        Kind.NEUTRAL -> Unit
                    }
                    i = seq
                }
                // Carriage return and NUL move nothing into history and draw nothing.
                b == CR || b == 0 -> i++
                // Anything else — a glyph, a line feed, a tab — is the program drawing.
                else -> return false
            }
        }
        // The chunk ended mid-prologue. Treat that as undeclared: a partial erase run is
        // exactly the case where guessing is unsafe.
        return false
    }

    /** What a single escape sequence means for the purposes above. */
    private enum class Kind { HOME, ERASE_DISPLAY_ALL, ERASE_DISPLAY_TO_END, ERASE_LINE_ALL, NEUTRAL }

    /**
     * Find the index just past the escape sequence starting at [start].
     *
     * @return the exclusive end index, or null if the sequence is truncated by [len].
     */
    private fun readEscape(buf: ByteArray, start: Int, len: Int): Int? {
        if (start + 1 >= len) return null
        return when (buf[start + 1].toInt() and 0xff) {
            LBRACKET -> {
                // CSI: parameter bytes 0x30–0x3f, intermediates 0x20–0x2f, final 0x40–0x7e.
                var i = start + 2
                while (i < len && (buf[i].toInt() and 0xff) in 0x30..0x3f) i++
                while (i < len && (buf[i].toInt() and 0xff) in 0x20..0x2f) i++
                if (i < len && (buf[i].toInt() and 0xff) in 0x40..0x7e) i + 1 else null
            }
            RBRACKET -> {
                // OSC: runs to BEL or ST (ESC \).
                var i = start + 2
                while (i < len) {
                    val c = buf[i].toInt() and 0xff
                    if (c == BEL) return i + 1
                    if (c == ESC && i + 1 < len && (buf[i + 1].toInt() and 0xff) == BACKSLASH) return i + 2
                    i++
                }
                null
            }
            // Two-byte escapes (ESC =, ESC >, ESC 7, …) and charset selectors (ESC ( B).
            in 0x20..0x2f -> if (start + 2 < len) start + 3 else null
            else -> start + 2
        }
    }

    /**
     * Interpret the CSI sequence spanning `[start, end)`; anything else is [Kind.NEUTRAL].
     */
    private fun classify(buf: ByteArray, start: Int, end: Int): Kind {
        if ((buf[start + 1].toInt() and 0xff) != LBRACKET) return Kind.NEUTRAL
        val final = buf[end - 1].toInt() and 0xff
        // Private sequences (ESC[?…) are mode sets, never erases.
        val paramsStart = start + 2
        if (paramsStart < end - 1 && (buf[paramsStart].toInt() and 0xff) == QUESTION) return Kind.NEUTRAL
        val params = String(buf, paramsStart, (end - 1) - paramsStart, Charsets.US_ASCII)
        return when (final.toChar()) {
            'H', 'f' -> if (isOrigin(params)) Kind.HOME else Kind.NEUTRAL
            'J' -> when (params) {
                "", "0" -> Kind.ERASE_DISPLAY_TO_END
                "2" -> Kind.ERASE_DISPLAY_ALL
                else -> Kind.NEUTRAL // 3 = erase scrollback, a different intent entirely.
            }
            'K' -> if (params == "2") Kind.ERASE_LINE_ALL else Kind.NEUTRAL
            else -> Kind.NEUTRAL
        }
    }

    /** True for the cursor-address forms that mean row 1, column 1. */
    private fun isOrigin(params: String): Boolean = when (params) {
        "", "1", "1;1", ";", "0", "0;0", "1;", ";1" -> true
        else -> false
    }

    private const val ESC = 0x1b
    private const val BEL = 0x07
    private const val CR = 0x0d
    private const val LBRACKET = '['.code
    private const val RBRACKET = ']'.code
    private const val BACKSLASH = '\\'.code
    private const val QUESTION = '?'.code
}
