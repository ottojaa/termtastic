/**
 * Opt-in automatic terminal naming from AI-agent activity.
 *
 * This file contains [installAutoNamer], the background collector that watches
 * the per-session AI-state flow (the same one that drives the "agent working"
 * glow) and, on the first idle → working transition of an *agent session*,
 * infers a short contextual name for the pane and applies it.
 *
 * **Granularity — once per agent session, not once per terminal.** Naming is
 * gated by an in-memory `namedThisRun` set rather than the persisted
 * [LeafNode.inferredName], so a second `claude`/`codex`/`gemini` run in the
 * same terminal is named again. The guard is reset when either:
 *  - the agent process exits or is replaced — the pid recorded at naming
 *    (via [se.soderbjorn.termtastic.pty.ProcessTreeReader]) is no longer alive; or
 *  - the user submits a `/clear` (or shell `clear`) — an in-place context reset
 *    in the same process, detected from the user's keystrokes by
 *    [se.soderbjorn.termtastic.pty.InputCommandScanner].
 *
 * Between turns neither happens, so there's no per-turn re-naming churn. A pane
 * the user renamed manually ([LeafNode.customName]) is never touched.
 *
 * Enablement is a live opt-in: the `terminalAutoName` UI-settings key, toggled
 * from the App Settings sidebar's Terminal section and read here via
 * [SettingsRepository.uiSettings], so turning it on/off takes effect with no
 * restart.
 *
 * Wired from [main] alongside [installSessionStatePoller].
 *
 * @see NameInferrer
 * @see WindowState.applyInferredName
 * @see installSessionStatePoller
 * @see se.soderbjorn.termtastic.pty.ProcessTreeReader
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.pty.ProcessTreeReader
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-settings key for the opt-in toggle. Must match `KEY_TERMINAL_AUTO_NAME`
 * in the web client's `AppSettingsContent.kt` (separate module — the string is
 * the shared contract).
 */
const val TERMINAL_AUTO_NAME_KEY = "terminalAutoName"

/** Max inference attempts per agent run before giving up (bounds retries/cost). */
private const val MAX_ATTEMPTS = 3

/**
 * Launch the auto-namer coroutine.
 *
 * Collects [sessionStates], detecting when a session flips to `"working"` from
 * a non-working state (idle or absent). On that edge — when the feature is
 * enabled, the pane has no manual name, and this agent run hasn't been named
 * yet — it snapshots the session's visible screen, asks [inferrer] for a short
 * title, and applies it via [WindowState.applyInferredName] (which broadcasts +
 * persists like any other layout mutation). Each emission also resets the
 * per-run guard for sessions whose agent has since exited, so the next agent
 * run in the same terminal is named afresh.
 *
 * @param scope the coroutine scope owning the collector (the persistence scope).
 * @param sessionStates the per-session AI-state flow from [installSessionStatePoller].
 * @param repo settings repository; read for the live `terminalAutoName` flag.
 * @param inferrer the name-inference strategy (see [defaultNameInferrer]).
 */
