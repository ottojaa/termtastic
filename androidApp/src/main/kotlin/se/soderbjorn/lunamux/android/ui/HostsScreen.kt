/**
 * Hosts/servers list screen for the Lunamux Android app.
 *
 * This is the app's landing screen. It displays the user's saved server
 * entries from the shared [se.soderbjorn.lunamux.client.storage.LocalRepository]
 * (via [se.soderbjorn.lunamux.android.data.AppLocalRepository]) and provides
 * add/edit/delete dialogs. Tapping a host initiates a WebSocket connection via
 * [se.soderbjorn.lunamux.android.net.ConnectionHolder] and, on success,
 * navigates to the [TreeScreen] overview.
 *
 * The screen is also the QR pairing entry point: the top-bar scanner (and the
 * `lunamux://pair` deep link relayed through
 * [se.soderbjorn.lunamux.android.PendingPairingUri]) parses a
 * [se.soderbjorn.lunamux.PairingPayload], saves or updates the host entry,
 * and connects immediately — scan → connected, with no approval dialog.
 *
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 * @see se.soderbjorn.lunamux.PairingPayload
 * @see TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.PairingPayload
import se.soderbjorn.lunamux.SERVER_TLS_PORT
import se.soderbjorn.lunamux.android.PendingPairingUri
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.client.CandidateConnector
import se.soderbjorn.lunamux.client.HostEntry
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.android.net.ServerUnreachableException
import se.soderbjorn.lunamux.android.net.NewsUpdatesController
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.demo.DEMO_HOST

/**
 * Sentinel id used in the `connectingId` state for the built-in demo row —
 * it is not a persisted [HostEntry], so it needs its own marker to drive
 * the row's progress spinner and to disable the rest of the list while the
 * (instant) demo connection is being set up.
 */
private const val DEMO_ROW_ID = "builtin-demo"

/**
 * Sealed type representing the target of the host edit dialog.
 *
 * [Add] opens a blank form; [Edit] opens a pre-filled form for an existing entry.
 */
private sealed interface EditTarget {
    /** Indicates the dialog should create a new host entry. */
    data object Add : EditTarget
    /**
     * Indicates the dialog should edit an existing host entry.
     *
     * @property entry the host entry to edit.
     */
    data class Edit(val entry: HostEntry) : EditTarget
}

/**
 * Outcome of scanning a pairing QR for a server already in the host list —
 * what [RePairDialog] reports.
 *
 * @property entry the entry as saved, with the scanned addresses merged in.
 * @property added how many addresses the scan actually contributed; 0 when the
 *   code carried nothing the entry did not already know.
 */
private data class RePairResult(val entry: HostEntry, val added: Int)

/**
 * Every address this entry can be reached at, in the order the connect walk
 * would try them: the preferred endpoint first, then the stored candidates.
 *
 * Backs both the long-press picker's list and the check for whether there is
 * anything to pick between — a one-address host has no choice to offer, so it
 * gets no long-press menu.
 *
 * @return the ordered, deduplicated `host[:port]` strings.
 */
private fun HostEntry.allAddresses(): List<String> {
    val preferred = HostPort(host, port).toCandidateString()
    return listOf(preferred) + (candidates - preferred)
}

