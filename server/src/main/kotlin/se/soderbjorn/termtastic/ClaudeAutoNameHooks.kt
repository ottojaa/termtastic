/**
 * Per-session registration of Termtastic's Claude Code auto-name hooks.
 *
 * This file contains [ClaudeAutoNameHooks], which makes a Claude Code session
 * launched inside a Termtastic terminal report its prompt-submit and
 * session-reset events back to the server — without permanently editing the
 * user's `~/.claude/settings.json`.
 *
 * ## Mechanism
 *  - A `claude` **shell function** (installed once by [ShellInitFiles] into the
 *    zsh bootstrap, *after* the user's rc) runs the real `claude` with
 *    `--settings <our hooks file>` prepended **when** the env var
 *    `TERMTASTIC_AUTONAME_SETTINGS` is set. `--settings` *merges* (hook arrays
 *    union across scopes), so the user's own settings and hooks are untouched.
 *  - A shell *function* wins over `PATH` unconditionally, so this is immune to
 *    dotfiles that re-prepend `~/.local/bin` (where `claude` usually lives) —
 *    the failure mode of the earlier PATH-shim approach.
 *  - [augmentEnv] sets `TERMTASTIC_AUTONAME_SETTINGS` (+ session / port / token)
 *    in the PTY env **only** for terminals Termtastic spawns while auto-naming
 *    is enabled. When it isn't set the function is a transparent passthrough,
 *    so nothing changes for other terminals or other users.
 *  - The registered hooks are `command` hooks that `curl -k` their JSON payload
 *    to the loopback [agentEventRoutes] endpoint (an HTTP `type` hook can't be
 *    used: the server is HTTPS with a self-signed cert). The command hook
 *    inherits the PTY env, so it forwards `TERMTASTIC_SESSION` for exact pane
 *    correlation and `TERMTASTIC_TOKEN` for spoof resistance.
 *
 * Wired from [main]: [port], [token] and [enabled] are set once at startup;
 * [augmentEnv] is called from [TerminalSession.create] per PTY spawn.
 *
 * POSIX-only, and the wrapper function is currently zsh-only (see
 * [ShellInitFiles]); bash/fish are a follow-up. A no-op on Windows.
 *
 * @see ShellInitFiles for where the `claude` wrapper function is defined.
 * @see agentEventRoutes
 * @see AgentEvent
 */
package se.soderbjorn.termtastic

import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.File

/**
 * Installs and injects the Claude Code auto-name hooks for Termtastic-spawned
 * terminals. Stateless beyond the startup-configured [port] / [token] /
 * [enabled]; the on-disk settings + forwarder are (re)written idempotently.
 */
object ClaudeAutoNameHooks {

    private val log = LoggerFactory.getLogger(ClaudeAutoNameHooks::class.java)

    /**
     * Env var naming the `--settings` file. When set in a shell, the `claude`
     * wrapper function (see [ShellInitFiles]) adds `--settings <this>`; when
     * unset the wrapper is a passthrough. Set by [augmentEnv] only for armed
     * terminals.
     */
    const val SETTINGS_ENV = "TERMTASTIC_AUTONAME_SETTINGS"

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
    private val settingsFile: File by lazy { File(dir, "settings.json") }
    private val hookScript: File by lazy { File(dir, "hook.sh") }

    /**
     * If auto-naming is enabled and `claude` is installed, ensure the hook
     * files exist and set the env so a `claude` launched in this PTY (via the
     * wrapper function) registers our hooks and can call back with [sessionId].
     *
     * A no-op when disabled, on Windows, or when `claude` can't be located.
     * Never throws — a failure here must not block spawning a terminal.
     *
     * @param sessionId the Termtastic PTY session id (`sN`) for correlation.
     * @param env the mutable PTY environment to augment in place.
     */
    fun augmentEnv(sessionId: String, env: MutableMap<String, String>) {
        if (isWindows) return
        if (!enabled()) {
            log.info("ClaudeAutoNameHooks: not arming {} — feature off at spawn", sessionId)
            return
        }
        try {
            if (ClaudeCli.locate() == null) {
                log.info("ClaudeAutoNameHooks: not arming {} — claude not found on PATH", sessionId)
                return
            }
            ensureFiles()
            env[SETTINGS_ENV] = settingsFile.absolutePath
            env["TERMTASTIC_SESSION"] = sessionId
            env["TERMTASTIC_PORT"] = port.toString()
            env["TERMTASTIC_TOKEN"] = token
            log.info("ClaudeAutoNameHooks: armed session {} (settings {}, port {})", sessionId, settingsFile.absolutePath, port)
        } catch (t: Throwable) {
            log.info("ClaudeAutoNameHooks: could not install hooks for {}; terminal will not auto-name", sessionId, t)
        }
    }

    /**
     * Write (idempotently) the hooks settings file and forwarder script,
     * marking the forwarder executable.
     */
    private fun ensureFiles() {
        dir.mkdirs()
        settingsFile.writeText(settingsJson())
        hookScript.writeText(hookScriptContents())
        hookScript.setExecutable(true, false)
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
