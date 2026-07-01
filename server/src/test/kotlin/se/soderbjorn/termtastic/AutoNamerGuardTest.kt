/**
 * Unit tests for [AutoNamerGuard] — the once-per-agent-session reset state
 * machine that decides when a terminal may be (re)named. Covers the semantics
 * most likely to regress: name once per run, re-name after the agent exits,
 * re-name after `/clear`, and do NOT re-name between turns.
 *
 * @see AutoNamerGuard
 */
package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for [AutoNamerGuard]'s transition detection, gating, and resets. */
class AutoNamerGuardTest {

    // ── transition detection ────────────────────────────────────────────

    @Test
    fun `startedWorking detects idle to working edge`() {
        val g = AutoNamerGuard()
        g.commit(mapOf("s1" to null))
        assertEquals(listOf("s1"), g.startedWorking(mapOf("s1" to "working")))
    }

    @Test
    fun `startedWorking ignores staying working (no per-turn edge)`() {
        val g = AutoNamerGuard()
        g.commit(mapOf("s1" to "working"))
        assertEquals(emptyList(), g.startedWorking(mapOf("s1" to "working")))
    }

    // ── eligibility gating ──────────────────────────────────────────────

    @Test
    fun `eligible only when the pane needs a name`() {
        val g = AutoNamerGuard()
        assertTrue(g.eligible("s1", needsName = true))
        assertFalse(g.eligible("s1", needsName = false))
    }

    @Test
    fun `in-flight and named sessions are not eligible`() {
        val g = AutoNamerGuard()
        g.beginNaming("s1")
        assertFalse(g.eligible("s1", needsName = true)) // in flight
        g.finishNaming("s1", named = true, pid = 100, clearGen = 0)
        assertFalse(g.eligible("s1", needsName = true)) // named this run
    }

    @Test
    fun `attempt cap disables naming after repeated failures`() {
        val g = AutoNamerGuard(maxAttempts = 2)
        g.beginNaming("s1"); g.finishNaming("s1", named = false, pid = null, clearGen = 0)
        assertTrue(g.eligible("s1", needsName = true))  // 1 attempt < 2
        g.beginNaming("s1"); g.finishNaming("s1", named = false, pid = null, clearGen = 0)
        assertFalse(g.eligible("s1", needsName = true)) // 2 attempts == cap
    }

    // ── reset semantics ─────────────────────────────────────────────────

    @Test
    fun `agent exit resets the guard and re-enables naming`() {
        val g = named(pid = 100, clearGen = 0)
        val reset = g.resetEnded(alive = { false }, clearGenOf = { 0 })
        assertEquals(listOf("s1"), reset.map { it.first })
        assertTrue(reset.single().second.contains("exited"))
        assertTrue(g.eligible("s1", needsName = true))
    }

    @Test
    fun `clear resets the guard and re-enables naming`() {
        val g = named(pid = 100, clearGen = 0)
        val reset = g.resetEnded(alive = { true }, clearGenOf = { 1 }) // gen changed
        assertEquals(listOf("s1"), reset.map { it.first })
        assertTrue(reset.single().second.contains("/clear"))
        assertTrue(g.eligible("s1", needsName = true))
    }

    @Test
    fun `no reset between turns while the agent is alive and unchanged`() {
        val g = named(pid = 100, clearGen = 0)
        val reset = g.resetEnded(alive = { true }, clearGenOf = { 0 })
        assertTrue(reset.isEmpty())
        assertFalse(g.eligible("s1", needsName = true)) // still named this run
    }

    // ── cleanup ─────────────────────────────────────────────────────────

    @Test
    fun `retain drops bookkeeping for closed sessions`() {
        val g = named(pid = 100, clearGen = 0)
        g.retain(emptySet())
        assertTrue(g.eligible("s1", needsName = true)) // no longer named
    }

    /** A guard with "s1" already named for the current run. */
    private fun named(pid: Long, clearGen: Long) = AutoNamerGuard().apply {
        beginNaming("s1")
        finishNaming("s1", named = true, pid = pid, clearGen = clearGen)
    }
}
