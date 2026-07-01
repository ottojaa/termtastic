/**
 * Opt-in automatic terminal naming from AI-agent activity.
 *
 * This file contains [installAutoNamer], the background collector that consumes
 * the structured [AgentEvent] stream (fed by [agentEventRoutes] from Claude
 * Code's hooks) and names a pane from the first prompt of each agent run.
 *
 * **Event-sourced, not screen-scraped.** The trigger and the prompt text come
 * directly from the agent via its hook system — [AgentEvent.Prompt] carries the
 * exact prompt, [AgentEvent.Reset] marks a new run / `/clear`. There is no
 * screen scraping, no process-tree polling, and no input-stream parsing; the
 * per-run bookkeeping lives in [AutoNamerGuard] (pure + unit-tested).
 *
 * **Granularity — once per agent run.** After a session is named, further
 * prompts don't rename it until an [AgentEvent.Reset] re-arms it (a new agent
 * session or a `/clear`; `compact`/`resume` are filtered out at the producer).
 * A pane the user renamed manually ([LeafNode.customName]) is never touched.
 *
 * Enablement is a live opt-in: the `terminalAutoName` UI-settings key, read here
 * via [SettingsRepository.uiSettings], so toggling takes effect with no restart.
 * (Whether a *new* terminal even registers the Claude hook is decided at spawn
 * by [ClaudeAutoNameHooks]; this collector additionally gates each event.)
 *
 * Wired from [main]; events arrive from [agentEventRoutes].
 *
 * @see AgentEvent
 * @see AutoNamerGuard
 * @see NameInferrer
 * @see ClaudeAutoNameHooks
 * @see WindowState.applyInferredName
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

/**
 * UI-settings key for the opt-in toggle. Must match `KEY_TERMINAL_AUTO_NAME`
 * in the web client's `AppSettingsContent.kt` (separate module — the string is
 * the shared contract).
 */
const val TERMINAL_AUTO_NAME_KEY = "terminalAutoName"

/**
 * Cap on concurrent `claude -p` summarizations. Several sessions can submit a
 * first prompt close together (e.g. a burst after launch), and each names via a
 * spawned agent; this bounds the process fan-out.
 */
private const val MAX_CONCURRENT_INFERENCES = 3

/**
 * Launch the auto-namer coroutine.
 *
 * Collects [events]:
 *  - [AgentEvent.Prompt] — when enabled, the pane has no manual name, and the
 *    run hasn't been named yet, spawns an inference and applies the result via
 *    [WindowState.applyInferredName].
 *  - [AgentEvent.Reset] — re-arms the session so its next prompt names afresh.
 *
 * @param scope the coroutine scope owning the collector (the persistence scope).
 * @param events the structured agent-activity stream from [agentEventRoutes].
 * @param repo settings repository; read for the live `terminalAutoName` flag.
 * @param inferrer the name-inference strategy (see [defaultNameInferrer]).
 */
fun installAutoNamer(
    scope: CoroutineScope,
    events: SharedFlow<AgentEvent>,
    repo: SettingsRepository,
    inferrer: NameInferrer,
) {
    val log = LoggerFactory.getLogger("AutoNamer")
    val guard = AutoNamerGuard()
    val inferenceSlots = Semaphore(MAX_CONCURRENT_INFERENCES)

    log.info(
        "AutoNamer: installed (opt-in key '{}', currently enabled={})",
        TERMINAL_AUTO_NAME_KEY, autoNameEnabled(repo),
    )
    scope.launch {
        events.collect { event ->
            when (event) {
                is AgentEvent.Reset -> {
                    guard.reset(event.sessionId)
                    log.info("AutoNamer: reset {} — {}; will name the next prompt", event.sessionId, event.reason)
                }

                is AgentEvent.Prompt -> {
                    // Gate: feature on, and this pane has no manual name.
                    if (!autoNameEnabled(repo)) return@collect
                    if (!paneNeedsName(event.sessionId)) return@collect
                    val token = guard.begin(event.sessionId) ?: return@collect
                    log.info("AutoNamer: naming session {} from prompt", event.sessionId)
                    launch {
                        val name = try {
                            inferenceSlots.withPermit { inferrer.infer(event.text) }
                        } catch (t: Throwable) {
                            log.warn("AutoNamer: inference failed for {}", event.sessionId, t)
                            null
                        }
                        // finish() reconciles against any reset that landed mid-inference.
                        if (guard.finish(event.sessionId, token, name != null) && name != null) {
                            WindowState.applyInferredName(event.sessionId, name)
                            log.info("AutoNamer: named session {} -> \"{}\"", event.sessionId, name)
                        } else if (name == null) {
                            log.info("AutoNamer: no name produced for {}", event.sessionId)
                        } else {
                            log.info("AutoNamer: dropped stale name for {} (context reset mid-inference)", event.sessionId)
                        }
                    }
                }
            }
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
internal fun autoNameEnabled(repo: SettingsRepository): Boolean {
    val el = repo.uiSettings.value[TERMINAL_AUTO_NAME_KEY] as? JsonPrimitive ?: return false
    el.booleanOrNull?.let { return it }
    return el.isString && el.content == "true"
}

/**
 * Whether the pane(s) backed by [sessionId] may receive an auto-name: there is
 * a live terminal leaf for the session with no user [LeafNode.customName]. The
 * once-per-run guard lives in [AutoNamerGuard] (not here), so an existing
 * [LeafNode.inferredName] does NOT block re-naming after a reset.
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
