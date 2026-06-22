/**
 * Read-only file-browser miniature for the overview screen.
 *
 * Shows a compact, non-interactive replica of the file browser's first screen
 * (the root directory listing reached from the session list): folders first,
 * then files, each as a single dim row. It reuses the shared
 * [se.soderbjorn.termtastic.client.viewmodel.FileBrowserBackingViewModel] so the
 * exact same listing logic backs the miniature, the full-screen
 * [FileBrowserListScreen], and (later) iOS.
 *
 * Non-interactive by design: the overview's whole-pane tap overlay drills into
 * the full [FileBrowserListScreen], so individual rows here do nothing.
 *
 * @see OverviewContent
 * @see FileBrowserListScreen
 * @see se.soderbjorn.termtastic.client.viewmodel.FileBrowserBackingViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.viewmodel.FileBrowserBackingViewModel

/** Max rows the miniature lists before it simply clips to the pane bounds. */
private const val MINI_FILE_ROWS = 14

/**
 * Compact file-browser thumbnail for the pane identified by [paneId].
 *
 * @param paneId   the file-browser leaf's pane id.
 * @param modifier layout modifier from the enclosing mini-pane.
 */
@Composable
fun MiniFileBrowserPane(
    paneId: String,
    modifier: Modifier = Modifier,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        Column(modifier.background(SidebarSurface)) {}
        return
    }

    val vm = remember(paneId, windowSocket) { FileBrowserBackingViewModel(paneId, windowSocket) }
    LaunchedEffect(vm) { vm.run() }
    val state by vm.stateFlow.collectAsStateWithLifecycle()

    // Root listing, directories first then files, both alphabetical — a faithful
    // miniature of the file browser's initial screen.
    val rootEntries = remember(state.dirListings) {
        state.dirListings[""].orEmpty()
            .sortedWith(compareByDescending<se.soderbjorn.termtastic.FileBrowserEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    Column(
        modifier = modifier
            .background(SidebarSurface)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = "Files",
            color = SidebarTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(2.dp))
        if (rootEntries.isEmpty()) {
            Text("…", color = SidebarTextSecondary, fontSize = 10.sp)
            return@Column
        }
        for (entry in rootEntries.take(MINI_FILE_ROWS)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (entry.isDir) "▸" else "·",
                    color = if (entry.isDir) SidebarAccent else SidebarTextSecondary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = entry.name,
                    color = SidebarTextPrimary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
