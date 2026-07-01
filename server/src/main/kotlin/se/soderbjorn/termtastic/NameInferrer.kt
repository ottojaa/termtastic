/**
 * Contextual terminal-name inference from an agent prompt.
 *
 * This file defines the [NameInferrer] strategy interface plus:
 *  - [ClaudeCliNameInferrer] — asks the user's local `claude` CLI (headless
 *    `claude -p`, no API key) to turn the prompt into a 2–4 word Title Case
 *    label, returning a [ClaudeOutcome] (named / declined / unavailable).
 *  - [HeuristicNameInferrer] — an offline fallback that cleans the prompt text
 *    into a name directly (used only when `claude` isn't installed).
 *  - [DefaultNameInferrer] — the production composite: uses claude, respects a
 *    decline (leaves the terminal unnamed), and only falls back to the
 *    heuristic when claude is genuinely unavailable.
 *
 * The input is now the **exact** user prompt (delivered by the agent's hook via
 * [AgentEvent.Prompt]), not scraped screen text — so there is no prompt-glyph
 * regex and no whole-screen fallback, and no ambient screen content leaks into
 * the summarizer.
 *
 * [defaultNameInferrer] wires these together for [main].
 *
 * @see AgentEvent
 * @see installAutoNamer
 * @see WindowState.applyInferredName
 * @see ClaudeUsageMonitor for the sibling "spawn the claude CLI" pattern.
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Strategy that turns an agent prompt into a short contextual name.
 *
 * Implemented by [ClaudeCliNameInferrer] and [HeuristicNameInferrer]; composed
 * by [defaultNameInferrer]. Called by the `AutoNamer`.
 */
interface NameInferrer {
    /**
     * Summarize [prompt] (the exact request the user gave the agent) into a
     * short contextual title.
     *
     * @param prompt the user's prompt text.
     * @return a short 2–4 word title, or `null` when no meaningful name could
     *         be produced (caller then leaves the pane's cwd-based title).
     */
    suspend fun infer(prompt: String): String?
}

/**
 * Build the default inferrer: ask the `claude` CLI first; use the offline
 * heuristic only when `claude` is genuinely unavailable (not installed / failed
 * / timed out). When `claude` runs but *declines* (empty reply), the terminal
 * is left unnamed.
 *
 * Called once from [main] and handed to the `AutoNamer`.
 *
 * @return a composed [NameInferrer].
 */
fun defaultNameInferrer(): NameInferrer =
    DefaultNameInferrer(ClaudeCliNameInferrer(), HeuristicNameInferrer())

/**
 * The outcome of asking the `claude` CLI to name a terminal. Distinguishes
 * "claude declined" (it ran and returned nothing usable) from "claude
 * unavailable" (couldn't run at all) so the caller can respect a decline
 * instead of falling back to the heuristic.
 */
sealed interface ClaudeOutcome {
    /** claude produced a usable title. */
    data class Named(val name: String) : ClaudeOutcome
    /**
     * claude ran but produced nothing usable (empty reply). The prompt
     * intentionally offers no "reply none" escape (the fast model over-used it
     * and declined real tasks), so in practice claude labels almost any prompt;
     * this mainly guards the empty-reply case.
     */
    data object Declined : ClaudeOutcome
    /** claude could not be run (not installed / spawn error / timeout / non-zero). */
    data object Unavailable : ClaudeOutcome
}

/**
 * The production [NameInferrer]: asks [claude] and maps its [ClaudeOutcome] —
 *  - [ClaudeOutcome.Named] → use the title;
 *  - [ClaudeOutcome.Declined] → return `null` (leave on cwd title);
 *  - [ClaudeOutcome.Unavailable] → fall back to the offline [heuristic].
 *
 * @property claude the CLI-backed inferrer.
 * @property heuristic the offline fallback used only when claude can't run.
 */
class DefaultNameInferrer(
    private val claude: ClaudeCliNameInferrer,
    private val heuristic: NameInferrer,
) : NameInferrer {
    private val log = LoggerFactory.getLogger(DefaultNameInferrer::class.java)

    override suspend fun infer(prompt: String): String? =
        when (val outcome = claude.tryInfer(prompt)) {
            is ClaudeOutcome.Named -> outcome.name
            ClaudeOutcome.Declined -> {
                log.info("DefaultNameInferrer: claude produced no usable label; leaving terminal unnamed")
                null
            }
            ClaudeOutcome.Unavailable -> heuristic.infer(prompt)
        }
}