/**
 * Landing screen showing the user's saved server hosts.
 *
 * Provides add, edit, and delete functionality for host entries. Tapping a
 * host initiates a WebSocket connection to the Lunamux server; on success
 * the [onConnected] callback navigates to the tree overview.
 *
 * Shows a "Waiting for approval..." label when the server has a pending
 * device-approval dialog for this client.
 *
 * @param applicationContext Android application context, forwarded to the
 *   [se.soderbjorn.lunamux.android.net.NewsUpdatesController].
 * @param onConnected callback invoked after a successful connection to a host.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    applicationContext: Context,
    onConnected: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val repository = remember { AppLocalRepository.instance }
    val scope = rememberCoroutineScope()

    // Null while local_state.json is still hydrating (render nothing rather than
    // flashing the empty state), then the persisted host list.
    val localState by repository.state.collectAsState()
    val hosts = localState?.hosts
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    // Set when a scan matched a host we already had; drives RePairDialog.
    var rePairResult by remember { mutableStateOf<RePairResult?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntry?>(null) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    // Triggered when ConnectionHolder.connect throws a pin-mismatch — the
    // server's cert no longer matches what we pinned. Re-pair clears the
    // stored pin and runs first-connect again; Forget removes the host.
    var pinMismatchEntry by remember { mutableStateOf<HostEntry?>(null) }
    val pendingApproval by (ConnectionHolder.pendingApproval
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // The address the connect walk is currently trying, surfaced on the row so
    // a 12s-per-dead-candidate walk reads as progress rather than a hang.
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    // Set when a long-press asks which address to use; only offered for entries
    // that actually have a choice.
    var addressPickerTarget by remember { mutableStateOf<HostEntry?>(null) }

    // Shared news/update checker — drives the toolbar bell's visibility (shown
    // only when there is news or an available update).
    val newsUpdatesVm = remember { NewsUpdatesController.ensureStarted(applicationContext) }
    val newsUpdatesState by newsUpdatesVm.stateFlow.collectAsState()

    // Connect to a saved host entry: walks its candidate endpoints in order
    // (a paired entry carries every address the server advertised) and, on
    // success, persists the winner in one write — preferred host/port
    // promoted, TOFU pin captured if this was a pinless first connect, spent
    // pairing token cleared. Failures produce connectivity-aware messages
    // instead of a generic timeout.
    // Connect using an explicit address instead of the usual walk — the
    // long-press picker's path. `null` means "walk the entry's candidates in
    // order", which is what tapping the row does.
    val connectToEntryUsing: (HostEntry, String?) -> Unit = { entry, only ->
        connectingId = entry.id
        connectingAddress = null
        scope.launch {
            runCatching {
                val token = repository.getOrCreateAuthToken()
                val preferred = HostPort(entry.host, entry.port).toCandidateString()
                val connection = ConnectionHolder.connectMulti(
                    candidates = only?.let { listOf(it) }
                        ?: (listOf(preferred) + (entry.candidates - preferred)),
                    defaultPort = entry.port,
                    authToken = token,
                    pinnedFingerprintHex = entry.pinnedFingerprintHex,
                    pairingToken = entry.pairingToken,
                    // Name what we're waiting on: a dead candidate costs 12s of
                    // otherwise mute spinner, which reads as a hang.
                    onAttempt = { connectingAddress = it },
                )
                val pin = entry.pinnedFingerprintHex
                    ?: connection.client.observedFingerprint.value
                val updated = entry.copy(
                    host = connection.endpoint.host,
                    port = connection.endpoint.port,
                    pinnedFingerprintHex = pin,
                    pairingToken = null,
                )
                if (updated != entry) repository.updateHost(updated)
            }.onSuccess {
                connectingId = null
                connectingAddress = null
                onConnected()
            }.onFailure { e ->
                connectingId = null
                connectingAddress = null
                if (ConnectionHolder.isPinMismatch(e)) {
                    pinMismatchEntry = entry
                } else {
                    val msg = connectFailureMessage(e)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = msg,
                            actionLabel = "Dismiss",
                        )
                    }
                }
            }
        }
    }

    /** Tapping a host: walk its addresses in order, asking the user nothing. */
    val connectToEntry: (HostEntry) -> Unit = { entry -> connectToEntryUsing(entry, null) }

    // Handle a scanned QR / deep-linked pairing URI: parse, dedupe against
    // existing entries (same server = same cert fingerprint or overlapping
    // endpoints — the re-pair path also refreshes a rotated cert's pin), save,
    // and connect straight away.
    val handlePairingUri: (String) -> Unit = { uri ->
        val payload = PairingPayload.parse(uri)
        if (payload == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "That doesn't look like a Lunamux pairing code",
                    actionLabel = "Dismiss",
                )
            }
        } else {
            scope.launch {
                val candidateStrings = payload.candidates.map { it.toCandidateString() }
                val preferred = payload.candidates.first()
                // Identity is the TLS cert and nothing else. An address is not
                // a machine: 192.168.1.5 is whoever's Wi-Fi you are on, so
                // matching on endpoint overlap would merge a colleague's Mac
                // into your entry and repin it. The cert can't collide, is
                // generated once with a 10-year life (CertStore), and follows
                // the machine between networks — which is exactly the case
                // re-pairing exists for. A manually-added entry matches too
                // once it has captured its TOFU pin, since that is the same
                // cert. The cost is that a manual entry that has never
                // connected has no pin and forks a duplicate; that entry has
                // no history worth keeping anyway.
                val existing = repository.ensureLoaded().hosts.firstOrNull { host ->
                    host.pinnedFingerprintHex == payload.fingerprintHex
                }
                if (existing != null) {
                    // Augment, don't replace: re-pairing at home must not
                    // cost the entry its work addresses. See mergeCandidates.
                    val merged = HostEntry.mergeCandidates(candidateStrings, existing.candidates)
                    // Count what actually landed, not what the QR offered: the
                    // merge caps its result, so some of the fresh addresses may
                    // not have made it in.
                    val added = merged.count { it !in existing.candidates }
                    val updated = existing.copy(
                        host = preferred.host,
                        port = preferred.port,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        candidates = merged,
                        pairingToken = payload.token,
                    )
                    repository.updateHost(updated)
                    // Deliberately no auto-connect here, unlike a first pairing.
                    // Re-pairing a known host changes nothing you can see — same
                    // label, same row — and connecting navigates away before any
                    // confirmation could be read, so the one moment the user
                    // could learn what happened would be spent. The dialog is
                    // that moment, and it offers the connect the scan implied.
                    rePairResult = RePairResult(updated, added)
                } else {
                    val entry = repository.addPairedHost(
                        label = payload.serverName ?: "Paired Mac",
                        host = preferred.host,
                        port = preferred.port,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        candidates = candidateStrings,
                        pairingToken = payload.token,
                    )
                    // A brand-new host keeps the scan → connected promise: the
                    // new row in the list is its own confirmation.
                    connectToEntry(entry)
                }
            }
        }
    }

    // QR scanner: the Google code scanner supplies its own UI and runs the
    // camera inside a Play Services process, so there is no CAMERA permission
    // and no runtime prompt to handle here — startScan() goes straight to the
    // viewfinder. It needs Play Services, this APK's only such dependency;
    // where that is missing or the module can't be fetched, the failure
    // listener explains it and manual add-host still works.
    val scanner = remember {
        GmsBarcodeScanning.getClient(
            applicationContext,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val startScan: () -> Unit = {
        scanner.startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(handlePairingUri) }
            // Cancellation is just the user backing out; say nothing.
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                Log.w("HostsScreen", "code scanner unavailable", e)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Couldn't open the scanner. Add the Mac's address " +
                            "manually with +, or check Google Play services.",
                        actionLabel = "Dismiss",
                    )
                }
            }
    }

    // Pairing deep link (system camera / browser → lunamux://pair): the
    // activity posts it into PendingPairingUri; consume() clears the slot so
    // recompositions and config changes can't pair twice.
    val pendingPairing by PendingPairingUri.uri.collectAsState()
    LaunchedEffect(pendingPairing) {
        if (pendingPairing != null) {
            PendingPairingUri.consume()?.let(handlePairingUri)
        }
    }

    // Connect to the built-in demo "server": the magic demo host makes the
    // shared client run against its in-process simulation, so this never
    // touches the network and completes instantly. No auth, no TLS pin, no
    // saved host entry.
    val connectDemo: () -> Unit = {
        connectingId = DEMO_ROW_ID
        scope.launch {
            runCatching {
                ConnectionHolder.connect(
                    serverUrl = ServerUrl(host = DEMO_HOST, port = 0),
                    authToken = "demo",
                )
            }.onSuccess {
                connectingId = null
                onConnected()
            }.onFailure { e ->
                connectingId = null
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = e.message ?: "Demo failed to start",
                        actionLabel = "Dismiss",
                    )
                }
            }
        }
    }

    // Collapsing large title — expands to a tall "Hosts" header at rest and
    // shrinks to an inline bar as the list scrolls, mirroring the iOS hosts
    // screen. The behaviour is wired to the Scaffold via nestedScroll below.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // This screen renders before any server connection exists, so no
    // server-driven theme has been fetched yet — but the sidebar palette falls
    // back to the default theme, so we use its black background and themed text
    // here (rather than the Material 3 surface) to match the Sessions screen.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SidebarBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Hosts") },
                actions = {
                    NewsBellButton(
                        onClick = onOpenNews,
                        shouldPulse = newsUpdatesState.hasNews,
                        muted = !newsUpdatesState.hasContent,
                    )
                    IconButton(onClick = startScan) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = "Scan QR code",
                            tint = SidebarAccent,
                        )
                    }
                    IconButton(onClick = { editTarget = EditTarget.Add }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add host",
                            tint = SidebarAccent,
                        )
                    }
                    // Shared info menu → support forum, website, legal pages.
                    // Mirrored in the Sessions top bar so both primary screens
                    // expose the same links from the same place.
                    AboutMenu()
                },
                // Keep the bar the sidebar colour in both expanded and collapsed
                // states so the title region never flashes the default surface
                // tint as it scrolls — matching the Sessions screen's top bar.
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SidebarBackground,
                    scrolledContainerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = hosts
                when {
                    list == null -> Unit // first composition
                    list.isEmpty() -> EmptyState(
                        onScan = startScan,
                        onAdd = { editTarget = EditTarget.Add },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { entry ->
                            HostRow(
                                entry = entry,
                                connecting = connectingId == entry.id,
                                attemptingAddress = connectingAddress
                                    ?.takeIf { connectingId == entry.id },
                                pendingApproval = pendingApproval && connectingId == entry.id,
                                enabled = connectingId == null,
                                onConnect = { connectToEntry(entry) },
                                // No choice to offer for a one-address host, so
                                // no long-press either.
                                onPickAddress = { addressPickerTarget = entry }
                                    .takeIf { entry.allAddresses().size > 1 },
                                onEdit = { editTarget = EditTarget.Edit(entry) },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting = connectingId == DEMO_ROW_ID,
                enabled = connectingId == null,
                onConnect = connectDemo,
            )
        }
    }

    editTarget?.let { target ->
        val initial = (target as? EditTarget.Edit)?.entry
        HostEditDialog(
            initial = initial,
            onDismiss = { editTarget = null },
            onSave = { label, host, port, candidates ->
                scope.launch {
                    if (initial == null) {
                        // A typed host has no candidates to carry: the dialog
                        // only shows that section for entries that have some.
                        repository.addHost(label, host, port)
                    } else {
                        repository.updateHost(
                            initial.copy(
                                label = label,
                                host = host,
                                port = port,
                                candidates = candidates,
                            ),
                        )
                    }
                    editTarget = null
                }
            },
        )
    }

    addressPickerTarget?.let { entry ->
        AddressPickerDialog(
            entry = entry,
            onDismiss = { addressPickerTarget = null },
            onPick = { address ->
                addressPickerTarget = null
                connectToEntryUsing(entry, address)
            },
        )
    }

    rePairResult?.let { result ->
        RePairDialog(
            result = result,
            onDismiss = { rePairResult = null },
            onConnect = {
                rePairResult = null
                connectToEntry(result.entry)
            },
        )
    }

    deleteTarget?.let { entry ->
        DeleteHostDialog(
            entry = entry,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    deleteTarget = null
                }
            },
        )
    }

    pinMismatchEntry?.let { entry ->
        PinMismatchDialog(
            entry = entry,
            onDismiss = { pinMismatchEntry = null },
            onRepair = {
                scope.launch {
                    // Clear the stored pin so the next tap runs first-connect
                    // (capture mode + server ApprovalDialog) again.
                    repository.updateHost(entry.copy(pinnedFingerprintHex = null))
                    pinMismatchEntry = null
                }
            },
            onForget = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    pinMismatchEntry = null
                }
            },
        )
    }
}

