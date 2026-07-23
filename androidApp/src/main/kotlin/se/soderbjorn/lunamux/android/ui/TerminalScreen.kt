/**
 * Terminal emulator screen for the Lunamux Android app.
 *
 * Hosts a full xterm-compatible terminal session rendered by a Termux
 * [com.termux.view.TerminalView]. Composes the four supporting helpers
 * extracted from this file:
 *  - [TerminalEmulatorHolder] — the externally-fed [TerminalSession]
 *    subclass + companion [TerminalEmulator] factory.
 *  - [TerminalThemeResolver] — palette resolution + emulator colour
 *    application.
 *  - [ImeHelperToolbar] — sticky modifier toolbar above the soft keyboard.
 *  - [SwipeInputBar] — gesture-typing input.
 *
 * Navigated to from [TreeScreen] when the user taps a terminal leaf
 * pane.
 *
 * @see TreeScreen
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 */
package se.soderbjorn.lunamux.android.ui

import se.soderbjorn.lunula.core.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.lunamux.WindowConfig
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.client.PtyEvent
import se.soderbjorn.lunamux.client.PtyPresentation
import kotlin.math.roundToInt

/** Theme accent colour for the terminal screen top bar. */
private val HeaderAccent: Color
    @Composable @ReadOnlyComposable
    get() = SidebarAccent

/**
 * Quiet threshold for the on-resume PTY refresh: anything that streamed
 * output within the last few seconds is clearly alive and is left alone;
 * everything else (idle or dead) gets a reconnect + reset-prefixed replay.
 */
private const val PTY_RESUME_STALE_MS = 3_000L

/**
 * Smallest legible font (px) for the passive mirror. When mirroring a grid wider
 * than the phone natively fits, the font shrinks proportionally down to this
 * floor; below it the right edge is clipped rather than shrinking illegibly.
 */
private const val PASSIVE_FONT_FLOOR_PX = 12f

/** The phone's normal (driving) terminal font size in px. */
private const val DRIVING_FONT_PX = 30

/**
 * Whether [bytes] contains a full terminal reset (RIS, `ESC c`). Termux's
 * emulator resets its colour table to the built-in default scheme on RIS
 * (see `TerminalColors.reset()`), discarding the applied theme — default-
 * coloured text then paints in the stock palette against our themed view
 * background and becomes unreadable. The output collector watches for RIS
 * (whether from the [PtySocket] reconnect replay or a real `reset` run on
 * the server) and re-applies the theme right after.
 *
 * @param bytes one PTY output frame.
 * @return `true` when the frame contains `ESC c`.
 */
internal fun containsTerminalReset(bytes: ByteArray): Boolean {
    for (i in 0 until bytes.size - 1) {
        if (bytes[i] == 0x1b.toByte() && bytes[i + 1] == 'c'.code.toByte()) return true
    }
    return false
}

/**
 * Local terminal grid metrics — cols/rows of the TerminalView's emulator.
 * Cached in Compose state and refreshed whenever the grid size changes,
 * so the Reformat button can re-assert the view's natural size.
 */
private data class AndroidGridDims(
    val cols: Int,
    val rows: Int,
)

/**
 * Mutable bookkeeping for terminal scroll-pause, all touched only on the main
 * thread (the visibility poll, the output `view.post`, and the pill tap all
 * run there). Termux's [TerminalView.onScreenUpdated] force-snaps the view to
 * the bottom; we let it snap and then restore the user's offset from here so
 * scrolling up pauses auto-follow without editing the vendored view.
 *
 * @property lastOffset the most recent `topRow` while scrolled up (<= 0; 0 = at
 *   bottom). Preserved across a resume reset (`ESC c`) that wipes scrollback so
 *   the position can be re-applied once the replay settles.
 * @property pendingRestore the offset to scroll back to after a resume replay,
 *   or null when no restore is pending.
 * @property restoreJob debounce coroutine for [pendingRestore]; re-armed on
 *   every output chunk and fires once output goes quiet.
 */
