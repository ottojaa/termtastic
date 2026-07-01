/**
 * Per-session registration of Termtastic's Claude Code auto-name hooks.
 *
 * This file contains [ClaudeAutoNameHooks], which makes a Claude Code session
 * launched inside a Termtastic terminal report its prompt-submit and
 * session-reset events back to the server — without permanently editing the
 * user's `~/.claude/settings.json`.
 *
 * ## Mechanism
 *  - A **PATH shim**: a tiny `claude` script (in Termtastic's data dir) that
 *    `exec`s the user's real `claude` with `--settings <our hooks file>`
 *    prepended. `--settings` *merges* (hook arrays union across scopes), so the
 *    user's own settings and hooks are untouched — we only add ours.
 *  - The shim's dir is prepended to the PTY's `PATH` **only** for terminals
 *    Termtastic spawns while auto-naming is enabled ([augmentEnv]). Nothing the
 *    user owns is modified; disabling the feature just stops future terminals
 *    from getting the shim.
 *  - The registered hooks are `command` hooks that `curl` their JSON payload to
 *    the loopback [agentEventRoutes] endpoint (HTTP `type` hooks can't be used:
 *    the server is HTTPS with a self-signed cert, so we `curl -k`). The command
 *    hook inherits the PTY env, so it forwards `TERMTASTIC_SESSION` for exact
 *    pane correlation and `TERMTASTIC_TOKEN` for spoof resistance.
 *
 * Wired from [main]: [port], [token] and [enabled] are set once at startup;
 * [augmentEnv] is called from [TerminalSession.create] per PTY spawn.
 *
 * POSIX-only (macOS/Linux `sh` + `curl`); a no-op on Windows for now.
 *
 * @see agentEventRoutes
 * @see AgentEvent
 * @see ClaudeCli
 */
package se.soderbjorn.termtastic

import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.File

/**
 * Installs and injects the Claude Code auto-name hooks for Termtastic-spawned
 * terminals. Stateless beyond the startup-configured [port] / [token] /
 * [enabled]; the on-disk shim + settings + forwarder are (re)written idempotently.
 */
object ClaudeAutoNameHooks {

    private val log = LoggerFactory.getLogger(ClaudeAutoNameHooks::class.java)

    /** Loopback port of the Termtastic HTTPS server; set once from [main]. */
    @Volatile
    var port: Int = 0

    /** Per-run shared secret required by [agentEventRoutes]; set from [main]. */
    @Volatile
    var token: String = ""

    /**
     * Live opt-in check (reads the `terminalAutoName` UI setting). Set from
     * [main]; defaults to "off" so nothing is injected before configuration.
     */
    @Volatile
    var enabled: () -> Boolean = { false }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val dir: File by lazy { File(AppPaths.dataDir(), "claude-autoname") }
    private val binDir: File by lazy { File(dir, "bin") }
    private val settingsFile: File by lazy { File(dir, "settings.json") }
    private val hookScript: File by lazy { File(dir, "hook.sh") }
    private val shim: File by lazy { File(binDir, "claude") }

    /**
     * If auto-naming is enabled and `claude` is installed, ensure the shim +
     * hook files exist and augment [env] so a `claude` launched in this PTY
     * routes through the shim and can call back with [sessionId].
     *
     * A no-op when disabled, on Windows, or when `claude` can't be located
     * (the terminal simply spawns as normal). Never throws — a failure here
     * must not block spawning a terminal.
     *
     * Caveat: the shim is found via `PATH`, which we prepend here. A login
     * shell whose startup *replaces* `PATH` wholesale (rather than the usual
     * prepend/append) would drop the shim and silently disable naming for that
     * terminal. Common configs (Homebrew `shellenv`, `path=(... $path)`)
     * preserve it; hardening via the shell-init bootstrap is a follow-up.
     *
     * @param sessionId the Termtastic PTY session id (`sN`) for correlation.
     * @param env the mutable PTY environment to augment in place.
     */
    fun augmentEnv(sessionId: String, env: MutableMap<String, String>) {
        if (isWindows || !enabled()) return
        try {
            val realClaude = ClaudeCli.locate() ?: return
            ensureFiles(realClaude)
            val existingPath = env["PATH"] ?: System.getenv("PATH") ?: "/usr/bin:/bin"
            env["PATH"] = "${binDir.absolutePath}:$existingPath"
            env["TERMTASTIC_SESSION"] = sessionId
            env["TERMTASTIC_PORT"] = port.toString()
            env["TERMTASTIC_TOKEN"] = token
        } catch (t: Throwable) {
            log.info("ClaudeAutoNameHooks: could not install hooks for {}; terminal will not auto-name", sessionId, t)
        }
    }

