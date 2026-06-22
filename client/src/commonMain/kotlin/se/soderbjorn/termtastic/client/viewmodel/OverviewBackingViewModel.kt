/**
 * Backing ViewModel for the Android/iOS "Overview" mode — a read-only,
 * miniaturised replica of the web/Electron tabs-and-panes experience.
 *
 * The overview shows a single scrollable row of tabs and, for the active tab,
 * a faithful layout of its panes (terminal / file-browser / git miniatures)
 * positioned by their server-owned geometry. This ViewModel owns all of the
 * platform-agnostic derivation so the Compose (Android) and SwiftUI (iOS)
 * front-ends stay thin renderers: it watches the `/window` socket, projects
 * the authoritative [WindowConfig] into a flat, render-ready [State], and
 * routes tab activation back to the server.
 *
 * It intentionally does **not** touch [se.soderbjorn.termtastic.client.PtySocket]
 * — terminal miniatures subscribe to the raw byte stream directly on each
 * platform (Termux on Android, a native renderer on iOS), exactly like
 * [TerminalBackingViewModel].
 *
 * @see AppBackingViewModel
 * @see TerminalBackingViewModel
 * @see se.soderbjorn.termtastic.client.WindowSocket
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.TabConfig
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.client.ToolkitPaneGeometry
import se.soderbjorn.termtastic.client.WindowSocket

/**
 * ViewModel backing the overview screen.
 *
 * Combines the server-pushed [WindowConfig] with the per-session state map and
 * the toolkit-owned pane geometry, then exposes a single [State] snapshot that
 * both platforms render directly.
 *
 * @param windowSocket the live `/window` WebSocket — source of config + state
 *   pushes and the channel for [setActiveTab].
 * @param geometryByTab authoritative `tabId -> (paneId -> geometry)` from the
 *   toolkit's `LAYOUT_STATE` blob. The web/Electron client renders *this*, not
 *   [se.soderbjorn.termtastic.Pane]'s placeholder fields, so the overview must
 *   too. Minimized (docked) panes carried here are omitted from the layout to
 *   stay a faithful replica. Defaults to an always-empty flow for callers (and
 *   tests) that don't track geometry; the projection then falls back to the
 *   config's own (less accurate) fields. Sourced on Android/iOS from
 *   [se.soderbjorn.termtastic.client.WindowStateRepository.geometryByTab].
 */