/**
 * Infers a name by passing the prompt to a headless `claude -p` invocation and
 * using its reply. Uses the user's existing Claude login (no API key), the same
 * way [ClaudeUsageMonitor] does.
 *
 * `claude` is used purely as a local text-summarizer here; it is independent of
 * which agent (Claude / Codex / Gemini) actually ran the prompt in the pane.
 */
class ClaudeCliNameInferrer {

    private val log = LoggerFactory.getLogger(ClaudeCliNameInferrer::class.java)

    /**
     * Ask `claude` to turn [prompt] into a terminal name.
     *
     * @param prompt the user's exact prompt text.
     * @return [ClaudeOutcome.Named] with a title, [ClaudeOutcome.Declined] if
     *   claude replied nothing usable, or [ClaudeOutcome.Unavailable] on any
     *   failure.
     */
    suspend fun tryInfer(prompt: String): ClaudeOutcome {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return ClaudeOutcome.Declined

        val claudePath = ClaudeCli.locate()
        if (claudePath == null) {
            log.info("ClaudeCliNameInferrer: 'claude' not on PATH; using heuristic fallback")
            return ClaudeOutcome.Unavailable
        }

        val claudeInput = "$PROMPT_INSTRUCTION\n\nRequest: ${trimmed.take(MAX_PROMPT_CHARS)}"
        val raw = withContext(Dispatchers.IO) {
            runProcess(claudePath, claudeInput)
        } ?: return ClaudeOutcome.Unavailable // runProcess already logged why

        val name = sanitizeInferredName(raw)
        return if (name == null) {
            log.info("ClaudeCliNameInferrer: claude replied \"{}\" (no usable label)", raw.take(80))
            ClaudeOutcome.Declined
        } else {
            log.info("ClaudeCliNameInferrer: inferred \"{}\"", name)
            ClaudeOutcome.Named(name)
        }
    }

    /**
     * Spawn `claude -p "<claudeInput>"` in a throwaway temp dir and return its
     * stdout (or `null` on any failure / timeout). Blocking; call on
     * [Dispatchers.IO].
     *
     * The input is passed inside the `-p` argument (argv, not a shell, so no
     * escaping is needed). stdin is closed immediately so `claude` can never
     * block waiting on it.
     *
     * @param claudePath absolute path to the `claude` binary.
     * @param claudeInput the full instruction+prompt to pass to `claude -p`.
     * @return trimmed stdout, or `null`.
     */
    private suspend fun runProcess(claudePath: String, claudeInput: String): String? {
        // A fresh per-invocation temp dir: avoids Claude Code's "trust this
        // folder" prompt for sensitive dirs like $HOME, keeps any project
        // CLAUDE.md out of context, avoids a predictable shared path a local
        // user could pre-plant config in, and doesn't accumulate claude project
        // history. Its basename must not resemble a task (Claude Code uses the
        // cwd basename as the project name, which can otherwise leak).
        val workDir = try {
            java.nio.file.Files.createTempDirectory("tt-title").toFile()
        } catch (t: Throwable) {
            log.info("ClaudeCliNameInferrer: temp dir failed; using heuristic fallback", t)
            return null
        }
        val proc = try {
            // Flags:
            //  --model haiku          — cheap/fast; a 2-4 word label needs nothing bigger.
            //  --allowedTools ""      — deny all tools; guarantee text-only output.
            //  --strict-mcp-config    — load no MCP servers (fast startup, can't hang).
            ProcessBuilder(
                claudePath, "-p", claudeInput,
                "--model", SUMMARIZER_MODEL,
                "--allowedTools", "",
                "--strict-mcp-config",
                "--output-format", "text",
            )
                .directory(workDir)
                .also { it.environment().putAll(ClaudeCli.augmentedEnv()) }
                // Discard stderr so a chatty claude can't fill the pipe buffer
                // and deadlock us while we read stdout.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (t: Throwable) {
            log.info("ClaudeCliNameInferrer: failed to spawn claude; using heuristic fallback", t)
            workDir.deleteRecursively()
            return null
        }

        return try {
            // No stdin input; close it so claude never blocks reading a pipe.
            runCatching { proc.outputStream.close() }
            val out = withTimeoutOrNull(TIMEOUT_MS) {
                runInterruptible { proc.inputStream.bufferedReader().readText() }
            }
            if (out == null) {
                log.info("ClaudeCliNameInferrer: timed out after {} ms; using heuristic fallback", TIMEOUT_MS)
                return null
            }
            proc.waitFor()
            if (proc.exitValue() != 0) {
                log.info("ClaudeCliNameInferrer: claude exited {}; using heuristic fallback", proc.exitValue())
                null
            } else {
                out.trim().ifEmpty { null }
            }
        } catch (t: Throwable) {
            log.info("ClaudeCliNameInferrer: error running claude; using heuristic fallback", t)
            null
        } finally {
            runCatching { proc.destroyForcibly() }
            // deleteRecursively: claude may drop a .claude.json / log in its cwd,
            // and File.delete() only removes an empty dir (would leak otherwise).
            runCatching { workDir.deleteRecursively() }
        }
    }

