/**
 * Unit tests for [AutoNamerGuard] — the once-per-run bookkeeping that decides
 * when a terminal may be (re)named in the event-driven AutoNamer. Covers the
 * semantics most likely to regress: name once per run, re-name after a reset
 * (`/clear` or a new run), don't re-name between prompts, drop a name whose
 * inference was superseded by a reset, and the per-run attempt cap.
 *
 * @see AutoNamerGuard
 */
package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for [AutoNamerGuard]'s gating, reset re-arming, and stale-name guard. */
class AutoNamerGuardTest {

    @Test
    fun `names once per run then blocks further prompts`() {
        val g = AutoNamerGuard()
        val token = g.begin("s1")
        assertNotNull(token)
        assertTrue(g.finish("s1", token, success = true))
        assertTrue(g.isNamed("s1"))
        // A second prompt in the same run is not eligible.
        assertNull(g.begin("s1"))
    }

    @Test
    fun `a reset re-arms a named session`() {
        val g = AutoNamerGuard()
        val t1 = g.begin("s1")!!
        g.finish("s1", t1, success = true)
        assertNull(g.begin("s1"))

        g.reset("s1")
        assertFalse(g.isNamed("s1"))
        val t2 = g.begin("s1")
        assertNotNull(t2) // eligible again after /clear or new run
    }

    @Test
    fun `a failed inference does not consume the name but counts an attempt`() {
        val g = AutoNamerGuard()
        val t1 = g.begin("s1")!!
        assertFalse(g.finish("s1", t1, success = false))
        assertFalse(g.isNamed("s1"))
        // Still eligible to retry (until the attempt cap).
        assertNotNull(g.begin("s1"))
    }

    @Test
    fun `gives up after the attempt cap until a reset`() {
        val g = AutoNamerGuard(maxAttempts = 2)
        g.finish("s1", g.begin("s1")!!, success = false)
        g.finish("s1", g.begin("s1")!!, success = false)
        assertNull(g.begin("s1")) // cap reached
        g.reset("s1")
        assertNotNull(g.begin("s1")) // reset clears the attempt count
    }

    @Test
    fun `a reset during inference discards the stale name`() {
        val g = AutoNamerGuard()
        val token = g.begin("s1")!!   // prompt A starts inferring
        g.reset("s1")                 // user hits /clear mid-inference
        // A's result comes back but must not label the post-clear task.
        assertFalse(g.finish("s1", token, success = true))
        assertFalse(g.isNamed("s1"))
    }

    @Test
    fun `retain drops bookkeeping for dead sessions`() {
        val g = AutoNamerGuard()
        g.finish("s1", g.begin("s1")!!, success = true)
        g.retain(emptySet())
        // s1 is forgotten, so it is eligible to be named afresh.
        assertFalse(g.isNamed("s1"))
        assertNotNull(g.begin("s1"))
    }
}