/**
 * Placeholder UI shown when no hosts have been saved yet.
 *
 * Pairing by QR is the primary path (scan → connected, no typing, no
 * approval dialog); manual entry is demoted to a secondary action. The entry
 * point into the built-in demo lives in the bottom-pinned [DemoFooter], which
 * is shown in this state too, so first-time users can still explore the app
 * without owning a server.
 *
 * @param onScan callback invoked when the "Scan QR code" button is tapped.
 * @param onAdd callback invoked when the secondary "Add manually" action is tapped.
 */
@Composable
private fun EmptyState(
    onScan: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = SidebarTextSecondary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No hosts yet",
            style = MaterialTheme.typography.titleMedium,
            color = SidebarTextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "On your Mac, in Lunamux, go to \"Settings > Server & Security… > Devices\" " +
                "and tick \"Allow connections from other devices\" and then press " +
                "\"Pair via QR Code\" - and then scan the code here.",
            style = MaterialTheme.typography.bodyMedium,
            color = SidebarTextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onScan,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SidebarAccent,
                contentColor = SidebarBackground,
            ),
        ) {
            Text("Scan QR code")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onAdd,
            colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
        ) {
            Text("Add manually")
        }
    }
}

/**
 * Discreet, bottom-pinned entry into the built-in demo. Tapping it connects to
 * the in-process demo simulation (see the magic [DEMO_HOST] handling in the
 * shared client) — instant, offline, and stateless, so it needs no edit/delete
 * affordances.
 *
 * Rendered as a single muted row beneath a hairline divider, staying out of the
 * way of the user's own servers. External links (support forum, website, legal
 * pages) now live in the top bar's [AboutMenu] rather than here, keeping this
 * footer to the one demo affordance.
 *
 * @param connecting true while the demo connection is being set up.
 * @param enabled false while another host is connecting.
 * @param onConnect callback invoked when the footer is tapped.
 * @see AboutMenu
 */
