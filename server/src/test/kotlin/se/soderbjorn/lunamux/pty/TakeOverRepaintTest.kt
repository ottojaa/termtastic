/**
 * Pins the behaviour that decides acceptance criterion "no duplicated output when
 * switching devices": a full-screen TUI answering a SIGWINCH must not leave a second
 * copy of its frame in the canonical grid's scrollback.
 *
 * Why this happens at all. One PTY has one winsize, so a take-over resizes it, and a
 * resize is a SIGWINCH. A full-screen program answers a SIGWINCH by redrawing its
 * viewport: it homes, erases every visible row, and paints one screen's worth. But
 * narrowing first runs the emulator's reflow, which rewraps the *old* viewport into more
 * rows than the new screen holds and archives the overflow — the top of the old frame —
 * into scrollback, where a per-row erase cannot reach it. The program then repaints the
 * same content on-screen, so that stranded top is now shown twice. This is exactly the
 * "duplicated the Claude ASCII logo + my prompt lines" a user reported when taking a
 * session back and forth. The redraw itself is correctly sized to one screen (captured
 * live: it homes, erases `rows` lines, homes, and paints a viewport — it does not spill a
 * second screenful into history), so the *only* surplus copy is the reflow's archival.
 *
 * The repaint is self-declaring, which is what makes this fixable without guesswork:
 * captured from a live Claude Code session, every post-SIGWINCH chunk opened with
 * `ESC[?25l` … `ESC[H` followed by exactly `rows` × (`ESC[2K` `ESC[1B`) and a second
 * `ESC[H` — the erase count equal to the new screen height in all 22 observed resizes,
 * narrowing and widening alike. A shell at a prompt emits no such sequence.
 *
 * @see RepaintDeclaration the classifier for that sequence
 * @see SessionGrid.feed where a declared repaint withdraws the reflow's archival
 * @see ReflowReversibilityTest the companion property: reflow itself is lossless
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeOverRepaintTest {

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /**
     * One screen's worth of a full-screen app's viewport: `rows` short lines (no wrap at
     * either test width), the first tagged [MARKER_TOP] and the last [MARKER_BOTTOM]. The
     * body between them is width-independent text a real TUI would recompute per size.
     */
    private fun viewport(rows: Int): List<String> = buildList {
        add(MARKER_TOP)
        repeat(rows - 2) { i -> add("viewport row %02d".format(i)) }
        add(MARKER_BOTTOM)
    }

    /**
     * How a full-screen TUI redraws itself after a SIGWINCH: hide cursor, home, erase every
     * visible row, home, then paint exactly one screen. Painting `rows` lines with a
     * trailing newline on each fills the screen and scrolls the top row into history by
     * exactly one — matching a real redraw, which commits one line of history as it wraps
     * onto the last row. The point of the test is that this leaves ONE copy, not that it
     * leaves zero.
     */
    private fun repaint(rows: Int): String = buildString {
        append("\u001b[?25l")
        append("\u001b[H")
        repeat(rows) { append("\u001b[2K\u001b[1B") }
        append("\u001b[H")
        append(viewport(rows).joinToString("\r\n"))
        append("[?25h")
    }

    private fun String.countOf(needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }

    @Test
    fun `a declared repaint after take-over leaves exactly one copy of the frame`() {
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // The program paints its viewport at the laptop's native size.
        grid.feedText(repaint(WIDE_ROWS))
        assertEquals(1, grid.transcriptText().countOf(MARKER_BOTTOM), "precondition: one copy")

        // Phone takes over: the PTY narrows, the grid reflows, and the program repaints.
        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "the frame's top must not be duplicated")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the frame's tail must not be duplicated")
    }

    @Test
    fun `repeated take-over does not accumulate copies`() {
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        repeat(8) {
            grid.resize(NARROW_COLS, NARROW_ROWS)
            grid.feedText(repaint(NARROW_ROWS))
            grid.resize(WIDE_COLS, WIDE_ROWS)
            grid.feedText(repaint(WIDE_ROWS))
        }

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "top must not scale with the number of switches")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "tail must not scale with the number of switches")
    }

    @Test
    fun `frozen scrollback above the viewport is preserved, not eaten`() {
        // A real session has committed history above the live viewport. The withdrawal must
        // remove only what the reflow archived from the viewport, never the genuine history
        // that was already in scrollback — that is the whole safety of anchoring on the
        // pre-resize completed-line count rather than clearing the transcript.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // Enough committed lines that many scroll off the top into the transcript before
        // the full-screen app draws its first frame — otherwise the repaint's erase simply
        // wipes them off the screen and there is nothing in scrollback to protect.
        val history = (0 until 90).joinToString("") { "history line $it\r\n" }
        grid.feedText(history)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        // Lines below the ~47 that were already in the transcript when the app started.
        for (line in listOf(0, 13, 27, 40)) {
            assertTrue(
                text.contains("history line $line\n"),
                "committed history must survive take-over (line $line)",
            )
        }
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the viewport is still not duplicated")
    }

    @Test
    fun `output that does not declare a repaint is never withdrawn`() {
        // The shell case: real committed output, a resize, and no full-screen redraw.
        // Nothing may be dropped — this is the safety direction, and the reason the
        // withdrawal waits for the program to declare itself.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        val committed = (0 until 60).map { "committed line $it " + "x".repeat(120) }
        grid.feedText(committed.joinToString("\r\n") + "\r\n$ ")

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText("ls\r\n")

        val text = grid.transcriptText()
        for (line in listOf(0, 17, 42, 59)) {
            assertTrue(
                text.contains("committed line $line "),
                "committed output must survive a resize with no repaint (line $line)",
            )
        }
    }

    @Test
    fun `widening then repainting keeps one copy`() {
        // Widening un-wraps, so the reflow archives nothing; the withdrawal must be a
        // no-op rather than eating history it did not create.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        assertEquals(1, grid.transcriptText().countOf(MARKER_BOTTOM), "one copy after widening")
    }

    private companion object {
        const val MARKER_TOP = "MARKER-TOP-OF-FRAME"
        const val MARKER_BOTTOM = "MARKER-BOTTOM-OF-FRAME"
        const val WIDE_COLS = 143
        const val WIDE_ROWS = 43
        const val NARROW_COLS = 67
        const val NARROW_ROWS = 24
    }
}
