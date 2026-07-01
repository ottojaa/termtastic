/**
 * Unit tests for the contextual terminal-naming feature.
 *
 * Covers:
 *  - [sanitizeInferredName] normalization rules (quotes, sentinel, word/char caps).
 *  - [HeuristicNameInferrer] prompt scraping from transcript text.
 *  - [computeLeafTitle] precedence (customName > inferredName > cwd > fallback).
 *  - [PaneManager.applyInferredName] applying/gating on a config snapshot.
 *
 * @see NameInferrer
 * @see PaneManager.applyInferredName
 * @see computeLeafTitle
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests for name sanitization, heuristic inference, and title precedence. */
class NameInferrerTest {

    // ── sanitizeInferredName ────────────────────────────────────────────

    @Test
    fun `sanitize strips surrounding quotes`() {
        assertEquals("Fix Login Bug", sanitizeInferredName("\"Fix Login Bug\""))
        assertEquals("Fix Login Bug", sanitizeInferredName("`Fix Login Bug`"))
    }

    @Test
    fun `sanitize maps the none sentinel and blanks to null`() {
        assertNull(sanitizeInferredName("none"))
        assertNull(sanitizeInferredName("None"))
        assertNull(sanitizeInferredName("   "))
        assertNull(sanitizeInferredName(""))
    }

    @Test
    fun `sanitize drops trailing punctuation`() {
        assertEquals("Fix the bug", sanitizeInferredName("Fix the bug."))
    }

    @Test
    fun `sanitize caps the word count`() {
        assertEquals("one two three four", sanitizeInferredName("one two three four five six seven"))
    }

    @Test
    fun `sanitize caps very long single tokens`() {
        val long = "a".repeat(80)
        val result = sanitizeInferredName(long)
        assertNotNull(result)
        assertEquals(36, result.length)
    }

    @Test
    fun `sanitize takes only the first non-empty line`() {
        assertEquals("Add Dark Mode", sanitizeInferredName("\nAdd Dark Mode\nsome explanation\n"))
    }

    @Test
    fun `sanitize strips HTML-unsafe characters`() {
        // A crafted on-screen payload must not survive into the (persisted +
        // broadcast) title with any HTML-significant character intact.
        val unsafe = "<>&\"'`\\"
        val payload = sanitizeInferredName("<img src=x onerror=alert(1)>")
        if (payload != null) assertFalse(payload.any { it in unsafe }, "leaked: $payload")
        val mixed = sanitizeInferredName("Fix <b>Login</b> & Auth")
        assertNotNull(mixed)
        assertFalse(mixed.any { it in unsafe }, "leaked: $mixed")
    }

    // ── HeuristicNameInferrer ───────────────────────────────────────────

    @Test
    fun `heuristic scrapes a plain prompt line`() = runBlocking {
        val transcript = """
            $ claude
            > add dark mode toggle
            Working...
        """.trimIndent()
        assertEquals("add dark mode toggle", HeuristicNameInferrer().infer(transcript))
    }

    @Test
    fun `heuristic scrapes a boxed prompt line`() = runBlocking {
        val transcript = "│ ❯ refactor auth flow                │"
        assertEquals("refactor auth flow", HeuristicNameInferrer().infer(transcript))
    }

    @Test
    fun `heuristic returns null when no prompt is present`() = runBlocking {
        val transcript = "just some ordinary shell output\nls -la\ntotal 0"
        assertNull(HeuristicNameInferrer().infer(transcript))
    }

    // ── computeLeafTitle precedence ─────────────────────────────────────

    @Test
    fun `computeLeafTitle prefers customName then inferredName then cwd then fallback`() {
        assertEquals("Manual", computeLeafTitle("Manual", "Inferred", "/opt/work", "fb"))
        assertEquals("Inferred", computeLeafTitle(null, "Inferred", "/opt/work", "fb"))
        assertEquals("/opt/work", computeLeafTitle(null, null, "/opt/work", "fb"))
        assertEquals("fb", computeLeafTitle(null, null, null, "fb"))
    }

    // ── PaneManager.applyInferredName ───────────────────────────────────

    @Test
    fun `applyInferredName sets title when no custom name`() {
        val cfg = cfgWith(leaf(sessionId = "s1", cwd = "/opt/proj", title = "/opt/proj"))
        val updated = PaneManager.applyInferredName(cfg, "s1", "Fix Login")
        assertNotNull(updated)
        val leaf = updated.tabs[0].panes[0].leaf
        assertEquals("Fix Login", leaf.inferredName)
        assertEquals("Fix Login", leaf.title)
    }

    @Test
    fun `applyInferredName records name but keeps a manual customName visible`() {
        val cfg = cfgWith(leaf(sessionId = "s1", customName = "MyName", title = "MyName"))
        val updated = PaneManager.applyInferredName(cfg, "s1", "Fix Login")
        assertNotNull(updated)
        val leaf = updated.tabs[0].panes[0].leaf
        assertEquals("Fix Login", leaf.inferredName)
        assertEquals("MyName", leaf.title) // manual name still wins
    }

    @Test
    fun `applyInferredName returns null for an unknown session`() {
        val cfg = cfgWith(leaf(sessionId = "s1", cwd = "/opt/proj", title = "/opt/proj"))
        assertNull(PaneManager.applyInferredName(cfg, "sX", "Fix Login"))
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun leaf(
        sessionId: String,
        customName: String? = null,
        inferredName: String? = null,
        cwd: String? = null,
        title: String,
    ) = LeafNode(
        id = "n1",
        sessionId = sessionId,
        title = title,
        customName = customName,
        inferredName = inferredName,
        cwd = cwd,
    )

    private fun cfgWith(leaf: LeafNode) = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "t1",
                title = "tab",
                panes = listOf(Pane(leaf = leaf, x = 0.0, y = 0.0, width = 0.5, height = 0.5, z = 1)),
            ),
        ),
    )
}
