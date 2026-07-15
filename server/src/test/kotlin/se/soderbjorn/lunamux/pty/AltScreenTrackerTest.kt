/**
 * Tests for [AltScreenTracker] — the parser that partitions recorded PTY
 * output into normal-buffer and alternate-buffer spans so a dead TUI's redraw
 * traffic can never replay as scrollback:
 *  - buffer switches route the whole span (enter and exit sequences included)
 *    to the alternate side, leaving normal-buffer history untouched;
 *  - a closed span is reported so its bytes can be dropped;
 *  - a span with no enter (the ring evicted it) is reported as orphaned;
 *  - switches split across feeds still resolve as one unit;
 *  - lookalike sequences (1048, DECCKM, SGR, queries) are not switches.
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AltScreenTrackerTest {

    private val esc = "\u001b"
    private val enterAlt = "$esc[?1049h"
    private val exitAlt = "$esc[?1049l"

    /** Accumulates the partitioned stream so a test can assert on each side. */
    private class Recorder : AltScreenTracker.Sink {
        val main = StringBuilder()
        val alt = StringBuilder()
        var spansClosed = 0
        var orphanExits = 0

        override fun onSegment(chunk: ByteArray, from: Int, until: Int, alt: Boolean) {
            val text = String(chunk, from, until - from, Charsets.UTF_8)
            (if (alt) this.alt else main).append(text)
        }

        override fun onSpanClosed() {
            spansClosed++
        }

        override fun onOrphanExit() {
            orphanExits++
        }
    }

    /** Feed [chunks] through one tracker, each chunk as a separate call. */
    private fun run(vararg chunks: String): Pair<Recorder, AltScreenTracker> {
        val rec = Recorder()
        val tracker = AltScreenTracker()
        for (c in chunks) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            tracker.feed(bytes, bytes.size, rec)
        }
        return rec to tracker
    }

    // ------------------------------------------------------------ routing

    @Test
    fun routesNormalBufferOutputToMain() {
        val (rec, tracker) = run("ls\r\nfile.txt\r\n")
        assertEquals("ls\r\nfile.txt\r\n", rec.main.toString())
        assertEquals("", rec.alt.toString())
        assertFalse(tracker.inAltScreen)
    }

    @Test
    fun routesWholeAltSpanIncludingSwitchesToAlt() {
        // The switching sequences belong to the alternate span: keeping them
        // out of the normal stream is what stops a replay from running the
        // cursor save/restore they imply against unrelated scrollback.
        val (rec, _) = run("vim\r\n$enterAlt~PAINT~$exitAlt> ")
        assertEquals("vim\r\n> ", rec.main.toString())
        assertEquals("$enterAlt~PAINT~$exitAlt", rec.alt.toString())
    }

    @Test
    fun reportsSpanClosedSoAltBytesCanBeDropped() {
        val (rec, tracker) = run("$enterAlt~PAINT~$exitAlt")
        assertEquals(1, rec.spansClosed)
        assertEquals(0, rec.orphanExits)
        assertFalse(tracker.inAltScreen)
    }

    @Test
    fun tracksOpenSpanUntilItCloses() {
        val (_, tracker) = run("$enterAlt~PAINT~")
        assertTrue(tracker.inAltScreen, "span with no exit must stay open")
    }

    @Test
    fun scrollbackAroundAnAltSpanStaysContiguous() {
        // The regression: a TUI's frame must not end up between the shell's
        // lines, which is what raw replay of a truncated ring produced.
        val (rec, _) = run("before\r\n", enterAlt, "~PAINT~", exitAlt, "after\r\n")
        assertEquals("before\r\nafter\r\n", rec.main.toString())
    }

    // ------------------------------------------------------------ orphans

    @Test
    fun reportsExitWithNoEnterAsOrphan() {
        // What a pre-tracking blob looks like once the ring evicted the enter:
        // everything before the exit is TUI paint, not scrollback.
        val (rec, tracker) = run("~ORPHAN PAINT~$exitAlt> ")
        assertEquals(1, rec.orphanExits)
        assertEquals(0, rec.spansClosed)
        assertFalse(tracker.inAltScreen)
    }

    // -------------------------------------------------------- chunk splits

    @Test
    fun resolvesSwitchSplitAcrossFeeds() {
        // A PTY read boundary can fall anywhere; the switch must still be one
        // unit and must not leak half of itself into the normal stream.
        for (cut in 1 until enterAlt.length) {
            val (rec, tracker) = run(
                "before",
                enterAlt.substring(0, cut),
                enterAlt.substring(cut) + "~PAINT~",
            )
            assertTrue(tracker.inAltScreen, "cut at $cut should still enter alt")
            assertEquals("before", rec.main.toString(), "cut at $cut leaked into main")
            assertEquals("$enterAlt~PAINT~", rec.alt.toString(), "cut at $cut")
        }
    }

    @Test
    fun resolvesExitSplitAcrossFeeds() {
        for (cut in 1 until exitAlt.length) {
            val (rec, tracker) = run(
                enterAlt + "~PAINT~",
                exitAlt.substring(0, cut),
                exitAlt.substring(cut) + "after",
            )
            assertFalse(tracker.inAltScreen, "cut at $cut should have exited alt")
            assertEquals("after", rec.main.toString(), "cut at $cut")
            assertEquals(1, rec.spansClosed, "cut at $cut")
        }
    }

    @Test
    fun deferredBytesAreNotLost() {
        // Every byte fed must come out on one side or the other once the
        // sequence it belongs to resolves.
        val (rec, _) = run("a$esc", "[?1049h", "b")
        assertEquals("a", rec.main.toString())
        assertEquals("${enterAlt}b", rec.alt.toString())
    }

    @Test
    fun releasesAnEscapeThatNeverResolves() {
        // A lone ESC mid-stream must not swallow the rest of the output.
        val (rec, _) = run("a${esc}b")
        assertEquals("a${esc}b", rec.main.toString())
    }

    // ------------------------------------------------------- non-switches

    @Test
    fun ignoresLookalikeSequences() {
        val notSwitches = listOf(
            "$esc[?1048h",  // save cursor only — does not swap buffers
            "$esc[?1048l",
            "$esc[?1h",     // DECCKM application cursor keys
            "$esc[?2004h",  // bracketed paste
            "$esc[?1000h",  // mouse tracking
            "$esc[0m",      // SGR
            "$esc[?6n",     // DECXCPR query
            "$esc[1049h",   // no `?` prefix: an ANSI mode, not a private one
        )
        for (seq in notSwitches) {
            val (rec, tracker) = run("a${seq}b")
            assertFalse(tracker.inAltScreen, "$seq must not enter alt")
            assertEquals("a${seq}b", rec.main.toString(), "$seq must stay in main")
            assertEquals(0, rec.spansClosed + rec.orphanExits, "$seq must not signal")
        }
    }

    @Test
    fun recognizesLegacyAndMultiParamSwitches() {
        for (seq in listOf("$esc[?47h", "$esc[?1047h", "$esc[?1049;1h")) {
            val (_, tracker) = run(seq)
            assertTrue(tracker.inAltScreen, "$seq should enter alt")
        }
    }

    // ------------------------------------------------------------- reset

    @Test
    fun resetClosesAnOpenSpan() {
        // After a blob that ended mid-span, the live shell's first bytes must
        // not be attributed to a program that no longer exists.
        val rec = Recorder()
        val tracker = AltScreenTracker()
        val blob = "$enterAlt~PAINT~".toByteArray(Charsets.UTF_8)
        tracker.feed(blob, blob.size, rec)
        tracker.reset()
        assertFalse(tracker.inAltScreen)

        val live = "> ".toByteArray(Charsets.UTF_8)
        tracker.feed(live, live.size, rec)
        assertEquals("> ", rec.main.toString())
    }
}
