/**
 * OS-level child-process presence check.
 *
 * This file contains [ProcessTreeReader], which reports the direct child pid of
 * a shell. The server-side `AutoNamer` records the agent's pid when it names a
 * terminal, then checks whether that pid is still alive to tell an agent that
 * is still running (e.g. `claude` waiting between turns) apart from one that has
 * exited or been replaced. That distinction lets auto-naming fire once per
 * *agent session* rather than once per terminal lifetime: when the agent exits
 * and a new one is started in the same terminal, the terminal is named again.
 *
 * Implemented with `pgrep -P <pid>`, available on both macOS and Linux. A short
 * fork on the AutoNamer's ~3 s cadence, consistent with [ProcessCwdReader]'s
 * `lsof` usage.
 *
 * @see ProcessCwdReader
 * @see se.soderbjorn.termtastic.AutoNamer
 */
package se.soderbjorn.termtastic.pty

import java.util.concurrent.TimeUnit

/**
 * Reports the direct child process of a shell, used to identify the interactive
 * agent process so the `AutoNamer` can tell when a *new* agent session begins
 * in the same terminal (a different — or absent — child).
 */
internal object ProcessTreeReader {

    /**
     * The pid of the first direct child of process [pid], if any.
     *
     * Runs `pgrep -P <pid>` and returns the first pid it reports. This is a
     * *heuristic*: it assumes the interactive agent is the shell's one relevant
     * foreground child. If the shell also has a backgrounded job, or the agent
     * is wrapped by a launcher (`env`, a node shim, …), the first child may not
     * be the agent — in which case the `AutoNamer`'s exit detection can latch
     * onto the wrong pid (worst case: a name stays stale for that shell's life).
     * Good enough for the common `claude`/`codex`/`gemini` case.
     *
     * @param pid the parent process id (the terminal's shell).
     * @return the child pid, or `null` when there are no children or the check
     *         could not be performed.
     */
    fun firstChild(pid: Long): Long? = try {
        val proc = ProcessBuilder("pgrep", "-P", pid.toString())
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else {
            out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.toLongOrNull()
        }
    } catch (_: Throwable) {
        null
    }
}