fun installAutoNamer(
    scope: CoroutineScope,
    sessionStates: SharedFlow<Map<String, String?>>,
    repo: SettingsRepository,
    inferrer: NameInferrer,
) {
    val log = LoggerFactory.getLogger("AutoNamer")
    // Guard against launching a second inference for the same session while one
    // is in flight.
    val inFlight = ConcurrentHashMap.newKeySet<String>()
    // Sessions successfully named during the CURRENT agent run. Reset when the
    // agent process exits, so a new run in the same terminal is named again.
    val namedThisRun = ConcurrentHashMap.newKeySet<String>()
    // The agent process pid captured when each named session was named. Used to
    // detect the agent exiting or being replaced (a new run has a new pid).
    val agentPid = ConcurrentHashMap<String, Long>()
    // The session's clear-command count captured at naming. A later change means
    // the user submitted /clear (or `clear`) — an in-place context reset in the
    // same agent process — so the next prompt should be named afresh.
    val namedClearGen = ConcurrentHashMap<String, Long>()
    // Per-run attempt counter (reset alongside [namedThisRun]).
    val attempts = ConcurrentHashMap<String, Int>()
    var prev: Map<String, String?> = emptyMap()

    log.info(
        "AutoNamer: installed (opt-in key '{}', currently enabled={})",
        TERMINAL_AUTO_NAME_KEY, autoNameEnabled(repo),
    )
    scope.launch {
        sessionStates.collect { states ->
            val enabled = autoNameEnabled(repo)
            for ((sid, state) in states) {
                val startedWorking = state == "working" && prev[sid] != "working"
                if (!startedWorking) continue

                // Log the full decision on every idle→working edge so it's clear
                // which gate (if any) skipped naming. Edges are infrequent.
                val att = attempts[sid] ?: 0
                val needs = paneNeedsName(sid)
                val alreadyNamed = sid in namedThisRun
                log.info(
                    "AutoNamer: session {} -> working (enabled={}, needsName={}, namedThisRun={}, attempts={}, inFlight={})",
                    sid, enabled, needs, alreadyNamed, att, sid in inFlight,
                )
                if (!enabled || sid in inFlight || alreadyNamed || att >= MAX_ATTEMPTS || !needs) continue

                inFlight += sid
                attempts[sid] = att + 1
                val session = TerminalSessions.get(sid)
                launch {
                    try {
                        val text = session?.snapshotVisibleText().orEmpty()
                        log.info("AutoNamer: inferring name for {} ({} chars of screen)", sid, text.length)
                        val name = if (text.isBlank()) null else inferrer.infer(text)
                        if (name != null) {
                            WindowState.applyInferredName(sid, name)
                            namedThisRun += sid
                            // Record the agent's pid (to detect this run ending
                            // or being replaced) and the hard-clear count (to
                            // detect an in-place /clear reset).
                            session?.let { s ->
                                s.shellPid()?.let { shell ->
                                    ProcessTreeReader.firstChild(shell)?.let { agentPid[sid] = it }
                                }
                                namedClearGen[sid] = s.clearCommandGeneration()
                            }
                            log.info("AutoNamer: named session {} -> \"{}\"", sid, name)
                        } else {
                            log.info("AutoNamer: no name produced for {}", sid)
                        }
                    } catch (t: Throwable) {
                        log.warn("AutoNamer: inference failed for {}", sid, t)
                    } finally {
                        inFlight -= sid
                    }
                }
            }

            // Reset the per-run guard for named sessions when either the agent
            // exited/was replaced (recorded pid no longer alive — cheap
            // ProcessHandle lookup, no fork) OR the terminal was hard-cleared
            // (/clear, `clear`). Either means a fresh context, so the next
            // prompt should be named again.
            for (sid in namedThisRun) {
                val session = TerminalSessions.get(sid)
                val pid = agentPid[sid]
                val agentGone = pid != null && ProcessHandle.of(pid).isEmpty
                val cleared = session != null &&
                    namedClearGen[sid]?.let { session.clearCommandGeneration() != it } == true
                if (agentGone || cleared) {
                    namedThisRun.remove(sid)
                    attempts.remove(sid)
                    agentPid.remove(sid)
                    namedClearGen.remove(sid)
                    val why = if (cleared) "/clear (context reset)" else "agent pid $pid exited"
                    log.info("AutoNamer: reset {} — {}; will auto-name the next prompt", sid, why)
                }
            }

            // Forget bookkeeping for sessions that have gone away.
            attempts.keys.retainAll(states.keys)
            inFlight.retainAll(states.keys)
            namedThisRun.retainAll(states.keys)
            agentPid.keys.retainAll(states.keys)
            namedClearGen.keys.retainAll(states.keys)
            prev = states
        }
    }
}

/**
 * Read the live `terminalAutoName` opt-in flag from UI settings, tolerating
 * both JSON-boolean and stringified-"true" shapes (writes can land either way).
 *
 * @param repo the settings repository whose UI-settings flow to read.
 * @return `true` when the user has enabled auto-naming.
 */
private fun autoNameEnabled(repo: SettingsRepository): Boolean {
    val el = repo.uiSettings.value[TERMINAL_AUTO_NAME_KEY] as? JsonPrimitive ?: return false
    el.booleanOrNull?.let { return it }
    return el.isString && el.content == "true"
}

/**
 * Whether the pane(s) backed by [sessionId] may receive an auto-name: there is
 * a live terminal leaf for the session with no user [LeafNode.customName]. The
 * once-per-agent-run guard lives in [installAutoNamer]'s `namedThisRun` (not
 * here), so an existing [LeafNode.inferredName] does NOT block re-naming a new
 * agent session.
 *
 * @param sessionId the PTY session id.
 * @return `true` when at least one matching terminal leaf has no manual name.
 */
private fun paneNeedsName(sessionId: String): Boolean {
    for (tab in WindowState.config.value.tabs) {
        for (p in tab.panes) {
            val leaf = p.leaf
            val sid = (leaf.content as? TerminalContent)?.sessionId
                ?: leaf.sessionId.takeIf { it.isNotEmpty() }
            if (sid == sessionId && leaf.customName.isNullOrBlank()) {
                return true
            }
        }
    }
    return false
}
