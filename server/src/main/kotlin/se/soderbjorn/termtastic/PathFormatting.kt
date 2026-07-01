/**
 * Pure path / leaf-title formatting helpers used by [WindowState] when
 * updating leaf titles in response to cwd changes, custom-name changes, and
 * persisted-config rehydrations.
 *
 * Lives at file scope so the helpers can be reused from any module that
 * needs them without dragging in [WindowState]'s mutation surface.
 */
package se.soderbjorn.termtastic

/**
 * Collapse `$HOME` to `~` in [path] for display. Anything else is left intact —
 * shortening to basename is intentionally avoided so users can tell similarly
 * named directories apart at a glance.
 */
internal fun prettifyPath(path: String): String {
    val home = System.getProperty("user.home")
    if (home.isNullOrEmpty()) return path
    return when {
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.substring(home.length)
        else -> path
    }
}

/**
 * Resolve the display title for a pane:
 *  1. user-set [customName] wins (a manual rename is never overridden);
 *  2. else the agent-[inferredName] (see the server-side `AutoNamer`);
 *  3. else the prettified [cwd];
 *  4. else [fallback] (typically the auto-generated "Session N" label).
 *
 * Called by [PaneManager.renamePane], [PaneManager.updatePaneCwd],
 * [PaneManager.applyInferredName], and [WindowState] pane creation.
 *
 * @param customName the user's manual name, or `null`.
 * @param inferredName the auto-inferred contextual name, or `null`.
 * @param cwd the last known working directory, or `null`.
 * @param fallback the last-resort label when nothing else is set.
 * @return the resolved display title.
 */
internal fun computeLeafTitle(
    customName: String?,
    inferredName: String?,
    cwd: String?,
    fallback: String,
): String =
    customName?.takeIf { it.isNotBlank() }
        ?: inferredName?.takeIf { it.isNotBlank() }
        ?: cwd?.takeIf { it.isNotBlank() }?.let(::prettifyPath)
        ?: fallback

/**
 * Recompute the display title for an existing [leaf] from its own naming
 * fields. Convenience overload used by [PaneManager]'s mutation helpers: after
 * changing one of `customName` / `inferredName` / `cwd` on a copied leaf, call
 * this to derive the matching [LeafNode.title] — so no call site has to
 * remember to thread every title input through by hand, and future title
 * inputs are picked up automatically.
 *
 * @param leaf the (already-updated) leaf whose title to compute.
 * @param fallback the last-resort label; defaults to the leaf's current title,
 *   which preserves the previously-resolved name when nothing else is set.
 * @return the resolved display title.
 */
internal fun computeLeafTitle(leaf: LeafNode, fallback: String = leaf.title): String =
    computeLeafTitle(leaf.customName, leaf.inferredName, leaf.cwd, fallback)

/**
 * Returns a deep copy of this config with every [LeafNode.sessionId] blanked.
 * Persisted blobs use this so we never write live PTY ids to disk — they
 * become stale the moment the process exits.
 */
internal fun WindowConfig.withBlankSessionIds(): WindowConfig {
    fun blankContent(c: LeafContent?): LeafContent? = when (c) {
        is TerminalContent -> if (c.sessionId.isEmpty()) c else c.copy(sessionId = "")
        is FileBrowserContent, is GitContent, null -> c
    }
    fun stripLeaf(leaf: LeafNode): LeafNode {
        val newContent = blankContent(leaf.content)
        return if (leaf.sessionId.isEmpty() && newContent === leaf.content) leaf
        else leaf.copy(sessionId = "", content = newContent)
    }
    return copy(
        tabs = tabs.map { tab ->
            tab.copy(
                panes = tab.panes.map { p -> p.copy(leaf = stripLeaf(p.leaf)) },
            )
        }
    )
}
