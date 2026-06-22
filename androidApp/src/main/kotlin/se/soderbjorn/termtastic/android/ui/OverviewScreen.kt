/**
 * Overview mode content for the Termtastic Android app.
 *
 * Renders a miniaturised, read-only replica of the web/Electron tabs-and-panes
 * experience (issue #42): a "scaled exposé" of the active tab's pane layout in
 * the content area, with a horizontally-scrollable strip of tab chips beneath
 * it. Selecting a tab activates it server-side (so the choice syncs across all
 * connected clients); tapping any pane drills into that pane's existing
 * full-screen route, from which the system back gesture returns here.
 *
 * The content area is a faithful spatial replica rather than a phone-native
 * reflow: panes keep their server-owned fractional `(x, y, width, height)`
 * geometry, mapped directly onto the full available content area (no aspect
 * letterboxing) so panes get the maximum space — relative positions and
 * proportions are preserved, only the overall canvas is stretched to fill.
 * Each pane hosts a live miniature: [MiniTerminalPane], [MiniFileBrowserPane],
 * or [MiniGitPane].
 *
 * All projection logic lives in the shared
 * [se.soderbjorn.termtastic.client.viewmodel.OverviewBackingViewModel] so iOS
 * can later render the same model; this file is the Compose front-end.
 *
 * Hosted by [TreeScreen] when its view-mode toggle is set to
 * [SessionsViewMode.OVERVIEW].
 *
 * @see TreeScreen
 * @see se.soderbjorn.termtastic.client.viewmodel.OverviewBackingViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.viewmodel.OverviewBackingViewModel

/**
 * The overview content: exposé canvas + bottom tab strip.
 *
 * @param onOpenTerminal    drill-in callback for a terminal pane (by session id).
 * @param onOpenFileBrowser drill-in callback for a file-browser pane (by pane id).
 * @param onOpenGit         drill-in callback for a git pane (by pane id).
 * @param modifier          layout modifier from [TreeScreen].
 */
@Composable
fun OverviewContent(
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = ConnectionHolder.client()
    val windowSocket = ConnectionHolder.windowSocket()
    if (client == null || windowSocket == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Disconnected", color = SidebarTextSecondary)
        }
        return
    }

    val vm = remember(windowSocket) {
        OverviewBackingViewModel(windowSocket, client.windowState.geometryByTab)
    }
    LaunchedEffect(vm) { vm.run() }
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // One registry for the whole overview: it owns the terminal miniatures'
    // sockets/emulators so they survive tab switches and recompositions. `scope`
    // (rememberCoroutineScope) and the registry both outlive the per-pane
    // composables but are torn down when the overview leaves composition.
    val miniTerminals = remember(client) { MiniTerminalRegistry(client, scope) }
    DisposableEffect(miniTerminals) {
        onDispose { miniTerminals.close() }
    }

    val tabs = state.tabs
    val activeIndex = tabs.indexOfFirst { it.isActive }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = activeIndex, pageCount = { tabs.size })

    // Server → pager: when the active tab changes elsewhere (chip tap, another
    // client), animate the pager to it. No-op if the pager is already there
    // (e.g. right after a user swipe), so the two never fight.
    LaunchedEffect(activeIndex) {
        if (activeIndex in tabs.indices && activeIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(activeIndex)
        }
    }
    // Pager → server: when the user settles on a new page, make it active.
    // rememberUpdatedState keeps the latest tabs/active without restarting the
    // collector on every state emit.
    val latestTabs = rememberUpdatedState(tabs)
    val latestActive = rememberUpdatedState(activeIndex)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val t = latestTabs.value
            if (page in t.indices && page != latestActive.value) {
                vm.setActiveTab(t[page].id)
            }
        }
    }

    CompositionLocalProvider(LocalMiniTerminalRegistry provides miniTerminals) {
        Column(modifier) {
            OverviewTabStrip(
                tabs = tabs,
                activeIndex = activeIndex,
                onSelect = { id -> scope.launch { vm.setActiveTab(id) } },
            )
            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No tabs", color = SidebarTextSecondary)
                }
            } else {
                // HorizontalPager gives the follow-finger transition and
                // snap/cancel between tabs for free; each page renders that
                // tab's exposé.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { page ->
                    ExposeCanvas(
                        panes = tabs[page].panes,
                        onOpenTerminal = onOpenTerminal,
                        onOpenFileBrowser = onOpenFileBrowser,
                        onOpenGit = onOpenGit,
                    )
                }
            }
        }
    }
}

/**
 * The exposé canvas: lays out [panes] by their fractional geometry mapped
 * directly onto the full available content area. Pane fractions are multiplied
 * by the measured width/height, so the panes always fill the space (no aspect
 * letterboxing) while keeping their relative positions and proportions.
 *
 * @param panes             the active tab's panes, pre-sorted bottom-to-top.
 * @param onOpenTerminal    drill-in callback for a terminal pane.
 * @param onOpenFileBrowser drill-in callback for a file-browser pane.
 * @param onOpenGit         drill-in callback for a git pane.
 */
