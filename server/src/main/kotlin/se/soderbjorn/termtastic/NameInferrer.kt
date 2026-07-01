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
import java.io.File

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
    /** claude ran but judged there is no clear task to name. */
    data object Declined : ClaudeOutcome
    /** claude could not be run (not installed / spawn error / timeout / non-zero). */
    data object Unavailable : ClaudeOutcome
}

/**
 * The production [NameInferrer]: asks [claude] and maps its [ClaudeOutcome] —
 *  - [ClaudeOutcome.Named] → use the title;
 *  - [ClaudeOutcome.Declined] → return `null` (leave the terminal on its cwd
 *    title — a greeting or non-task shouldn't get a junk name);
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
     * Prefers to summarize just the user's most recent prompt (extracted via
     * [extractLatestPrompt]) rather than the whole screen: the full screen
     * carries ambient noise (leftover output from a previous session, the agent
     * banner, the cwd/project name) that can leak into the label. Summarizing
     * the isolated request yields a clean, on-topic title. Falls back to the
     * whole screen only when no prompt line can be found.
     *
     * @param transcript the visible screen text.
     * @return [ClaudeOutcome.Named] with a title, [ClaudeOutcome.Declined] if
     *   claude replied `none`, or [ClaudeOutcome.Unavailable] on any failure.
     */
    suspend fun tryInfer(transcript: String): ClaudeOutcome {
        val claudePath = findClaude()
        if (claudePath == null) {
            log.info("ClaudeCliNameInferrer: 'claude' not on PATH; using heuristic fallback")
            return ClaudeOutcome.Unavailable
        }

        val prompt = extractLatestPrompt(transcript)
        val claudeInput = if (prompt != null) {
            "$PROMPT_INSTRUCTION\n\nRequest: $prompt"
        } else {
            val screen = transcript.takeLast(MAX_TRANSCRIPT_CHARS)
            if (screen.isBlank()) return ClaudeOutcome.Unavailable
            "$SCREEN_INSTRUCTION\n\n--- terminal screen ---\n$screen"
        }

        val raw = withContext(Dispatchers.IO) {
            runProcess(claudePath, claudeInput)
        } ?: return ClaudeOutcome.Unavailable // runProcess already logged why

        val name = sanitizeInferredName(raw)
        return if (name == null) {
            log.info("ClaudeCliNameInferrer: claude replied \"{}\" (no clear task)", raw.take(80))
            ClaudeOutcome.Declined
        } else {
            log.info("ClaudeCliNameInferrer: inferred \"{}\" (from {})", name, if (prompt != null) "prompt" else "screen")
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
        // A dedicated, neutrally-named temp dir avoids Claude Code's "trust this
        // folder" prompt for sensitive dirs like $HOME, keeps any project
        // CLAUDE.md out of context, and — crucially — its basename must not
        // resemble a task (Claude Code uses the cwd basename as the project
        // name, which can otherwise leak into the label).
        val workDir = File(System.getProperty("java.io.tmpdir"), "tt-title-tmp").apply { mkdirs() }
        val prompt = claudeInput
        val proc = try {
            // --strict-mcp-config with no --mcp-config loads NO MCP servers, so
            // startup is fast and can't hang on a flaky server configured in the
            // user's global settings. This summarization uses no tools.
            ProcessBuilder(claudePath, "-p", prompt, "--output-format", "text", "--strict-mcp-config")
                .directory(workDir)
                .also { it.environment().putAll(augmentedEnv()) }
                .redirectErrorStream(false)
                .start()
        } catch (t: Throwable) {
            log.info("ClaudeCliNameInferrer: failed to spawn claude; using heuristic fallback", t)
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
        }
    }

    companion object {
        /** Max transcript chars sent to the CLI (a terminal screen is small). */
        private const val MAX_TRANSCRIPT_CHARS = 8_000

        /** Hard cap on how long we wait for `claude -p` before giving up. */
        private const val TIMEOUT_MS = 25_000L

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

        /**
         * Fallback instruction used only when no prompt line could be extracted
         * from the transcript; summarizes the whole screen. More prone to
         * picking up ambient content, hence the fallback status.
         */
        private val SCREEN_INSTRUCTION = """
            Summarize what the user is doing in this terminal as a concise
            2-4 word Title Case tab label (for example: Fix Login Bug,
            Readme Summary, Debug CI Failure). Reply with ONLY the label -
            no quotes, no punctuation, no explanation.
        """.trimIndent().replace('\n', ' ')

        /**
         * Locate the `claude` binary. Prefers standalone installs (which don't
         * need Node on the PATH, often absent in GUI-launched Electron), then
         * common npm locations, then `which`. Mirrors
         * [ClaudeUsageMonitor]'s private `findClaude`.
         *
         * @return an executable path, or `null` when not found.
         */
        private fun findClaude(): String? {
            val home = System.getProperty("user.home")
            val candidates = listOf(
                "$home/.local/bin/claude",
                "$home/.claude/local/claude",
                "$home/.claude/bin/claude",
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                "$home/.npm-global/bin/claude",
            )
            for (path in candidates) {
                if (File(path).canExecute()) return path
            }
            return try {
                val proc = ProcessBuilder("which", "claude").redirectErrorStream(true).start()
                val result = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                if (proc.exitValue() == 0 && result.isNotBlank()) result else null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * A copy of the current environment with well-known Node/CLI bin
         * directories appended to PATH, so an npm-installed `claude`
         * (`#!/usr/bin/env node`) resolves even when Electron was launched from
         * Finder/Dock with a minimal PATH. Mirrors [ClaudeUsageMonitor].
         *
         * @return the augmented environment map.
         */
        private fun augmentedEnv(): Map<String, String> {
            val home = System.getProperty("user.home")
            val extra = listOf(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "$home/.nvm/versions/node/default/bin",
                "$home/.local/bin",
            )
            return HashMap(System.getenv()).apply {
                put("TERM", "xterm-256color")
                val current = getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
                put("PATH", (extra + current.split(":")).distinct().joinToString(":"))
            }
        }
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
 * Strips box-drawing/control characters and surrounding quotes/backticks,
 * collapses whitespace, drops trailing punctuation, and caps the result to
 * [MAX_WORDS] words / [MAX_CHARS] characters.
 *
 * @param raw the untrusted candidate string.
 * @return a cleaned name, or `null` when empty or the sentinel "none".
 */
internal fun sanitizeInferredName(raw: String): String? {
    val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    var s = firstLine
        .replace(BOX_AND_CONTROL, " ")
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