private class ScrollPauseState {
    var lastOffset: Int = 0
    var pendingRestore: Int? = null
    var restoreJob: Job? = null
}

/**
 * Searches the [WindowConfig] pane tree for a leaf whose session ID
 * matches [sessionId] and returns its display title.
 */
private fun findLeafTitle(config: WindowConfig?, sessionId: String): String? {
    if (config == null) return null
    // Search every world's tabs (worlds are the source of truth for >=1.9
    // clients and the opened session may belong to any of them); fall back to
    // the legacy flat tabs when the config carries no worlds (pre-1.9 server).
    val tabs = config.worlds.flatMap { it.tabs }.ifEmpty { config.tabs }
    for (tab in tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.title }
    }
    return null
}

/**
 * A single-session terminal screen.
 *
 * @param sessionId the PTY session identifier to connect to on the server.
 * @param onBack callback invoked when the user navigates back to [TreeScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        onBack()
        return
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val emulatorDispatcher = remember {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    val windowConfig by client.windowState.config.collectAsStateWithLifecycle()
    val headerTitle = remember(windowConfig, sessionId) {
        findLeafTitle(windowConfig, sessionId) ?: sessionId
    }

    val sessionStates by client.windowState.states.collectAsStateWithLifecycle()
    val paneState = sessionStates[sessionId]

    // The grid this phone currently renders, mirrored onto the connect URL so the
    // server synthesizes the attach redraw at our width (no 80x24-seed reflow
    // flash). Updated by the TerminalView grid-size listener below.
    val gridFlow = remember(sessionId) { MutableStateFlow<Pair<Int, Int>?>(null) }
    val ptySocket = remember(sessionId) { client.openPtySocket(sessionId, gridFlow) }
    val ctrlSticky = remember { mutableStateOf(false) }
    val shiftSticky = remember { mutableStateOf(false) }
    var swipeInputActive by remember { mutableStateOf(false) }
    var swipeText by remember { mutableStateOf("") }
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    // [localGrid] = the phone's NATURAL grid (what the view renders at the user's
    // own font), measured by the grid-size listener while NOT mirroring. It is the
    // take-over target and the baseline for the passive font-fit.
    var localGrid by remember(sessionId) {
        mutableStateOf<AndroidGridDims?>(null)
    }

    // The phone renders the server's authoritative grid as a live mirror. When that
    // grid is wider than [localGrid] (another device — the laptop — is driving) the
    // phone is PASSIVE: it pins the emulator to the server grid ([passiveGridPin],
    // read by the holder's updateSize) and shrinks the font so the grid fits,
    // instead of reflowing wide output into the narrow phone grid (the old mangle).
    // Take-over (typing / tap / badge) forces the PTY to [localGrid] and the mirror
    // snaps back to the phone's own width.
    //
    // [serverGrid] is the server's last authoritative PTY size (Compose state so the
    // badge + font-fit react). [drivingTo] holds the grid we last forced to,
    // optimistically, so a keystroke burst doesn't each fire a forceResize before
    // the server's Size echo returns.
    var serverGrid by remember(sessionId) { mutableStateOf<Pair<Int, Int>?>(null) }
    val drivingTo = remember(sessionId) { AtomicReference<Pair<Int, Int>?>(null) }
    val passiveGridPin = remember(sessionId) { AtomicReference<Pair<Int, Int>?>(null) }

    // The user's chosen (driving) font size; pinch-zoom adjusts it. The actually
    // applied size shrinks below this while mirroring (see appliedFontSize).
    var userFontSize by remember(sessionId) { mutableStateOf(DRIVING_FONT_PX) }

    // Passive = the server grid is a different (wider) width than this phone's own.
    // Cols only: a rows-only difference doesn't change wrapping.
    val passive = localGrid?.let { lg -> serverGrid?.let { it.first != lg.cols } } ?: false

    // The font actually applied to the view: the user's size while driving; while
    // mirroring, shrunk so the (wider) server grid fits the phone, floored so it
    // stays legible (below the floor the right edge clips). Applied in the
    // AndroidView update block.
    val appliedFontSize = if (passive) {
        val lg = localGrid
        val sg = serverGrid
        if (lg != null && sg != null) {
            PtyPresentation.passiveFontSize(userFontSize.toFloat(), lg.cols, sg.first, PASSIVE_FONT_FLOOR_PX)
                .roundToInt()
        } else {
            userFontSize
        }
    } else {
        userFontSize
    }
    // Last font size pushed to the view — a plain holder (not Compose state) so the
    // guarded setTextSize in the update block can't feed back into recomposition.
    val appliedFontRef = remember(sessionId) { intArrayOf(-1) }

    // Take-over: force the shared PTY to this phone's natural grid. No-op when the
    // server already matches (ordinary typing while driving is free) or when a force
    // to that grid is already in flight. After the force, the server's resync Size
    // arrives, [passive] clears, and the font restores. Invoked by real input,
    // tap-to-focus and the take-over badge — the "intent, not presence" model.
    val ensureDriving: suspend () -> Unit = remember(sessionId) {
        {
            val local = localGrid
            if (local != null && local.cols > 0 && local.rows > 0) {
                val target = local.cols to local.rows
                if (serverGrid != target && drivingTo.get() != target) {
                    drivingTo.set(target)
                    runCatching { ptySocket.forceResize(target.first, target.second) }
                }
            }
        }
    }

    // Input policy for view-produced bytes. Real input takes over first, then sends;
    // but ambient reports (mouse wheel, focus in/out) the mirror emits from scrolling
    // or focus are dropped while passive — they must neither reach the shell nor
    // count as a take-over, or scrolling the mirror would steal the grid. Reads live
    // state (safe outside composition); classifier is the shared PtyPresentation.
    val handleInput: suspend (ByteArray) -> Unit = remember(sessionId) {
        { bytes ->
            val lg = localGrid
            val sg = serverGrid
            val passiveNow = lg != null && sg != null && sg.first != lg.cols
            if (passiveNow && PtyPresentation.isAmbientReport(bytes)) {
                // Ambient mirror report — drop it.
            } else {
                ensureDriving()
                runCatching { ptySocket.send(bytes) }
            }
        }
    }

    // Scroll-pause: whether the user has scrolled up off the bottom (drives the
    // floating "jump to bottom" pill) and whether fresh output arrived while
    // they were scrolled up (switches the pill to a "New output" hint).
    var scrolledUp by remember(sessionId) { mutableStateOf(false) }
    var hasNewOutput by remember(sessionId) { mutableStateOf(false) }
    val scrollPause = remember(sessionId) { ScrollPauseState() }

    val terminalPalette = rememberTerminalPalette(client, sessionId)
    val bgComposeColor = Color(terminalPalette.bg)

    val session = remember(sessionId) {
        createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = emulatorDispatcher,
            terminalViewRef = terminalViewRef,
            ptySocket = ptySocket,
            passiveGridPin = passiveGridPin,
            handleInput = handleInput,
        )
    }

    val emulator = remember(sessionId) { createSyncedEmulator(session) }

    // Single ordered event stream: output bytes, size changes and reconnect
    // resets are applied to the emulator in the order the server produced them,
    // so a resize never races the redraw bytes it triggers (the old split
    // output + ptySize flows could interleave and mangle the grid).
    LaunchedEffect(sessionId) {
        // A server Size may have been recorded in the conflated ptySize mirror
        // before this collector started; seed from it so the mode machine (passive
        // detection, badge) sees the current PTY width immediately.
        ptySocket.ptySize.value?.let { serverGrid = it }
        ptySocket.events.collect { ev ->
            // Passive = the server PTY is at another device's width. Raw bytes are
            // cursor-addressed for that width, so appending them into this phone's
            // narrow grid is the mangle — and the repeated wide repaints during
            // contention (you type on the laptop while the phone holds a different
            // width) stack up as the duplicated banners/input-lines. So once the
            // phone has its OWN clean frame we FREEZE while passive: hold the last
            // clean frame and drop output + reconnect resets until the phone is at
            // its own width again. BEFORE that first frame ([hasDrivenOwnWidth] is
            // false) we feed everything — including the tab-return replay that
            // carries the scrollback — so re-entering a terminal never loses history.
            when (ev) {
                is PtyEvent.Size -> {
                    val sz = ev.cols to ev.rows
                    serverGrid = sz
                    // Server drifted off the grid we forced to → another device
                    // reclaimed; drop the optimistic guard so the next real input
                    // re-takes-over to this phone's width.
                    val d = drivingTo.get()
                    if (d != null && d != sz) drivingTo.set(null)
                    // Mirror the server's grid. Pin the emulator to it while passive
                    // (so the view's own layout can't reflow it out from under the
                    // synthesized redraw); release the pin at our own width so
                    // rotation re-drives. Then size the emulator to the grid the
                    // redraw Bytes ordered right after this Size assume.
                    val passiveNow = localGrid?.let { it.cols != sz.first } ?: false
                    passiveGridPin.set(if (passiveNow) sz else null)
                    withContext(emulatorDispatcher) {
                        synchronized(emulator) {
                            // Width only — rows stay at the view's capacity so a taller
                            // server screen bottom-anchors (scrolling its earlier rows
                            // into scrollback) instead of clipping the prompt. See the
                            // pin note in TerminalEmulatorHolder.updateSize.
                            runCatching { emulator.resize(sz.first, emulator.mRows, 1, 1) }
                        }
                    }
                    return@collect
                }
                PtyEvent.Reset -> {
                    // Reconnect boundary: the server re-sends a fresh synthesized
                    // attach redraw (RIS-prefixed) as the next Bytes, which clears
                    // and repaints the emulator itself — so, unlike the old ring
                    // replay, we feed no local RIS here, and the theme re-applies on
                    // the Bytes path (containsTerminalReset detects the redraw's RIS).
                    // Stash the scroll offset so the user lands near where they were.
                    if (scrollPause.lastOffset < 0) {
                        scrollPause.pendingRestore = scrollPause.lastOffset
                    }
                    return@collect
                }
                // Live output (incl. the RIS-prefixed synthesized redraw): always fed
                // into the emulator below. No freeze — the phone mirrors the server's
                // coherent grid at whatever font-fit the mode machine applies.
                is PtyEvent.Bytes -> Unit
            }
            val chunk = ev.data
            withContext(emulatorDispatcher) {
                synchronized(emulator) {
                    emulator.append(chunk, chunk.size)
                }
            }
            val isReset = containsTerminalReset(chunk)
            // A terminal reset (the reconnect replay's prefix, or a real
            // `reset` on the server) reverts the emulator's colour table to
            // the stock scheme — re-apply the theme before repainting or
            // default-coloured text becomes unreadable on the themed
            // background until the screen is rebuilt.
            if (isReset) {
                terminalViewRef.value?.post {
                    terminalViewRef.value?.let { applyTerminalColors(it, emulator, terminalPalette) }
                }
                // A resume reset wipes scrollback while the user may have been
                // reading history. Stash their last offset so we can return
                // them once the replay settles (best-effort: the replayed
                // ring buffer is the same content, so the row offset lands
                // close to where they were).
                if (scrollPause.lastOffset < 0) {
                    scrollPause.pendingRestore = scrollPause.lastOffset
                }
            }
            terminalViewRef.value?.post {
                val view = terminalViewRef.value ?: return@post
                val before = view.topRow
                if (before < 0) {
                    // User has scrolled up — let onScreenUpdated snap to the
                    // bottom (it also clears the scroll counter), then shift
                    // the view back up by the number of newly-scrolled lines so
                    // the content the user is reading stays put. All in one
                    // post = one render frame, so there's no visible flicker.
                    val shift = synchronized(emulator) { emulator.scrollCounter }
                    view.onScreenUpdated()
                    val history = emulator.screen.activeTranscriptRows
                    val restored = (before - shift).coerceIn(-history, 0)
                    view.topRow = restored
                    view.invalidate()
                    scrollPause.lastOffset = restored
                    scrolledUp = restored < 0
                    if (restored < 0) hasNewOutput = true
                } else {
                    view.onScreenUpdated()
                }
            }
            // Debounce a resume-restore: re-armed on every chunk, it fires once
            // output goes quiet so we land after the whole replay has been fed.
            if (scrollPause.pendingRestore != null) {
                scrollPause.restoreJob?.cancel()
                scrollPause.restoreJob = scope.launch {
                    delay(300)
                    val target = scrollPause.pendingRestore ?: return@launch
                    val view = terminalViewRef.value ?: return@launch
                    view.post {
                        val history = emulator.screen.activeTranscriptRows
                        val restored = target.coerceIn(-history, 0)
                        view.topRow = restored
                        view.invalidate()
                        scrollPause.lastOffset = restored
                        scrolledUp = restored < 0
                        if (restored < 0) hasNewOutput = true
                    }
                    scrollPause.pendingRestore = null
                }
            }
        }
    }

    // Poll the view's scroll offset so the pill appears/disappears even when
    // the user scrolls a static screen (Termux's TerminalView has no scroll
    // callback). Cheap and main-thread only; runs only while this screen is
    // composed. Output-driven scroll changes are handled inline above, but the
    // poll also covers them as a backstop.
    LaunchedEffect(sessionId) {
        while (isActive) {
            delay(80)
            val view = terminalViewRef.value ?: continue
            val tr = view.topRow
            if (tr < 0) {
                scrollPause.lastOffset = tr
                if (!scrolledUp) scrolledUp = true
            } else {
                scrollPause.lastOffset = 0
                if (scrolledUp) scrolledUp = false
                if (hasNewOutput) hasNewOutput = false
            }
        }
    }

    // Take-over badge: shown while another device drives (i.e. while [passive]),
    // debounced so a momentary handoff blip doesn't flash it.
    var showTakeOver by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(passive) {
        if (passive) {
            delay(300)
            showTakeOver = true
        } else {
            showTakeOver = false
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            ptySocket.closeDetached()
            emulatorDispatcher.close()
        }
    }

    // Refresh the terminal whenever the screen returns to the foreground:
    // if the PTY stream has been quiet (idle shell, or a connection the OS
    // silently killed while the phone slept), the socket reconnects and the
    // server's ring-buffer replay — prefixed with a terminal reset — brings
    // the emulator up to date with whatever happened while we were away.
    // Actively-streaming sessions are left alone. ON_RESUME also fires on
    // first composition, which is harmless: the socket just connected, so
    // it is never stale at that point.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ptySocket.reconnectIfStale(PTY_RESUME_STALE_MS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        terminalViewRef.value?.requestLayout()
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = HeaderAccent,
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Leading pane-type icon (issue #48) — the same glyph the
                        // session list shows before each pane title, so the
                        // full-screen header stays consistent with the list. This
                        // screen only ever hosts terminal panes, hence the fixed
                        // [LeafKind.TERMINAL]; it is never a floating window here.
                        PaneIcon(kind = LeafKind.TERMINAL, floating = false)
                        Spacer(Modifier.width(8.dp))
                        // Pane status indicator (issue #38), painted in the
                        // theme foreground colour: idle = solid dot, working =
                        // breathing dot, waiting = pulsing warning triangle. The
                        // 18dp box bakes in ~5dp of trailing gap to the title.
                        StatusDot(state = paneState, boxDp = 18)
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = SidebarTextPrimary,
                            ),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { swipeInputActive = !swipeInputActive }) {
                        // Material extended's KeyboardHide (a keyboard with a
                        // downward chevron) mirrors the iOS toolbar's
                        // keyboard.chevron.compact.down toggle, so the
                        // text-input affordance reads the same on both apps.
                        Icon(
                            Icons.Filled.KeyboardHide,
                            contentDescription = "Text input bar",
                            tint = if (swipeInputActive) HeaderAccent else Color.Gray,
                        )
                    }
                    IconButton(onClick = {
                        val natural = localGrid
                        if (natural != null && natural.cols > 0 && natural.rows > 0) {
                            // Explicit fit: force this phone's grid unconditionally
                            // (bypass ensureDriving's no-op check — Reformat is how
                            // the user re-asserts their width even when the server
                            // already reports it). Record it so it doesn't
                            // immediately re-drive on the next keystroke.
                            drivingTo.set(natural.cols to natural.rows)
                            scope.launch {
                                runCatching { ptySocket.forceResize(natural.cols, natural.rows) }
                            }
                        }
                    }) {
                        ReformatIcon(tint = HeaderAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(bgComposeColor),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val view = TerminalView(context, null)
                        view.setTextSize(userFontSize)
                        view.setTypeface(TerminalFont.typeface(context))
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                        view.setOnTerminalGridSizeChangedListener { cols, rows ->
                            // Record the phone's NATURAL grid + vote it (so a lone or
                            // governing phone follows rotation) ONLY while not mirroring:
                            // while passive the view's grid is the shrunk-font mirror
                            // grid, and rotation must rescale the mirror, not steal the
                            // PTY. passiveGridPin is set iff we are mirroring.
                            if (passiveGridPin.get() == null) {
                                localGrid = AndroidGridDims(cols = cols, rows = rows)
                                gridFlow.value = cols to rows
                                scope.launch { runCatching { ptySocket.resize(cols, rows) } }
                            }
                        }
                        view.setTerminalViewClient(object : TerminalViewClient {
                            override fun onScale(scale: Float): Float {
                                if (scale < 0.95f || scale > 1.05f) {
                                    val step = if (scale > 1f) 1 else -1
                                    // Adjust the user's driving font; the applied size
                                    // (shrunk while mirroring) is derived + set in the
                                    // AndroidView update block.
                                    val next = (userFontSize + step).coerceIn(14, 96)
                                    if (next != userFontSize) userFontSize = next
                                    return 1f
                                }
                                return scale
                            }
                            override fun onSingleTapUp(e: android.view.MotionEvent?) {
                                view.requestFocus()
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                                // Tap-to-focus is a deliberate "use this pane"
                                // gesture (a tap, not a scroll — scrolls do not
                                // route here), so it takes over: fit the PTY to
                                // this phone. No-ops if already driving.
                                scope.launch { ensureDriving() }
                            }
                            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                            override fun shouldEnforceCharBasedInput(): Boolean = false
                            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                            override fun isTerminalViewSelected(): Boolean = true
                            override fun copyModeChanged(copyMode: Boolean) = Unit
                            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
                            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
                            override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
                            override fun readControlKey(): Boolean = ctrlSticky.value
                            override fun readAltKey(): Boolean = false
                            override fun readShiftKey(): Boolean = shiftSticky.value
                            override fun readFnKey(): Boolean = false
                            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?): Boolean = false
                            override fun onEmulatorSet() = Unit
                            override fun logError(tag: String?, message: String?) = Unit
                            override fun logWarn(tag: String?, message: String?) = Unit
                            override fun logInfo(tag: String?, message: String?) = Unit
                            override fun logDebug(tag: String?, message: String?) = Unit
                            override fun logVerbose(tag: String?, message: String?) = Unit
                            override fun logStackTraceWithMessage(tag: String?, message: String?, e: java.lang.Exception?) = Unit
                            override fun logStackTrace(tag: String?, e: java.lang.Exception?) = Unit
                        })
                        view.attachSession(session)
                        try {
                            val field = TerminalView::class.java.getDeclaredField("mEmulator")
                            field.isAccessible = true
                            field.set(view, emulator)
                        } catch (_: Throwable) {
                        }
                        applyTerminalColors(view, emulator, terminalPalette)
                        terminalViewRef.value = view
                        // No size seeding here: the phone renders its own
                        // pixel-derived width once laid out (grid listener), and
                        // serverGrid is seeded from the ptySize mirror in the
                        // events collector.
                        view
                    },
                    update = { view ->
                        applyTerminalColors(view, emulator, terminalPalette)
                        // Apply the derived font: the user's size while driving, shrunk
                        // to fit while mirroring a wider grid. Guarded (setTextSize
                        // rebuilds the renderer + relayouts unconditionally) so it fires
                        // only on a real change. The relayout re-runs the grid listener,
                        // which is gated on the passive pin so it can't clobber our
                        // natural grid.
                        if (appliedFontRef[0] != appliedFontSize) {
                            appliedFontRef[0] = appliedFontSize
                            view.setTextSize(appliedFontSize)
                        }
                        // Recomposition (e.g. our own scroll-pause state changes)
                        // must not yank a scrolled-up user back to the bottom:
                        // onScreenUpdated() force-snaps, so only call it when at
                        // the bottom and otherwise just repaint in place.
                        if (view.topRow < 0) view.invalidate() else view.onScreenUpdated()
                    },
                )

                // Take-over badge: shown while another device drives the PTY (this
                // phone is passive). Tapping it is an explicit, input-free take-over
                // — fit the shared PTY to this phone's width. Neutral copy: the size
                // broadcast doesn't carry which device is driving.
                if (showTakeOver) {
                    // Filled with the accent (rather than the surface tint) so it reads
                    // as an action over the mirrored content instead of blending into
                    // the terminal chrome — same treatment as the "New output" pill.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(terminalPalette.accent))
                            .clickable { scope.launch { ensureDriving() } }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Mirroring another device",
                            color = Color(terminalPalette.bg),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "· Tap to take over",
                            color = Color(terminalPalette.bg),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Floating "jump to bottom" pill, shown only while scrolled up.
                // Tapping it snaps back to the bottom and resumes auto-follow.
                // While paused, fresh output flips the label to "New output".
                if (scrolledUp) {
                    val pillBg = if (hasNewOutput) {
                        Color(terminalPalette.accent)
                    } else {
                        Color(terminalPalette.surface)
                    }
                    val pillFg = if (hasNewOutput) {
                        Color(terminalPalette.bg)
                    } else {
                        Color(terminalPalette.text)
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(pillBg)
                            .clickable {
                                terminalViewRef.value?.let { view ->
                                    view.topRow = 0
                                    view.onScreenUpdated()
                                }
                                scrollPause.lastOffset = 0
                                scrollPause.pendingRestore = null
                                scrollPause.restoreJob?.cancel()
                                scrolledUp = false
                                hasNewOutput = false
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = if (hasNewOutput) "New output" else "Jump to bottom",
                            color = pillFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "↓",
                            color = pillFg,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (swipeInputActive) {
                SwipeInputBar(
                    text = swipeText,
                    onTextChange = { swipeText = it },
                    onSubmit = {
                        // Send the typed text and the carriage return as two
                        // separate frames so Enter lands as its own keystroke —
                        // matching how native typing submits. A single
                        // "<text>\r" burst written raw to the PTY often isn't
                        // treated as accept-line (the trailing CR gets absorbed
                        // into the burst), which made the command text appear
                        // but never run. An empty field still sends a bare CR so
                        // the user can press Enter without leaving word mode.
                        val text = swipeText
                        scope.launch {
                            // Take over before the input reaches the PTY (see
                            // [ensureDriving]) so it is processed at this phone's
                            // grid, not the desktop's.
                            ensureDriving()
                            if (text.isNotEmpty()) {
                                ptySocket.send(text.toByteArray(Charsets.UTF_8))
                            }
                            ptySocket.send("\r".toByteArray(Charsets.UTF_8))
                        }
                        swipeText = ""
                    },
                    theme = terminalPalette,
                )
            }

            ImeHelperToolbar(
                ctrlSticky = ctrlSticky.value,
                onCtrlToggle = { ctrlSticky.value = !ctrlSticky.value },
                shiftSticky = shiftSticky.value,
                onShiftToggle = { shiftSticky.value = !shiftSticky.value },
                onSend = { bytes ->
                    scope.launch { ensureDriving(); ptySocket.send(bytes) }
                },
                theme = terminalPalette,
            )
        }
    }
}
