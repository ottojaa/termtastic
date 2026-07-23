/**
 * Tests for [SessionGrid] — the canonical server-side screen wrapping the
 * vendored Termux emulator:
 *  - fed bytes are interpreted into a readable transcript;
 *  - a width change runs the emulator's reflow without losing content;
 *  - device queries in ingested bytes are answered into the discard sink
 *    (proving nothing can escape toward a PTY), not re-injected;
 *  - alternate-buffer state and the new serialization getters are readable;
 *  - a malformed sequence never throws out of [SessionGrid.feed].
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGridTest {

    private val esc = "\u001b"

    private fun SessionGrid.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    @Test
    fun `feed produces readable transcript`() {
        val grid = SessionGrid(80, 24)
        grid.feed("hello\r\nworld")
        val text = grid.transcriptText()
        assertTrue(text.contains("hello") && text.contains("world"), "transcript should contain fed text, was: $text")
    }

    @Test
    fun `resize reflows without losing content`() {
        val grid = SessionGrid(120, 24)
        // 80 'a's fit on one row at 120 cols (no pending wrap since 80 < 120).
        grid.feed("a".repeat(80))
        assertEquals(80, grid.transcriptText().count { it == 'a' })
        // Narrowing forces the emulator's wrap-flag-faithful reflow; content is preserved.
        grid.resize(40, 24)
        assertEquals(80, grid.transcriptText().count { it == 'a' }, "all 80 chars survive the reflow")
    }

    @Test
    fun `device queries are answered into the discard sink, never escape`() {
        val grid = SessionGrid(80, 24)
        // OSC 10 color query — the emulator replies (see OperatingSystemControlTest);
        // that reply lands in the discard sink and is counted, proving the grid
        // answers-and-drops rather than leaking to any PTY (it has no PTY handle).
        grid.feed("$esc]10;?")
        grid.feed("after")
        assertTrue(grid.discardedOutputBytes > 0, "emulator answered the query into the sink")
        assertTrue(grid.transcriptText().contains("after"), "text after a query still renders")
    }

    @Test
    fun `alternate buffer state is readable`() {
        val grid = SessionGrid(80, 24)
        assertFalse(grid.read { it.isAlternateBufferActive() })
        grid.feed("$esc[?1049h")
        assertTrue(grid.read { it.isAlternateBufferActive() })
        grid.feed("$esc[?1049l")
        assertFalse(grid.read { it.isAlternateBufferActive() })
    }

    @Test
    fun `serialization getters reflect fed state`() {
        val grid = SessionGrid(80, 24)
        assertTrue(grid.read { it.isAutoWrapEnabled() })
        grid.feed("$esc[?7l")
        assertFalse(grid.read { it.isAutoWrapEnabled() })
        grid.feed("$esc[?2004h")
        assertTrue(grid.read { it.isBracketedPasteMode() })
    }

    @Test
    fun `malformed sequence does not throw`() {
        val grid = SessionGrid(80, 24)
        // Truncated/garbage CSI followed by normal text must not propagate.
        grid.feed("$esc[999999999999999999;;;;x normal")
        assertTrue(grid.transcriptText().contains("normal"))
    }

    @Test
    fun `sub-minimum resize is ignored`() {
        val grid = SessionGrid(80, 24)
        grid.feed("keep")
        grid.resize(1, 1) // below the emulator's 2x2 floor — no-op, no throw
        assertTrue(grid.transcriptText().contains("keep"))
    }

    @Test
    fun `synthesizeRedraw reconstructs the grid into a fresh grid`() {
        val grid = SessionGrid(40, 8)
        grid.feed("hello${esc}[31m red${esc}[0m\r\nsecond")
        val redraw = grid.synthesizeRedraw()
        assertTrue(redraw.isNotEmpty())

        val fresh = SessionGrid(40, 8)
        fresh.feed(redraw, redraw.size)
        assertEquals(grid.transcriptText(), fresh.transcriptText())
        // The synthesized redraw is constructed paint — it must carry no device queries.
        assertEquals(0L, fresh.discardedOutputBytes)
    }

    @Test
    fun `synthesizeForPersist round-trips into a fresh grid`() {
        // This is the Phase 4 persistence contract: persistSnapshot() bytes fed into a
        // fresh grid on restart reconstruct the scrollback.
        val grid = SessionGrid(40, 8)
        grid.feed("line one\r\nline two\r\nprompt$ ")
        val blob = grid.synthesizeForPersist()

        val restored = SessionGrid(40, 8)
        restored.feed(blob, blob.size)
        assertEquals(grid.transcriptText(), restored.transcriptText())
    }

    @Test
    fun `persist form does not resurrect terminal modes`() {
        // A dead full-screen app must not re-enable sticky modes on restore (#91):
        // serializeForPersist carries no mode epilogue.
        val grid = SessionGrid(40, 8)
        grid.feed("$esc[?2004h$esc[?1000h$esc[?1006hcontent")   // bracketed paste + mouse on
        assertTrue(grid.read { it.isBracketedPasteMode })

        val blob = grid.synthesizeForPersist()
        val restored = SessionGrid(40, 8)
        restored.feed(blob, blob.size)
        assertFalse(restored.read { it.isBracketedPasteMode }, "bracketed paste must not resurrect")
        assertFalse(restored.read { it.isMouseTrackingPressRelease }, "mouse tracking must not resurrect")
        assertTrue(restored.transcriptText().contains("content"))
    }
}