@Composable
private fun DemoFooter(
    connecting: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = SidebarTextSecondary.copy(alpha = 0.2f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Live demo — the footer's sole affordance.
            Row(
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onConnect)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Try the live demo, no server needed"
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = SidebarTextSecondary,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SidebarTextSecondary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Try the live demo",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SidebarTextSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A single row in the hosts list showing the label, host:port, and an
 * overflow menu for edit/delete actions.
 *
 * When a connection attempt is in progress, the overflow menu is replaced
 * by a progress spinner and optional "Waiting for approval" text.
 *
 * @param entry the host entry to render.
 * @param connecting true while a connection attempt is in progress for this entry.
 * @param attemptingAddress the address currently being tried, or null when not
 *   connecting; names what the spinner is waiting on.
 * @param pendingApproval true when the server is showing a device-approval dialog.
 * @param enabled false to disable tap interactions (while another host is connecting).
 * @param onConnect callback invoked when the row is tapped to connect.
 * @param onPickAddress invoked on long-press for entries with more than one
 *   address; null for entries with nothing to choose between, which get no
 *   long-press at all rather than a menu of one.
 * @param onEdit callback invoked from the overflow menu to edit this entry.
 * @param onDelete callback invoked from the overflow menu to delete this entry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(
    entry: HostEntry,
    connecting: Boolean,
    attemptingAddress: String?,
    pendingApproval: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onPickAddress: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = onConnect,
                onLongClick = onPickAddress,
            )
            .semantics {
                role = Role.Button
                contentDescription = "${entry.label}, ${entry.host}:${entry.port}"
            }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalGlyph()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SidebarTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // While connecting, this line names the address actually being
            // tried rather than the stored preferred one. The walk can spend
            // 12s on each dead address, and a spinner over an unchanging
            // host:port makes that look like a hang instead of progress.
            Text(
                text = attemptingAddress?.let { "Trying $it…" } ?: "${entry.host}:${entry.port}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SidebarTextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (connecting) {
            if (pendingApproval) {
                Text(
                    "Waiting for approval\u2026",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SidebarTextSecondary,
                    ),
                )
                Spacer(Modifier.width(6.dp))
            }
            // Determinate, not a spinner: each attempt has a known budget
            // (DEFAULT_PER_CANDIDATE_TIMEOUT_MS), so the ring can show how much
            // of it is left rather than just asserting that something is
            // happening. It restarts on every new address, which is also the
            // signal that the walk moved on. Driven off `attemptingAddress`
            // rather than a timer, so it can't drift from the real deadline.
            val ring = remember { Animatable(0f) }
            LaunchedEffect(attemptingAddress) {
                if (attemptingAddress != null) {
                    ring.snapTo(0f)
                    ring.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS.toInt(),
                            easing = LinearEasing,
                        ),
                    )
                }
            }
            if (attemptingAddress == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(
                    progress = { ring.value },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Host options",
                        tint = SidebarTextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Long-press escape hatch: connect to [entry] using one specific address
 * rather than walking them.
 *
 * Tapping a host is the normal path and asks nothing — it tries the preferred
 * address, falls back through the rest, and promotes whatever answered. That
 * walk costs up to 12s per dead address, though, and the phone cannot know
 * which network it is on. When the user does know, this skips straight to the
 * right one. Deliberately not the default: it is an override for the case the
 * automatic path handles slowly, not a question worth asking on every connect.
 *
 * Only reachable for entries with more than one address, so it never offers a
 * choice of one.
 *
 * @param entry the host to connect to.
 * @param onDismiss invoked when the user backs out without picking.
 * @param onPick invoked with the chosen `host[:port]` string.
 */
@Composable
private fun AddressPickerDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Connect using") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entry.allAddresses().forEachIndexed { index, address ->
                    Text(
                        text = address,
                        color = SidebarTextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(address) }
                            .padding(vertical = 12.dp),
                    )
                    if (index == 0) {
                        Text(
                            "Last address that worked — what tapping the host tries first",
                            color = SidebarTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Reports what scanning a QR for an already-known server did.
 *
 * The scan matched an existing entry by TLS fingerprint, so nothing visible
 * changed — the row keeps its name and place. This is the only chance to tell
 * the user their new network was recorded, which is the whole point of
 * re-pairing somewhere else: the payoff is deferred to the next time they are
 * back on the old network, so without this the action looks like a no-op.
 *
 * Offers the connect that the scan implied, so skipping the auto-connect costs
 * nothing.
 *
 * @param result the merged entry and how many addresses it gained.
 * @param onDismiss invoked when the user closes without connecting.
 * @param onConnect invoked to connect to the merged entry.
 */
@Composable
private fun RePairDialog(
    result: RePairResult,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("${result.entry.label} updated") },
        text = {
            Text(
                when (result.added) {
                    0 ->
                        "This code carried no addresses that ${result.entry.label} " +
                            "didn't already know, so nothing changed."
                    1 ->
                        "1 new address was added. ${result.entry.label} can now be " +
                            "reached on this network as well as the ones it already knew."
                    else ->
                        "${result.added} new addresses were added. ${result.entry.label} " +
                            "can now be reached on this network as well as the ones it " +
                            "already knew."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConnect,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Done") }
        },
    )
}

/**
 * Dialog for adding or editing a host entry.
 *
 * Presents text fields for label, host, and port, plus — for an entry that has
 * them — the saved fallback addresses, each editable and removable. The Save
 * button is disabled until label, host, and port are all valid.
 *
 * @param initial the existing entry to edit, or null when adding a new one.
 * @param onDismiss callback to close the dialog without saving.
 * @param onSave callback with the validated label, host, port, and the edited
 *   candidate list (empty for a newly-added host).
 */
@Composable
private fun HostEditDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (label: String, host: String, port: Int, candidates: List<String>) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    // New hosts default to the port every Lunamux server listens on.
    var port by remember { mutableStateOf(initial?.port?.toString() ?: SERVER_TLS_PORT.toString()) }
    // Editable copy of the entry's saved fallback endpoints. Scanning a QR
    // merges new networks in (HostEntry.mergeCandidates), and this is where
    // they can be pruned again — a laptop that has visited several offices
    // accumulates addresses that are dead everywhere else, and each one costs
    // up to 12s of the sequential connect walk.
    var candidates by remember { mutableStateOf(initial?.candidates ?: emptyList()) }

    val parsedPort = port.toIntOrNull()
    val canSave = label.isNotBlank() && host.isNotBlank() && parsedPort != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text(if (initial == null) "Add host" else "Edit host") },
        text = {
            // Scrolls: with a paired host's saved addresses the content runs
            // past a phone's dialog height.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    supportingText = { Text("A name for this server, shown in the host list") },
                    singleLine = true,
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    supportingText = {
                        Text(
                            "Hostname or IP address of your Mac running Lunamux. " +
                                "Your phone must be on the same Wi-Fi network or VPN.",
                        )
                    },
                    singleLine = true,
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    supportingText = { Text("Servers listen on $SERVER_TLS_PORT by default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Other addresses",
                        style = MaterialTheme.typography.titleSmall,
                        color = SidebarTextPrimary,
                    )
                    Text(
                        "Tried in order when the address above doesn't answer. " +
                            "Scanning a QR code adds the addresses it carries; delete any " +
                            "that no longer exist, since each dead one slows down connecting.",
                        color = SidebarTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    candidates.forEachIndexed { index, candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = candidate,
                                onValueChange = { edited ->
                                    candidates = candidates.toMutableList().also { it[index] = edited }
                                },
                                label = { Text("Address ${index + 1}") },
                                singleLine = true,
                                colors = themedTextFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                candidates = candidates.filterIndexed { i, _ -> i != index }
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete address ${index + 1}",
                                    tint = SidebarTextSecondary,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (parsedPort != null && canSave) {
                        onSave(
                            label.trim(),
                            host.trim(),
                            parsedPort,
                            // Blank rows are how a user clears one by hand; treat
                            // that the same as pressing delete rather than saving
                            // an empty candidate the connect walk would choke on.
                            candidates.map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }
                },
                enabled = canSave,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Dialog shown when the server's TLS leaf certificate no longer matches the
 * fingerprint pinned during the first successful connect. This is either a
 * benign event (server was reinstalled / regenerated its cert) or evidence
 * of an active MITM attempt.
 *
 * Three exits:
 *  - **Re-pair**: clears [HostEntry.pinnedFingerprintHex] so the next tap
 *    runs first-connect (TOFU capture + server `ApprovalDialog`) again.
 *  - **Forget**: deletes the host entry.
 *  - **Cancel**: dismisses without changing anything; user can retry later.
 *
 * @param entry the host entry whose pin mismatched.
 * @param onDismiss invoked when the user picks Cancel or taps outside.
 * @param onRepair invoked when the user picks Re-pair.
 * @param onForget invoked when the user picks Forget.
 */
@Composable
private fun PinMismatchDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onRepair: () -> Unit,
    onForget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Server certificate changed") },
        text = {
            Text(
                "The server at \"${entry.label}\" (${entry.host}:${entry.port}) is " +
                    "presenting a different certificate than the one you paired with. " +
                    "This could mean the server was reinstalled, or someone is trying " +
                    "to intercept your connection.\n\n" +
                    "Re-pair if you trust the new certificate; Forget to remove the host.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onRepair,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Re-pair") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
                ) { Text("Cancel") }
            }
        },
    )
}

/**
 * Confirmation dialog shown before deleting a host entry.
 *
 * @param entry the host entry about to be deleted.
 * @param onDismiss callback to close the dialog without deleting.
 * @param onConfirm callback to proceed with deletion.
 */
@Composable
private fun DeleteHostDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Delete host?") },
        text = { Text("\"${entry.label}\" will be removed from this device.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Build a connection-failure message that explains the likely cause instead
 * of echoing a transport error.
 *
 * Deliberately does not inspect the phone's transport. Mobile data used to
 * get its own "you can't reach a LAN host from here" message, but that is not
 * true — a VPN reaches the Mac over cellular perfectly well — and a message
 * that blames the connection reads as though the app refused to try. It never
 * refused: the connect is always attempted, and this only ever runs once one
 * has already failed. The same reachability advice covers every transport.
 *
 * Called from the hosts screen's connect failure path (non-pin-mismatch).
 *
 * @param e the connect failure, used verbatim when the server was reached.
 * @return a user-facing message for the snackbar.
 */
private fun connectFailureMessage(e: Throwable): String = when {
    // A device-auth rejection reaches the server but is turned away before the
    // first config (expired/foreign pairing token, allow-remote off, or a
    // revoked device). Its raw exception text is developer-facing ("…before
    // sending a config… check the server's log for…"), so translate the known
    // case into something the user can act on.
    isDeviceAuthRejection(e) ->
        "The Mac declined this connection. On your Mac, in Lunamux, go to " +
            "\"Settings > Server & Security… > Devices\" to re-pair or approve this device."
    // Reachability advice only when we genuinely couldn't reach the server. A
    // phase-2 failure (reached, but the server rejected the device / never
    // sent a config) carries a descriptive message that we must not mask.
    e !is ServerUnreachableException -> e.message ?: "Connection failed"
    else ->
        "Couldn't reach the Mac. Make sure this phone is on the same Wi-Fi " +
            "network as your computer, or on a VPN that can reach it."
}

/**
 * Whether [e] is the post-handshake device-auth rejection thrown by
 * [se.soderbjorn.termtastic.client.WindowSocket.awaitInitialConfig] when the
 * server accepts the socket but closes it before the first config. Matched on
 * the exception's distinctive phrase rather than its type so [connectMulti]'s
 * phase-2 failure keeps a friendly, actionable message.
 *
 * @param e the connect failure surfaced to [connectFailureMessage].
 * @return true when the failure is a device-auth rejection.
 */
private fun isDeviceAuthRejection(e: Throwable): Boolean =
    e.message?.contains("before sending a config") == true

/** Small 16dp terminal-pane glyph, inlined from TreeScreen's PaneIcon (non-floating variant). */
@Composable
private fun TerminalGlyph() {
    val tint = SidebarTextSecondary.copy(alpha = 0.7f)
    Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = "Terminal" }) {
        val w = size.width
        val px = w / 16f
        val stroke = Stroke(
            width = 1.3f * px,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(1f * px, 2f * px),
            size = Size(14f * px, 12f * px),
            cornerRadius = CornerRadius(1.5f * px, 1.5f * px),
            style = stroke,
        )
        val chevron = Path().apply {
            moveTo(4f * px, 7f * px)
            lineTo(6f * px, 5f * px)
            lineTo(4f * px, 3f * px)
        }
        drawPath(chevron, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(7f * px, 7f * px),
            end = Offset(11f * px, 7f * px),
            strokeWidth = 1.2f * px,
            cap = StrokeCap.Round,
        )
    }
}
