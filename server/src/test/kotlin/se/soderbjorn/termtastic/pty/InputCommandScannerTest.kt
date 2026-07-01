/**
 * Unit tests for [InputCommandScanner] — verifying it detects a submitted
 * `/clear` (and prefixes / shell `clear`) from the user's input byte stream,
 * ignores ordinary input, and survives line editing and input escape
 * sequences.
 *
 * Control bytes are built from their codes (Char(code)) to keep the test
 * strings free of backslash escapes.
 *
 * @see InputCommandScanner
 */
package se.soderbjorn.termtastic.pty

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for [InputCommandScanner.feed] clear-command detection. */
class InputCommandScannerTest {

    private val CR = Char(13).toString()
    private val LF = Char(10).toString()
    private val DEL = Char(0x7F).toString()
    private val ESC = Char(0x1B).toString()

    private fun countClears(vararg inputs: String): Int {
        var n = 0
        val scanner = InputCommandScanner { n++ }
        for (s in inputs) scanner.feed(s.toByteArray(Charsets.UTF_8))
        return n
    }

    @Test
    fun `detects full slash clear`() {
        assertEquals(1, countClears("/clear" + CR))
    }

    @Test
    fun `detects autocomplete prefix`() {
        assertEquals(1, countClears("/cl" + CR))
        assertEquals(1, countClears("/cle" + CR))
    }

    @Test
    fun `detects bare shell clear`() {
        assertEquals(1, countClears("clear" + LF))
    }

    @Test
    fun `ignores ordinary prompts`() {
        assertEquals(0, countClears("how many files are here?" + CR))
        assertEquals(0, countClears("clear the build cache" + CR))
        assertEquals(0, countClears("/clear the logs" + CR))
        assertEquals(0, countClears("/help" + CR))
    }

    @Test
    fun `handles backspace editing`() {
        // "/cx" then backspace then "lear" -> "/clear"
        assertEquals(1, countClears("/cx" + DEL + "lear" + CR))
    }

    @Test
    fun `skips arrow-key escape sequences`() {
        // "/cl" + right-arrow (ESC [ C) + "ear" -> "/clear"
        assertEquals(1, countClears("/cl" + ESC + "[Cear" + CR))
    }

    @Test
    fun `counts multiple submissions and resets between lines`() {
        assertEquals(2, countClears("/clear" + CR, "do a thing" + CR, "/clear" + CR))
    }

    @Test
    fun `chunked input across feeds still detects`() {
        assertEquals(1, countClears("/cl", "ear", CR))
    }
}