@Composable
private fun ExposeCanvas(
    panes: List<OverviewBackingViewModel.OverviewPane>,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
) {
    if (panes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No windows in this tab", color = SidebarTextSecondary, fontSize = 13.sp)
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        val canvasW = maxWidth
        val canvasH = maxHeight
        for (pane in panes) {
            val x = if (pane.maximized) 0f else pane.x.toFloat()
            val y = if (pane.maximized) 0f else pane.y.toFloat()
            val w = if (pane.maximized) 1f else pane.width.toFloat()
            val h = if (pane.maximized) 1f else pane.height.toFloat()
            Box(
                modifier = Modifier
                    .offset(x = canvasW * x, y = canvasH * y)
                    .size(width = canvasW * w, height = canvasH * h)
                    .padding(3.dp),
            ) {
                MiniPane(
                    pane = pane,
                    onOpenTerminal = onOpenTerminal,
                    onOpenFileBrowser = onOpenFileBrowser,
                    onOpenGit = onOpenGit,
                )
            }
        }
    }
}

/**
 * A single miniature pane: a themed, rounded card with a tiny title bar, the
 * type-specific live miniature, and a full-size transparent tap overlay that
 * drills into the pane's full-screen route. The focused pane gets the accent
 * outline, matching the web's focused-pane treatment.
 *
 * @param pane              the projected pane (geometry, focus, state, leaf).
 * @param onOpenTerminal    drill-in callback for a terminal pane.
 * @param onOpenFileBrowser drill-in callback for a file-browser pane.
 * @param onOpenGit         drill-in callback for a git pane.
 */
@Composable
private fun MiniPane(
    pane: OverviewBackingViewModel.OverviewPane,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
) {
    val focused = pane.isFocused
    val borderColor = if (focused) SidebarAccent else SidebarTextSecondary.copy(alpha = 0.35f)
    val borderWidth = if (focused) 2.dp else 1.dp
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .background(SidebarSurface),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot first; it hides itself when idle (issue #43), so an
                // idle thumbnail leads with just the pane-type icon + title.
                StatusDot(state = pane.sessionState, boxDp = 12)
                // Miniature pane-type icon to the RIGHT of the status dot
                // (issue #43), sized to match the thumbnail's compact title bar.
                val kind = when (pane.leaf.content) {
                    is FileBrowserContent -> LeafKind.FILE_BROWSER
                    is GitContent -> LeafKind.GIT
                    else -> LeafKind.TERMINAL
                }
                PaneIcon(kind = kind, floating = false, sizeDp = 12)
                Text(
                    text = pane.leaf.title,
                    color = if (focused) SidebarTextBright else SidebarTextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                when (pane.leaf.content) {
                    is FileBrowserContent -> MiniFileBrowserPane(pane.leaf.id, Modifier.fillMaxSize())
                    is GitContent -> MiniGitPane(pane.leaf.id, Modifier.fillMaxSize())
                    // TerminalContent or null (legacy terminal leaf).
                    else -> MiniTerminalPane(pane.leaf.sessionId, Modifier.fillMaxSize())
                }
            }
        }

        // Transparent whole-pane tap target on top of the (non-interactive)
        // miniature, so a tap anywhere drills into the full-screen view.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable {
                    when (pane.leaf.content) {
                        is FileBrowserContent -> onOpenFileBrowser(pane.leaf.id)
                        is GitContent -> onOpenGit(pane.leaf.id)
                        else -> onOpenTerminal(pane.leaf.sessionId)
                    }
                },
        )
    }
}

/**
 * The top tab strip: a single horizontally-scrollable row of [FilterChip]s, the
 * active tab outlined in the accent colour (web parity) with its aggregate
 * status dot as the leading icon. Auto-scrolls to keep the active tab in view
 * whenever it changes (e.g. via a content swipe or another client).
 *
 * @param tabs        the tab summaries to render.
 * @param activeIndex index of the active tab in [tabs], or -1 if none.
 * @param onSelect    invoked with a tab id when the user taps a chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewTabStrip(
    tabs: List<OverviewBackingViewModel.OverviewTab>,
    activeIndex: Int,
    onSelect: (String) -> Unit,
) {
    if (tabs.isEmpty()) return

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            FilterChip(
                selected = tab.isActive,
                onClick = { onSelect(tab.id) },
                label = {
                    Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = { StatusDot(state = tab.aggregateState, boxDp = 12) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SidebarBackground,
                    labelColor = SidebarTextSecondary,
                    selectedContainerColor = SidebarAccent.copy(alpha = 0.18f),
                    selectedLabelColor = SidebarAccent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = tab.isActive,
                    borderColor = SidebarTextSecondary.copy(alpha = 0.4f),
                    selectedBorderColor = SidebarAccent,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 2.dp,
                ),
            )
        }
    }
}
