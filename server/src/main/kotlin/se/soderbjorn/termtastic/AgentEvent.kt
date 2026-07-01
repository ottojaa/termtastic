/**
 * Structured activity events emitted by AI coding agents running in a terminal.
 *
 * This file defines [AgentEvent], the internal, CLI-agnostic event type that
 * drives auto-naming. Instead of scraping the rendered screen to guess when an
 * agent got a prompt or when its context was reset, Termtastic receives these
 * facts directly from the agent via its hook system (Claude Code today; Codex /
 * Gemini planned). A per-CLI producer translates the CLI's native hook payload
 * into an [AgentEvent] and publishes it on a shared flow that the `AutoNamer`
 * consumes.
 *
 * Producers:
 *  - Claude Code → [agentEventRoutes] (an HTTP hook POSTs to the loopback
 *    server, which maps `UserPromptSubmit` → [Prompt] and `SessionStart`
 *    (`startup`/`clear`) → [Reset]).
 *
 * Consumer: [installAutoNamer].
 *
 * @see agentEventRoutes
 * @see installAutoNamer
 * @see ClaudeAutoNameHooks for how the Claude hooks are registered per session.
 */
package se.soderbjorn.termtastic

/**
 * A single activity signal for the Termtastic session identified by
 * [sessionId] (Termtastic's own PTY session id, `sN`, not the agent CLI's
 * internal session id — correlation happens at the producer boundary).
 */
sealed interface AgentEvent {
    /** The Termtastic PTY session id this event pertains to. */
    val sessionId: String

    /**
     * The user submitted a prompt to the agent running in [sessionId]. Carries
     * the exact prompt [text] as reported by the agent's hook — no screen
     * scraping. The `AutoNamer` names the terminal from the first such event
     * of a run.
     *
     * @property sessionId the Termtastic PTY session id.
     * @property text the exact user prompt text.
     */
    data class Prompt(override val sessionId: String, val text: String) : AgentEvent

    /**
     * The agent's context was reset in place — a new session started
     * (`SessionStart` source `startup`) or the user ran `/clear` (source
     * `clear`). This re-arms auto-naming so the next [Prompt] names the terminal
     * afresh. Deliberately excludes `compact`/`resume`, which continue the same
     * task and must not trigger a re-name.
     *
     * @property sessionId the Termtastic PTY session id.
     * @property reason the originating source value, for logging.
     */
    data class Reset(override val sessionId: String, val reason: String) : AgentEvent
}