    /**
     * Write (idempotently) the shim, hooks settings file, and forwarder script,
     * marking the executables. Rewritten each call so the baked real-`claude`
     * path stays current across updates.
     *
     * @param realClaude absolute path to the user's real `claude` binary.
     */
    private fun ensureFiles(realClaude: String) {
        binDir.mkdirs()
        settingsFile.writeText(settingsJson())
        hookScript.writeText(hookScriptContents())
        hookScript.setExecutable(true, false)
        shim.writeText(shimContents(realClaude))
        shim.setExecutable(true, false)
    }

    /**
     * The `--settings` payload: two `command` hooks pointing at [hookScript].
     * The script path is single-quoted so a data dir containing spaces (macOS
     * `Application Support`) doesn't word-split the shell command string.
     *
     * @return the settings JSON text.
     */
    private fun settingsJson(): String {
        val h = hookScript.absolutePath.replace("\\", "\\\\")
        return """
            {
              "hooks": {
                "UserPromptSubmit": [
                  { "hooks": [ { "type": "command", "command": "'$h' prompt", "timeout": 10 } ] }
                ],
                "SessionStart": [
                  { "hooks": [ { "type": "command", "command": "'$h' session", "timeout": 10 } ] }
                ]
              }
            }
        """.trimIndent() + "\n"
    }

    /**
     * The `claude` shim: exec the real binary with our hooks merged in. Both
     * paths are double-quoted to survive spaces.
     *
     * @param realClaude absolute path to the real `claude`.
     * @return the shim script text.
     */
    private fun shimContents(realClaude: String): String = """
        #!/bin/sh
        # Termtastic shim: run the user's real `claude` with Termtastic's
        # auto-name hooks merged in via --settings (non-destructive — the
        # user's own settings and hooks still apply). On PATH only for
        # terminals Termtastic spawned while auto-naming was enabled.
        exec "$realClaude" --settings "${settingsFile.absolutePath}" "${'$'}@"
    """.trimIndent() + "\n"

    /**
     * The forwarder: read the hook JSON on stdin and POST it to the loopback
     * server in the background. Non-blocking and silent (always exits 0 with no
     * stdout) so it never delays or alters the Claude session.
     *
     * @return the forwarder script text.
     */
    private fun hookScriptContents(): String = """
        #!/bin/sh
        # Termtastic auto-name forwarder. ${'$'}1 = kind (prompt|session).
        # Reads the Claude Code hook JSON on stdin and forwards it to the local
        # Termtastic server, which names the terminal from the first prompt of a
        # run. Best-effort, non-blocking, no stdout.
        kind="${'$'}1"
        [ -n "${'$'}TERMTASTIC_SESSION" ] && [ -n "${'$'}TERMTASTIC_PORT" ] || exit 0
        payload=${'$'}(cat)
        (
          printf '%s' "${'$'}payload" | curl -ksS -m 5 -X POST \
            "https://127.0.0.1:${'$'}TERMTASTIC_PORT/internal/agent-event?session=${'$'}TERMTASTIC_SESSION&kind=${'$'}kind&token=${'$'}TERMTASTIC_TOKEN" \
            -H 'Content-Type: application/json' --data-binary @- >/dev/null 2>&1 &
        ) >/dev/null 2>&1
        exit 0
    """.trimIndent() + "\n"
}
