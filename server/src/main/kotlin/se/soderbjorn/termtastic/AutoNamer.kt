/**
 * Opt-in automatic terminal naming from AI-agent activity.
 *
 * This file contains [installAutoNamer], the background collector that watches
 * the per-session AI-state flow (the same one that drives the "agent working"
 * glow) and, on the first idle → working transition of an *agent session*,
 * infers a short contextual name for the pane and applies it.
 *
 * **Granularity — once per agent session, not once per terminal.** The
 * bookkeeping and reset semantics live in [AutoNamerGuard] (pure + unit-tested);
 * this file is the async orchestration around it. Naming is gated per agent run
 * rather than by the persisted [LeafNode.inferredName], so a second
 * `claude`/`codex`/`gemini` run in the same terminal is named again. The guard
 * is reset when either:
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
 * @see AutoNamerGuard
 * @see NameInferrer
 * @see WindowState.applyInferredName
 * @see installSessionStatePoller
 * @see se.soderbjorn.termtastic.pty.ProcessTreeReader
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.pty.ProcessTreeReader
import java.util.concurrent.atomic.AtomicLong

/**
 * UI-settings key for the opt-in toggle. Must match `KEY_TERMINAL_AUTO_NAME`
 * in the web client's `AppSettingsContent.kt` (separate module — the string is
 * the shared contract).
 */
const val TERMINAL_AUTO_NAME_KEY = "terminalAutoName"

/**
 * Cap on concurrent `claude -p` summarizations. Several sessions can flip to
 * "working" at once (server start / layout restore fires on the first observed
 * edge), and each names via a spawned agent; this bounds the process fan-out.
 */
private const val MAX_CONCURRENT_INFERENCES = 3

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
    val guard = AutoNamerGuard()
    // Bounds concurrent claude spawns (see MAX_CONCURRENT_INFERENCES).
    val inferenceSlots = Semaphore(MAX_CONCURRENT_INFERENCES)
    // Coarse health signal: successes / attempts. Logged on each outcome so a
    // silent decay of the scrape-based extraction (a CLI reskinning its TUI) is
    // observable rather than invisible.
    val attempted = AtomicLong(0)
    val succeeded = AtomicLong(0)

    log.info(
        "AutoNamer: installed (opt-in key '{}', currently enabled={})",
        TERMINAL_AUTO_NAME_KEY, autoNameEnabled(repo),
    )
    scope.launch {
        sessionStates.collect { states ->
            // When disabled, still keep bookkeeping tidy but skip all naming
            // work (no pane scans, no inference, no logging) — free when off.
            if (!autoNameEnabled(repo)) {
                guard.retain(states.keys)
                guard.commit(states)
                return@collect
            }

            for (sid in guard.startedWorking(states)) {
                if (!guard.eligible(sid, paneNeedsName(sid))) continue
                log.info("AutoNamer: session {} -> working; inferring name", sid)
                guard.beginNaming(sid)
                val session = TerminalSessions.get(sid)
                // Capture the agent pid + clear-generation now, at the working
                // edge — NOT after the (up-to-25s) inference, by which point the
                // shell's foreground child may have changed (fast task done, a
                // stray `ls`, …), which would record a wrong/transient pid.
                val pid = session?.shellPid()?.let { ProcessTreeReader.firstChild(it) }
                val clearGen = session?.clearCommandGeneration() ?: 0L
                launch {
                    var named = false
                    try {
                        val text = session?.snapshotVisibleText().orEmpty()
                        val name = if (text.isBlank()) null else inferenceSlots.withPermit { inferrer.infer(text) }
                        if (name != null) {
                            WindowState.applyInferredName(sid, name)
                            named = true
                            log.info(
                                "AutoNamer: named session {} -> \"{}\" (rate {}/{})",
                                sid, name, succeeded.incrementAndGet(), attempted.incrementAndGet(),
                            )
                        } else {
                            log.info(
                                "AutoNamer: no name produced for {} (rate {}/{})",
                                sid, succeeded.get(), attempted.incrementAndGet(),
                            )
                        }
                    } catch (t: Throwable) {
                        attempted.incrementAndGet()
                        log.warn("AutoNamer: inference failed for {}", sid, t)
                    } finally {
                        guard.finishNaming(sid, named, pid, clearGen)
                    }
                }
            }

            // Reset the per-run guard for sessions whose run ended (agent pid no
            // longer alive — cheap ProcessHandle lookup, no fork) or whose
            // context was cleared (/clear). Either means the next prompt renames.
            val reset = guard.resetEnded(
                alive = { pid -> ProcessHandle.of(pid).isPresent },
                clearGenOf = { sid -> TerminalSessions.get(sid)?.clearCommandGeneration() },
            )
            for ((sid, why) in reset) {
                log.info("AutoNamer: reset {} — {}; will auto-name the next prompt", sid, why)
            }

            guard.retain(states.keys)
            guard.commit(states)
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
