/**
 * Pure once-per-run bookkeeping for the event-driven AutoNamer.
 *
 * Extracted from [installAutoNamer] so the naming semantics can be unit-tested
 * without coroutines or the PTY/inference side effects. The orchestrator feeds
 * it [AgentEvent]s (indirectly) and asks it *when* to name.
 *
 * ## Semantics (the behavior most likely to regress)
 *  - **name once per run**: after a session is successfully named, further
 *    prompts don't rename it — until a reset;
 *  - **reset** ([reset], driven by [AgentEvent.Reset] = new run / `/clear`)
 *    re-arms the session so the next prompt names it afresh;
 *  - **stale-name guard**: if a `/clear` (reset) lands *while* an inference for
 *    the previous prompt is in flight, that inference's result is discarded
 *    (its captured generation no longer matches) rather than labelling the new
 *    task with the old prompt's name;
 *  - **attempt cap**: a run makes at most [maxAttempts] inference attempts
 *    (each failure burns one) before giving up until the next reset.
 *
 * Thread-safety: [finish] runs on the inference coroutine while the collector
 * calls the others, so all methods are synchronized.
 *
 * @property maxAttempts inference attempts per run before giving up.
 * @see installAutoNamer
 * @see AgentEvent
 */
package se.soderbjorn.termtastic

class AutoNamerGuard(private val maxAttempts: Int = 3) {

    private val named = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val attempts = HashMap<String, Int>()
    private val generation = HashMap<String, Long>()

    /**
     * Begin an inference for [sid] if eligible: not already named this run, not
     * in flight, and under the attempt cap. Marks it in flight and counts an
     * attempt.
     *
     * @param sid the session id.
     * @return a generation token to pass back to [finish], or `null` if the
     *   session is not eligible right now.
     */
    @Synchronized
    fun begin(sid: String): Long? {
        if (sid in named || sid in inFlight || (attempts[sid] ?: 0) >= maxAttempts) return null
        inFlight += sid
        attempts[sid] = (attempts[sid] ?: 0) + 1
        return generation[sid] ?: 0L
    }

    /**
     * Complete an inference started with [token]. Clears the in-flight mark and,
     * on [success], marks the session named for this run — but only if no [reset]
     * bumped its generation in the meantime.
     *
     * @param sid the session id.
     * @param token the generation returned by the matching [begin].
     * @param success whether a usable name was produced.
     * @return `true` if the caller should apply the name (produced *and* still
     *   current); `false` if it should be dropped (failed, or superseded by a
     *   reset during inference).
     */
    @Synchronized
    fun finish(sid: String, token: Long, success: Boolean): Boolean {
        inFlight -= sid
        val current = (generation[sid] ?: 0L) == token
        if (success && current) {
            named += sid
            return true
        }
        return false
    }

    /**
     * Re-arm [sid] after a context reset (new run / `/clear`): forget that it
     * was named, reset its attempt count, and bump its generation so any
     * in-flight inference for the previous prompt is discarded by [finish].
     *
     * @param sid the session id.
     */
    @Synchronized
    fun reset(sid: String) {
        named -= sid
        attempts -= sid
        generation[sid] = (generation[sid] ?: 0L) + 1
    }

    /** Drop all bookkeeping for sessions no longer in [liveSessionIds]. */
    @Synchronized
    fun retain(liveSessionIds: Set<String>) {
        named.retainAll(liveSessionIds)
        inFlight.retainAll(liveSessionIds)
        attempts.keys.retainAll(liveSessionIds)
        generation.keys.retainAll(liveSessionIds)
    }

    /** Whether [sid] has been named in the current run (test/inspection aid). */
    @Synchronized
    fun isNamed(sid: String): Boolean = sid in named
}
