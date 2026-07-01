/**
 * Contextual terminal-name inference from AI-agent activity.
 *
 * This file defines the [NameInferrer] strategy interface plus:
 *  - [ClaudeCliNameInferrer] — asks the user's local `claude` CLI (headless
 *    `claude -p`, no API key) to summarize the on-screen agent transcript,
 *    returning a [ClaudeOutcome] (named / declined / unavailable).
 *  - [HeuristicNameInferrer] — an offline fallback that scrapes the most recent
 *    user prompt out of the transcript and cleans it into a name.
 *  - [DefaultNameInferrer] — the production composite: uses claude, respects a
 *    decline (leaves the terminal unnamed), and only falls back to the
 *    heuristic when claude is genuinely unavailable.
 *
 * [defaultNameInferrer] wires these together for [main].
 *
 * Called by the server-side `AutoNamer` when a terminal session transitions
 * idle → working; the resulting name is applied via
 * [WindowState.applyInferredName].
 *
 * @see AutoNamer
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
 * Strategy that turns a terminal transcript into a short contextual name.
 *
 * Implemented by [ClaudeCliNameInferrer] and [HeuristicNameInferrer]; composed
 * by [defaultNameInferrer]. Called by the `AutoNamer`.
 */
interface NameInferrer {
    /**
     * Summarize [transcript] (the visible screen text of a terminal running an
     * AI agent) into a short contextual title.
     *
     * @param transcript ANSI-free rendered screen text (may be noisy TUI output).
     * @return a short 2–4 word title, or `null` when no meaningful name could
     *         be produced (caller then leaves the pane's cwd-based title).
     */
    suspend fun infer(transcript: String): String?
}

/**
 * Build the default inferrer: ask the `claude` CLI first; use the offline
 * heuristic only when `claude` is genuinely unavailable (not installed / failed
 * / timed out). When `claude` runs but *declines* (no clear task), the terminal
 * is left unnamed rather than echoing the prompt via the heuristic.
 *
 * Called once from [main] and handed to the `AutoNamer`.
 *
 * @return a composed [NameInferrer].
 */
fun defaultNameInferrer(): NameInferrer =
    DefaultNameInferrer(ClaudeCliNameInferrer(), HeuristicNameInferrer())

/**
 * The outcome of asking the `claude` CLI to name a terminal. Distinguishes
 * "claude declined" (it ran and judged there's no clear task) from "claude
 * unavailable" (couldn't run at all) so the caller can respect a decline
 * instead of falling back to the echo-heuristic.
 */
sealed interface ClaudeOutcome {
    /** claude produced a usable title. */
    data class Named(val name: String) : ClaudeOutcome
    /**
     * claude ran but produced nothing usable (empty reply). Note: the prompt
     * intentionally offers no "reply none" escape (the fast model over-used it
     * and declined real tasks), so in practice claude labels almost any input —
     * a genuine non-task (e.g. a greeting) just gets a benign label rather than
     * this outcome. This mainly guards the empty-reply case.
     */
    data object Declined : ClaudeOutcome
    /** claude could not be run (not installed / spawn error / timeout / non-zero). */
    data object Unavailable : ClaudeOutcome
}

/**
 * The production [NameInferrer]: asks [claude] and maps its [ClaudeOutcome] —
 *  - [ClaudeOutcome.Named] → use the title;
 *  - [ClaudeOutcome.Declined] → return `null` (empty reply; leave on cwd title).
 *    In practice claude labels almost any input, so this is rare — see
 *    [ClaudeOutcome.Declined];
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

    override suspend fun infer(transcript: String): String? =
        when (val outcome = claude.tryInfer(transcript)) {
            is ClaudeOutcome.Named -> outcome.name
            ClaudeOutcome.Declined -> {
                log.info("DefaultNameInferrer: claude found no clear task; leaving terminal unnamed")
                null
            }
            ClaudeOutcome.Unavailable -> heuristic.infer(transcript)
        }
}

/**
 * Infers a name by piping the transcript to a headless `claude -p` invocation
 * and using its reply. Uses the user's existing Claude login (no API key), the
 * same way [ClaudeUsageMonitor] does.
 *
 * Returns a [ClaudeOutcome] so the caller can tell a *decline* (claude ran and
 * replied `none`) apart from *unavailability* (couldn't run at all), and only
 * fall back to the heuristic in the latter case.
 *
 * `claude` is used purely as a local text-summarizer tool here; it is
 * independent of which agent (Claude / Codex / Gemini) actually runs in the
 * pane being named.
 */
class ClaudeCliNameInferrer {

    private val log = LoggerFactory.getLogger(ClaudeCliNameInferrer::class.java)

