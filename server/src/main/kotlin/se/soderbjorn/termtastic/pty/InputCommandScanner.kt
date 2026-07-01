/**
 * User-input command scanner.
 *
 * This file contains [InputCommandScanner], a streaming parser fed with the
 * bytes the user types into a terminal (the PTY *input* stream, i.e. what
 * [se.soderbjorn.termtastic.TerminalSession.write] sends). It reconstructs each
 * submitted line and invokes a callback when the line is a "clear context"
 * command — Claude Code's `/clear` (or a bare shell `clear`).
 *
 * The server-side `AutoNamer` uses this to re-enable auto-naming after a
 * `/clear`: unlike a new agent process (detectable by pid) or a hard screen
 * erase, `/clear` runs in the same process and emits no distinctive output
 * escape — but the user's keystrokes are unambiguous, so watching the input is
 * the most direct and version-independent signal.
 *
 * The parser tracks a minimal line editor (printable chars, backspace, line
 * kill) and skips terminal input escape sequences (arrow keys, etc.) so their
 * bytes don't corrupt the reconstructed line.
 *
 * @see se.soderbjorn.termtastic.TerminalSession.write
 * @see se.soderbjorn.termtastic.AutoNamer
 */
package se.soderbjorn.termtastic.pty

/**
 * Reconstructs submitted input lines and fires [onClearCommand] when the user
 * submits a clear-context command (`/clear`, a prefix like `/cl`, or a bare
 * `clear`).
 *
 * Fed in arbitrary chunks from the PTY input path; state persists across calls.
 *
 * @property onClearCommand invoked once per submitted clear command.
 */
internal class InputCommandScanner(private val onClearCommand: () -> Unit) {

    private enum class State { NORMAL, ESC, CSI }

    private var state = State.NORMAL
    private val line = StringBuilder()

    /**
     * Feed [len] bytes of user input from [chunk], invoking [onClearCommand]
     * for each submitted clear command.
     *
     * Synchronized: the PTY write path is shared — multiple clients attached to
     * one session can call [se.soderbjorn.termtastic.TerminalSession.write]
     * concurrently — so guard the mutable line/state to avoid a corrupted
     * reconstruction (a missed or spurious `/clear`).
     *
     * @param chunk raw input bytes written toward the PTY.
     * @param len number of valid bytes (defaults to `chunk.size`).
     */
    @Synchronized
    fun feed(chunk: ByteArray, len: Int = chunk.size) {
        var i = 0
        while (i < len) {
            val b = chunk[i].toInt() and 0xFF
            when (state) {
                State.NORMAL -> when {
                    b == 0x1B -> state = State.ESC               // start of an escape seq
                    b == 0x0D || b == 0x0A -> {                  // Enter — submit the line
                        evaluate()
                        line.setLength(0)
                    }
                    b == 0x7F || b == 0x08 -> {                  // backspace
                        if (line.isNotEmpty()) line.deleteCharAt(line.length - 1)
                    }
                    b == 0x03 || b == 0x15 -> line.setLength(0)  // Ctrl-C / Ctrl-U kill the line
                    b in 0x20..0x7E -> { if (line.length < 64) line.append(b.toChar()) }
                    else -> Unit                                 // other control bytes: ignore
                }
                // Skip input escape sequences (e.g. arrow keys ESC[C) so their
                // printable tail bytes ('[', 'C') don't land in the line.
                State.ESC -> state = if (b == 0x5B || b == 0x4F) State.CSI else State.NORMAL
                State.CSI -> if (b in 0x40..0x7E) state = State.NORMAL
            }
            i++
        }
    }

    /** Check the just-submitted [line] and fire the callback if it's a clear. */
    private fun evaluate() {
        val cmd = line.toString().trim()
        // `/clear` and any unambiguous prefix of it (autocomplete: user may type
        // just "/cl" then Enter), or a bare shell `clear`.
        if (cmd == "clear" || (cmd.length >= 3 && "/clear".startsWith(cmd))) {
            onClearCommand()
        }
    }
}
