/**
 * Shared helpers for locating and launching the local `claude` CLI.
 *
 * Both [ClaudeUsageMonitor] (usage scraping) and [NameInferrer] (terminal-name
 * inference) shell out to `claude`; this object centralizes the binary
 * discovery and PATH-augmentation they both need so the two can't drift.
 *
 * @see ClaudeUsageMonitor
 * @see NameInferrer
 */
package se.soderbjorn.termtastic

import java.io.File

/** Discovery + environment helpers for spawning the local `claude` CLI. */
object ClaudeCli {

    /**
     * Locate the `claude` binary. Prefers standalone installs (which don't need
     * Node on the PATH — often absent when Electron is launched from
     * Finder/Dock), then common npm locations, then `which`.
     *
     * @return an executable path, or `null` when `claude` can't be found.
     */
    fun locate(): String? {
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
     * A copy of the current environment with `TERM` set and well-known Node/CLI
     * bin directories appended to `PATH`, so an npm-installed `claude`
     * (`#!/usr/bin/env node`) resolves even when Electron inherited a minimal
     * PATH from a GUI launch.
     *
     * @return the augmented environment map (mutable, as some spawn APIs require).
     */
    fun augmentedEnv(): MutableMap<String, String> {
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