class OverviewBackingViewModel(
    private val windowSocket: WindowSocket,
    private val geometryByTab: StateFlow<Map<String, Map<String, ToolkitPaneGeometry>>> =
        MutableStateFlow(emptyMap()),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** Render-ready overview state; emits on every config or session-state push. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable, render-ready snapshot of the overview.
     *
     * Every visible tab carries its own [OverviewTab.panes] so a pager can
     * render any tab's layout (not just the active one) for follow-finger
     * swiping between tabs.
     *
     * @property tabs        every visible tab, in display order, each carrying
     *   its title, active flag, aggregate status dot, and its own panes.
     * @property activeTabId the id of the currently-selected tab, or `null`
     *   before the first config arrives.
     */
    data class State(
        val tabs: List<OverviewTab> = emptyList(),
        val activeTabId: String? = null,
    )

    /**
     * One tab in the strip / one page in the pager.
     *
     * @property id             the tab id (sent back via [setActiveTab]).
     * @property title          display label.
     * @property isActive       whether this is the currently-selected tab.
     * @property aggregateState worst-case status across the tab's panes —
     *   `"waiting"`, `"working"`, or `null` (idle); drives the status dot.
     * @property panes          this tab's panes, sorted bottom-to-top by
     *   [se.soderbjorn.termtastic.Pane.z], ready to be positioned by geometry.
     */
    data class OverviewTab(
        val id: String,
        val title: String,
        val isActive: Boolean,
        val aggregateState: String?,
        val panes: List<OverviewPane>,
    )

    /**
     * One pane in a tab's miniature layout.
     *
     * Geometry mirrors [se.soderbjorn.termtastic.Pane]: `(x, y, width, height)`
     * are fractions (0.0–1.0) of the content area; the renderer multiplies by
     * its measured size. The [leaf] is forwarded verbatim so the platform can
     * dispatch on [LeafNode.content] (terminal / file-browser / git) and read
     * the session id and title without re-walking the config.
     *
     * @property leaf         the pane's content descriptor.
     * @property x            top-left x as a fraction of the content width.
     * @property y            top-left y as a fraction of the content height.
     * @property width        width as a fraction of the content width.
     * @property height       height as a fraction of the content height.
     * @property z            stacking key; the list is pre-sorted ascending.
     * @property maximized    whether the pane should fill the content area.
     * @property isFocused    whether this is the tab's focused pane (accent outline).
     * @property sessionState the pane's PTY session state (`"working"` /
     *   `"waiting"` / `null`), or `null` for non-terminal panes.
     */
    data class OverviewPane(
        val leaf: LeafNode,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val z: Long,
        val maximized: Boolean,
        val isFocused: Boolean,
        val sessionState: String?,
    )

    /**
     * Start projecting config + state pushes into [stateFlow]. Long-running —
     * cancels when the enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                combine(
                    windowSocket.config.filterNotNull(),
                    windowSocket.states,
                    geometryByTab,
                ) { config, states, geometry -> project(config, states, geometry) }
                    .collect { _stateFlow.value = it }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    /**
     * Make [tabId] the active tab. Server-authoritative, exactly like the web
     * client: the command is sent to the server, which persists the choice and
     * broadcasts an updated [WindowConfig] to every connected client (so the
     * change syncs across devices). The local [State] updates when that echo
     * arrives, not optimistically.
     *
     * @param tabId the tab to activate.
     */
    suspend fun setActiveTab(tabId: String) {
        windowSocket.send(WindowCommand.SetActiveTab(tabId))
    }

    // ── Projection ──────────────────────────────────────────────────

    /**
     * Flatten a [WindowConfig] + session-state map + toolkit geometry into a
     * render-ready [State].
     *
     * Tabs hidden from the tab strip ([TabConfig.isHidden]) are excluded —
     * mobile mirrors the web/Electron tab bar, where hidden tabs are reachable
     * only via the overflow menu, so the overview simply omits them.
     *
     * @param config   the authoritative layout.
     * @param states   map of session id → state string (`"working"` /
     *   `"waiting"` / `null`).
     * @param geometry `tabId -> (paneId -> geometry)` from the toolkit blob.
     * @return the projected snapshot.
     */
    private fun project(
        config: WindowConfig,
        states: Map<String, String?>,
        geometry: Map<String, Map<String, ToolkitPaneGeometry>>,
    ): State {
        val visibleTabs = config.tabs.filter { !it.isHidden }

        val activeTabId = config.activeTabId
            ?.takeIf { id -> visibleTabs.any { it.id == id } }
            ?: visibleTabs.firstOrNull()?.id

        val tabs = visibleTabs.map { tab ->
            OverviewTab(
                id = tab.id,
                title = tab.title,
                isActive = tab.id == activeTabId,
                aggregateState = aggregateState(tab, states),
                panes = projectPanes(tab, states, geometry[tab.id].orEmpty()),
            )
        }

        return State(tabs = tabs, activeTabId = activeTabId)
    }

    /**
     * Project a single tab's panes into render-ready [OverviewPane]s, applying
     * the toolkit geometry, focus fallback, and docked-pane filtering.
     *
     * @param tab         the tab whose panes to project.
     * @param states      session-state map for status dots.
     * @param tabGeometry `paneId -> geometry` for this tab from the toolkit blob.
     * @return the tab's panes, sorted bottom-to-top.
     */
    private fun projectPanes(
        tab: TabConfig,
        states: Map<String, String?>,
        tabGeometry: Map<String, ToolkitPaneGeometry>,
    ): List<OverviewPane> {
        // Drop docked panes (they leave the desktop canvas on web).
        val visiblePanes = tab.panes.filterNot { tabGeometry[it.leaf.id]?.isMinimized == true }

        // The focused pane gets the accent outline. A freshly-activated tab may
        // carry a null/stale focusedPaneId until the server sets one, so mirror
        // the desktop's rule (TabConfig.focusedPaneId docs): when it's null or
        // unknown, default focus to the first pane — otherwise switching to a
        // single-pane tab would momentarily show no active outline.
        val focusedPaneId = tab.focusedPaneId
            ?.takeIf { id -> visiblePanes.any { it.leaf.id == id } }
            ?: visiblePanes.firstOrNull()?.leaf?.id

        return visiblePanes
            .map { pane ->
                // Prefer the toolkit's authoritative geometry; fall back to the
                // config's placeholder fields only when the blob lacks an entry.
                val geom = tabGeometry[pane.leaf.id]
                OverviewPane(
                    leaf = pane.leaf,
                    x = geom?.xPct ?: pane.x,
                    y = geom?.yPct ?: pane.y,
                    width = geom?.widthPct ?: pane.width,
                    height = geom?.heightPct ?: pane.height,
                    z = geom?.zIndex?.toLong() ?: pane.z,
                    maximized = geom?.isMaximized ?: pane.maximized,
                    isFocused = pane.leaf.id == focusedPaneId,
                    sessionState = states[pane.leaf.sessionId],
                )
            }
            .sortedBy { it.z }
    }

    /**
     * Compute a tab's aggregate status dot. `"waiting"` wins over `"working"`,
     * which wins over idle — matching `updateStateIndicators()` on web and the
     * sidebar's `appendTab()` aggregation on Android.
     *
     * @param tab    the tab whose panes are inspected.
     * @param states map of session id → state string.
     * @return `"waiting"`, `"working"`, or `null` (idle).
     */
    private fun aggregateState(tab: TabConfig, states: Map<String, String?>): String? {
        var result: String? = null
        for (pane in tab.panes) {
            when (states[pane.leaf.sessionId]) {
                "waiting" -> return "waiting"
                "working" -> if (result != "working") result = "working"
            }
        }
        return result
    }
}