    companion object {
        /** Max prompt chars sent to the CLI (labels need only the gist). */
        private const val MAX_PROMPT_CHARS = 2_000

        /** Hard cap on how long we wait for `claude -p` before giving up. */
        private const val TIMEOUT_MS = 25_000L

        /** Model alias for the summarizer — a 2-4 word label needs only the cheapest/fastest. */
        private const val SUMMARIZER_MODEL = "haiku"

        /**
         * Instruction prepended to the prompt. Passed as
         * `"$PROMPT_INSTRUCTION\n\nRequest: <prompt>"`.
         *
         * Note: deliberately does NOT offer a "reply none if unclear" escape —
         * testing showed the fast model over-uses such an escape and returns
         * "none" even for clear tasks.
         */
        private val PROMPT_INSTRUCTION = """
            Turn this user request to an AI coding agent into a concise 2-4 word
            Title Case tab label (for example: Fix Login Bug, Summarize Readme,
            Debug CI Failure). Reply with ONLY the label - no quotes, no
            punctuation, no explanation.
        """.trimIndent().replace('\n', ' ')
    }
}

/**
 * Offline fallback: clean the prompt text into a name directly. Used only when
 * the `claude` CLI isn't available. Returns `null` (leaving the cwd-based title)
 * rather than emitting garbage when the prompt yields nothing usable.
 */
class HeuristicNameInferrer : NameInferrer {
    private val log = LoggerFactory.getLogger(HeuristicNameInferrer::class.java)

    override suspend fun infer(prompt: String): String? {
        val name = sanitizeInferredName(prompt)
        if (name != null) log.info("HeuristicNameInferrer: inferred \"{}\" (LLM path unavailable)", name)
        return name
    }
}

/**
 * Normalize a raw inferred title (CLI reply or raw prompt) into a clean,
 * bounded terminal name.
 *
 * Strips box-drawing/control characters, HTML-significant characters (the
 * title is persisted + broadcast to every client and rendered by the toolkit,
 * and the input is untrusted), and surrounding quotes/backticks; collapses
 * whitespace, drops trailing punctuation, and caps the result to [MAX_WORDS]
 * words / [MAX_CHARS] characters.
 *
 * @param raw the untrusted candidate string.
 * @return a cleaned name, or `null` when empty or the sentinel "none".
 */
internal fun sanitizeInferredName(raw: String): String? {
    val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    var s = firstLine
        .replace(BOX_AND_CONTROL, " ")
        .replace(HTML_UNSAFE, "")
        .trim()
        .trim('"', '\'', '`', '“', '”', '‘', '’')
        .trim()
    // Collapse whitespace and cap word count.
    s = s.split(WHITESPACE).filter { it.isNotBlank() }.take(MAX_WORDS).joinToString(" ")
    // Drop trailing sentence punctuation left over from a prompt.
    s = s.trimEnd('.', ',', ';', ':', '!', '?').trim()
    if (s.length > MAX_CHARS) s = s.take(MAX_CHARS).trim()
    if (s.isEmpty() || s.equals("none", ignoreCase = true)) return null
    return s
}

private const val MAX_WORDS = 4
private const val MAX_CHARS = 36
private val WHITESPACE = Regex("""\s+""")
private val BOX_AND_CONTROL = Regex("""[\p{Cntrl}─-╿]+""")
/** HTML-significant chars stripped so an attacker-influenced label can't inject markup. */
private val HTML_UNSAFE = Regex("""[<>&"'`\\]""")
