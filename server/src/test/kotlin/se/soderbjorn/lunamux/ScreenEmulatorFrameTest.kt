/**
 * Tests for [ScreenEmulator]'s alternate-screen freeze-frame — the rendering
 * the killed-server restore path persists in place of unrepaintable raw TUI
 * traffic:
 *  - the frame is only offered while a program owns the alternate buffer;
 *  - it reproduces the visible grid, with SGR styling, and round-trips
 *    through a fresh emulator (i.e. a client can render it);
 *  - it is inert: no buffer switch, so replaying it cannot swallow the
 *    scrollback it is appended to.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenEmulatorFrameTest {

    private val esc = "\u001b"
    private val enterAlt = "$esc[?1049h"
    private val exitAlt = "$esc[?1049l"

    private fun emulator(vararg feeds: String): ScreenEmulator {
        val e = ScreenEmulator(initialCols = 40, initialRows = 6)
        for (f in feeds) {
            val b = f.toByteArray(Charsets.UTF_8)
            e.feed(b, b.size)
        }
        return e
    }

    /** Visible rows with trailing blank padding removed, for readable asserts. */
    private fun visibleRows(e: ScreenEmulator): List<String> =
        e.snapshotVisibleText().lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }

    @Test
    fun offersNoFrameForOrdinaryShellOutput() {
        val e = emulator("hello\r\n")
        assertFalse(e.isAlternateScreenActive())
        assertNull(e.renderAlternateScreenFrame(), "normal-buffer history is not a frame")
    }

    @Test
    fun offersNoFrameOnceTheProgramExits() {
        // The frame is gone from a real terminal too — persisting it here is
        // what resurrected a dead TUI as phantom scrollback.
        val e = emulator("vim\r\n", "$enterAlt~PAINT~", exitAlt)
        assertFalse(e.isAlternateScreenActive())
        assertNull(e.renderAlternateScreenFrame())
    }

    @Test
    fun freezesTheVisibleAlternateGrid() {
        val e = emulator("$enterAlt", "TOP LINE\r\nsecond line")
        assertTrue(e.isAlternateScreenActive())
        val frame = assertNotNull(e.renderAlternateScreenFrame())
        val text = frame.toString(Charsets.UTF_8)
        assertTrue(text.contains("TOP LINE"), "frame should carry the grid: $text")
        assertTrue(text.contains("second line"), "frame should carry the grid: $text")
    }

    @Test
    fun frameIsInertAndCarriesNoBufferSwitch() {
        // Critical: the frame is pasted into the normal buffer under replayed
        // scrollback. A stray switch would hide it, and a stray enter would
        // strand the fresh shell in the alternate buffer.
        val e = emulator(enterAlt, "PAINT")
        val text = assertNotNull(e.renderAlternateScreenFrame()).toString(Charsets.UTF_8)
        for (seq in listOf("[?1049", "[?1047", "[?47")) {
            assertFalse(text.contains(seq), "frame must not contain $seq: $text")
        }
    }

    @Test
    fun framePreservesColor() {
        // Red foreground text — the point of rendering SGR rather than plain
        // text is that the restored frame still looks like the program did.
        val e = emulator(enterAlt, "$esc[31mRED$esc[0m")
        val text = assertNotNull(e.renderAlternateScreenFrame()).toString(Charsets.UTF_8)
        assertTrue(text.contains("RED"), text)
        // Indexed color 1 is red; kept indexed so the client's theme applies.
        assertTrue(text.contains("38;5;1"), "expected indexed red SGR in: $text")
    }

    @Test
    fun frameRoundTripsThroughAFreshEmulator() {
        // The end-to-end property: what a client renders from the persisted
        // frame must be what was on screen when the server died.
        val dying = emulator(enterAlt, "line one\r\nline two\r\nline three")
        val frame = assertNotNull(dying.renderAlternateScreenFrame())

        // A fresh client, replaying a restore: no alternate buffer involved.
        val restored = ScreenEmulator(initialCols = 40, initialRows = 6)
        restored.feed(frame, frame.size)

        assertFalse(restored.isAlternateScreenActive(), "replay must stay in the normal buffer")
        assertEquals(listOf("line one", "line two", "line three"), visibleRows(restored))
    }

    @Test
    fun frameLandsBelowReplayedScrollback() {
        // The restore shape: normal-buffer history, then the frozen frame.
        val restored = ScreenEmulator(initialCols = 40, initialRows = 6)
        val history = "shell history\r\n".toByteArray(Charsets.UTF_8)
        restored.feed(history, history.size)

        val dying = emulator(enterAlt, "DEAD TUI FRAME")
        val frame = assertNotNull(dying.renderAlternateScreenFrame())
        restored.feed(frame, frame.size)

        assertEquals(listOf("shell history", "DEAD TUI FRAME"), visibleRows(restored))
    }
}
