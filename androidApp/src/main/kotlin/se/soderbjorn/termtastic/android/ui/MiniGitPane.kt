/**
 * Read-only git miniature for the overview screen.
 *
 * Shows a compact, non-interactive replica of the git pane's first screen (the
 * changed-files list reached from the session list): each changed file as a
 * single row with a coloured status badge. It reuses the shared
 * [se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel] so the
 * same status logic backs the miniature, the full-screen [GitListScreen], and
 * (later) iOS.
 *
 * Non-interactive by design: the overview's whole-pane tap overlay drills into
 * the full [GitListScreen], so individual rows here do nothing.
 *
 * @see OverviewContent
 * @see GitListScreen
 * @see se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.termtastic.GitFileStatus
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel

/** Max changed-file rows the miniature lists before it clips to the pane bounds. */
private const val MINI_GIT_ROWS = 14

/**
 * Compact git changed-files thumbnail for the pane identified by [paneId].
 *
 * @param paneId   the git leaf's pane id.
 * @param modifier layout modifier from the enclosing mini-pane.
 */
@Composable
fun MiniGitPane(
    paneId: String,
    modifier: Modifier = Modifier,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        Column(modifier.background(SidebarSurface)) {}
        return
    }

    val vm = remember(paneId, windowSocket) { GitPaneBackingViewModel(paneId, windowSocket) }
    LaunchedEffect(vm) { vm.run() }
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val entries = state.entries.orEmpty()

    Column(
        modifier = modifier
            .background(SidebarSurface)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = "Changes",
            color = SidebarTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (entries.isEmpty()) {
            Text(
                text = if (state.isLoading) "…" else "No changes",
                color = SidebarTextSecondary,
                fontSize = 10.sp,
            )
            return@Column
        }
        for (entry in entries.take(MINI_GIT_ROWS)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GitStatusBadge(entry.status)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = entry.filePath.substringAfterLast('/'),
                    color = SidebarTextPrimary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A small single-letter badge encoding a file's [GitFileStatus] in its
 * conventional colour (added = green, modified = amber, deleted = red,
 * renamed = blue, untracked = grey).
 *
 * @param status the file's git status.
 */
@Composable
private fun GitStatusBadge(status: GitFileStatus) {
    val (letter, color) = when (status) {
        GitFileStatus.Added -> "A" to Color(0xFF65DA82)
        GitFileStatus.Modified -> "M" to Color(0xFFF4B869)
        GitFileStatus.Deleted -> "D" to Color(0xFFE5534B)
        GitFileStatus.Renamed -> "R" to Color(0xFF5AA9F5)
        GitFileStatus.Untracked -> "?" to Color(0xFF8E8E93)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
