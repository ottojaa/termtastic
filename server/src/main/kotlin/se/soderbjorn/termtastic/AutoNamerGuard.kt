/**
 * Pure once-per-agent-session bookkeeping for the AutoNamer.
 *
 * Extracted from [installAutoNamer] so the tricky reset semantics can be
 * unit-tested without coroutines, PTY sessions, or process globals. The
 * orchestrator injects the side effects (screen snapshot, inference, pid
 * liveness, clear-generation) via lambdas; this class only tracks state and
 * decides *when* to name and *when* a run has ended.
 *
 * ## Reset semantics (the behavior most likely to regress)
 *  - **agent exits or is replaced** (the pid recorded at naming is no longer
 *    alive) → the session becomes eligible to be named again;
 *  - **user runs `/clear`** (the clear-generation differs from the one captured
 *    at naming) → eligible again;
 *  - **between turns** (same agent pid alive, no clear) → NOT re-named.
 *
 * Thread-safety: [finishNaming] is called from the inference coroutine while
 * the collector calls the other methods, so all methods are synchronized. The
 * injected lambdas in [resetEnded] must be cheap and non-blocking (they run
 * under the lock).
 *
 * @property maxAttempts inference attempts per run before giving up (a failed
 *   inference still burns one — see [beginNaming]).
 * @see installAutoNamer
 */
package se.soderbjorn.termtastic

class AutoNamerGuard(private val maxAttempts: Int = 3) {

    private val inFlight = HashSet<String>()
    private val namedThisRun = HashSet<String>()
    private val agentPid = HashMap<String, Long>()
    private val namedClearGen = HashMap<String, Long>()
    private val attempts = HashMap<String, Int>()
    private var prev: Map<String, String?> = emptyMap()

    /**
     * Session ids that transitioned into `"working"` since the last [commit]
     * (were not `"working"` in the previous snapshot).
     *
     * @param states the latest per-session state snapshot.
     * @return the ids that just started working.
     */
    @Synchronized
    fun startedWorking(states: Map<String, String?>): List<String> =
        states.filter { (sid, st) -> st == "working" && prev[sid] != "working" }.keys.toList()

    /**
     * Whether [sid] may be named now: not already in flight or named this run,
     * under the attempt cap, and the pane still [needsName] (no manual name).
     *
     * @param sid the session id.
     * @param needsName whether the pane lacks a user-set name.
     * @return `true` if an inference should be started.
     */
    @Synchronized
    fun eligible(sid: String, needsName: Boolean): Boolean =
        sid !in inFlight && sid !in namedThisRun && (attempts[sid] ?: 0) < maxAttempts && needsName

    /** Mark [sid] as having an inference in flight; counts an attempt. */
    @Synchronized
    fun beginNaming(sid: String) {
        inFlight += sid
        attempts[sid] = (attempts[sid] ?: 0) + 1
    }

    /**
     * Record an inference result. On success the session is marked named for
     * this run, capturing the agent [pid] and clear-generation [clearGen] used
     * later by [resetEnded] to detect the run ending.
     *
     * @param sid the session id.
     * @param named whether a name was produced and applied.
     * @param pid the agent process pid at naming time (or `null` if unknown).
     * @param clearGen the session's clear-command generation at naming time.
     */
    @Synchronized
    fun finishNaming(sid: String, named: Boolean, pid: Long?, clearGen: Long) {
        inFlight -= sid
        if (named) {
            namedThisRun += sid
            if (pid != null) agentPid[sid] = pid
            namedClearGen[sid] = clearGen
        }
    }

    /**
     * Reset the per-run guard for named sessions whose run has ended — the
     * recorded agent pid is no longer [alive], or the session's current
     * clear-generation ([clearGenOf]) differs from the value captured at naming
     * (a `/clear`). Cleared sessions become eligible to be named again.
     *
     * @param alive reports whether a pid is still a live process.
     * @param clearGenOf current clear-generation for a session, or `null` if gone.
     * @return `(sessionId, reason)` for each session that was reset.
     */
    @Synchronized
    fun resetEnded(alive: (Long) -> Boolean, clearGenOf: (String) -> Long?): List<Pair<String, String>> {
        val reset = mutableListOf<Pair<String, String>>()
        for (sid in namedThisRun.toList()) {
            val pid = agentPid[sid]
            val agentGone = pid != null && !alive(pid)
            val capturedGen = namedClearGen[sid]
            val curGen = clearGenOf(sid)
            val cleared = capturedGen != null && curGen != null && curGen != capturedGen
            if (agentGone || cleared) {
                namedThisRun -= sid
                attempts -= sid
                agentPid -= sid
                namedClearGen -= sid
                reset += sid to if (cleared) "/clear (context reset)" else "agent pid $pid exited"
            }
        }
        return reset
    }

    /** Drop bookkeeping for sessions no longer present in [liveSessionIds]. */
    @Synchronized
    fun retain(liveSessionIds: Set<String>) {
        inFlight.retainAll(liveSessionIds)
        namedThisRun.retainAll(liveSessionIds)
        attempts.keys.retainAll(liveSessionIds)
        agentPid.keys.retainAll(liveSessionIds)
        namedClearGen.keys.retainAll(liveSessionIds)
    }

    /** Record [states] as the baseline for the next [startedWorking] diff. */
    @Synchronized
    fun commit(states: Map<String, String?>) {
        prev = states
    }
}