    /**
     * Ask `claude` to name the terminal described by [transcript].
     *
     * Only ever sends the user's most recent prompt line (extracted via
     * [extractLatestPrompt]) to `claude` — never the whole screen. This keeps
     * the label on-topic (no ambient leftover output / banner / cwd leaking in)
     * and, importantly, minimizes what leaves the machine: a bounded, deliberate
     * prompt line rather than up to a full screen of source/output/secrets. If
     * no prompt line can be found, we don't name (returning
     * [ClaudeOutcome.Unavailable]) rather than exfiltrating the screen.
     *
     * Note: this forwards the prompt to Anthropic's Claude for summarization
     * regardless of which agent runs in the pane — surfaced in the setting copy.
     *
     * @param transcript the visible screen text.
     * @return [ClaudeOutcome.Named] with a title, [ClaudeOutcome.Declined] if
     *   claude produced nothing usable, or [ClaudeOutcome.Unavailable] on any
     *   failure or when no prompt could be extracted.
     */
    suspend fun tryInfer(transcript: String): ClaudeOutcome {
        val claudePath = ClaudeCli.locate()
        if (claudePath == null) {
            log.info("ClaudeCliNameInferrer: 'claude' not on PATH; using heuristic fallback")
            return ClaudeOutcome.Unavailable
        }

        val prompt = extractLatestPrompt(transcript)?.take(MAX_PROMPT_CHARS)
        if (prompt == null) {
            log.info("ClaudeCliNameInferrer: no prompt line found; not naming (won't send screen)")
            return ClaudeOutcome.Unavailable
        }

        val raw = withContext(Dispatchers.IO) {
            runProcess(claudePath, "$PROMPT_INSTRUCTION\n\nRequest: $prompt")
        } ?: return ClaudeOutcome.Unavailable // runProcess already logged why

        val name = sanitizeInferredName(raw)
        return if (name == null) {
            log.info("ClaudeCliNameInferrer: claude reply not usable (\"{}\")", raw.take(80))
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
     * escaping is needed) rather than piped on stdin — this avoids depending on
     * version-specific stdin-append behavior. stdin is closed immediately so
     * `claude` can never block waiting on it.
     *
     * @param claudePath absolute path to the `claude` binary.
     * @param claudeInput the full instruction+content to pass to `claude -p`.
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
            //  --allowedTools ""      — deny all tools; the input is untrusted
            //                           screen text, so guarantee text-only output.
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
        /** Cap on the extracted prompt chars sent to the CLI (bounds what leaves the machine). */
        private const val MAX_PROMPT_CHARS = 500

        /** Hard cap on how long we wait for `claude -p` before giving up. */
        private const val TIMEOUT_MS = 25_000L

        /** Model alias for the summarizer — a 2-4 word label needs only the cheapest/fastest. */
        private const val SUMMARIZER_MODEL = "haiku"

        /**
         * Preferred instruction: label the user's isolated request. Passed as
         * `"$PROMPT_INSTRUCTION\n\nRequest: <prompt>"`. Because the input is
         * just the request, there's no ambient screen text to leak into the
         * label.
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
 * Offline fallback: scrape the most recent user prompt out of the transcript
 * and clean it into a name. Used when the `claude` CLI isn't available. Returns
 * `null` (leaving the cwd-based title) rather than emitting a garbage name when
 * no plausible prompt can be found.
 */
class HeuristicNameInferrer : NameInferrer {
    private val log = LoggerFactory.getLogger(HeuristicNameInferrer::class.java)

    override suspend fun infer(transcript: String): String? {
        val prompt = extractLatestPrompt(transcript) ?: return null
        val name = sanitizeInferredName(prompt)
        if (name != null) log.info("HeuristicNameInferrer: inferred \"{}\" (LLM path unavailable)", name)
        return name
    }
}

/**
 * Extract the user's most recent prompt from a terminal [transcript].
 *
 * Agent TUIs echo the submitted user prompt on a line led by a prompt glyph
 * (`> `, `❯ `, `⏵ `), sometimes inside a box so a leading `│` and spaces
 * precede it. Returns the last such line that has real content — the user's
 * latest request. Used by both [ClaudeCliNameInferrer] (to summarize just the
 * request, avoiding ambient screen noise) and [HeuristicNameInferrer].
 *
 * @param transcript the visible screen text.
 * @return the latest prompt text, or `null` when none is found.
 */
internal fun extractLatestPrompt(transcript: String): String? =
    transcript.lineSequence()
        .mapNotNull { line -> PROMPT_LINE.find(line)?.groupValues?.get(1)?.trim() }
        .lastOrNull { it.length >= MIN_PROMPT_CHARS }

private const val MIN_PROMPT_CHARS = 3
private val PROMPT_LINE = Regex("""^[\s│]*[>❯⏵]\s+(.+)$""")

/**
 * Normalize a raw inferred title (CLI reply or scraped prompt) into a clean,
 * bounded terminal name.
 *
 * The input is untrusted (it derives from on-screen text run through an LLM),
 * and the result is written to [LeafNode.title], persisted, and broadcast to
 * every connected client. So besides trimming box-drawing/control chars and
 * surrounding quotes, this strips HTML-significant characters (`< > & " '` and
 * backtick) as a defense-in-depth against a crafted on-screen payload becoming
 * a stored/propagated XSS if a render path ever used innerHTML — a tab label
 * never legitimately needs them. Then collapses whitespace, drops trailing
 * punctuation, and caps to [MAX_WORDS] words / [MAX_CHARS] chars.
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
private val HTML_UNSAFE = Regex("""[<>&"'`\\]""")
