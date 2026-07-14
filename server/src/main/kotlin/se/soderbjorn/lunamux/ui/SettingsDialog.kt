/**
 * Server-side settings UI rendered via Compose Desktop.
 *
 * This file contains [SettingsDialog], a process-wide singleton Compose
 * Desktop window that provides the Lunamux settings interface. The
 * controls are split across three tabs so the MCP configuration doesn't
 * clutter the everyday network/device controls:
 *  - **Devices** tab:
 *    - Connections (listening port/IPs, allow-remote toggle, "Pair a
 *      device" QR dialog — see [PairingDialog]).
 *    - Approved device management (list, revoke).
 *    - Denied device management (list, unban).
 *  - **Features** tab:
 *    - Claude Code usage polling toggle.
 *  - **MCP** tab:
 *    - MCP server kill switch, token minting/scoping/revoking, the
 *      ready-to-paste `.mcp.json` snippet, recent agent-activity log, and
 *      the TLS-trust line for Node clients.
 *
 * The dialog is opened by the `OpenSettings` command from the `/window`
 * WebSocket (triggered by the client's settings button). Settings take
 * effect immediately -- there is no "Apply" button; the user dismisses
 * the dialog via the window close control.
 *
 * Silently no-ops in headless JVM environments (e.g. when running without
 * a display server).
 *
 * @see DeviceAuth
 * @see SettingsRepository
 * @see ClaudeUsageMonitor
 */
package se.soderbjorn.lunamux.ui

import se.soderbjorn.darkness.core.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.SERVER_TLS_PORT
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.net.LocalAddresses
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.tls.CertStore
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Process-wide Compose Desktop settings window.
 *
 * Only one instance is kept: if [show] is called while a dialog is already on
 * screen, the existing instance is brought to the front instead of spawning a
 * duplicate. Settings mutate the backing [SettingsRepository] immediately --
 * there is no "Apply" button; dismiss via the window's close control.
 */
object SettingsDialog {

    private val log = LoggerFactory.getLogger(SettingsDialog::class.java)

    @Volatile
    private var listeningPort: Int? = null

    @Volatile
    var usageMonitor: ClaudeUsageMonitor? = null

    // Guarded by the Compose UI thread — mutations must happen on the AWT EDT
    // so Compose Desktop's recomposer picks up the change.
    private val showing = mutableStateOf(false)
    private var repo: SettingsRepository? = null

    /**
     * Record the Ktor server's listening port so the Network section can
     * display it. Called once from [Application.main] after the port is resolved.
     *
     * @param port the TCP port the server is listening on
     */
    fun setListeningPort(port: Int) {
        listeningPort = port
    }

    /**
     * The `clientType` the Electron shell reports (see the UA sniff in the
     * web client's `start()`). Generic because the shell doesn't know its
     * platform; [deviceTitle] renders it as "Mac".
     */
    private const val DESKTOP_CLIENT_TYPE = "Computer"

    /** Lunamux dark-theme blue used for accents throughout the dialog. */
    private val lunamuxBlue = Color(0xFF0A84FF)

    val lunamuxColorScheme = darkColorScheme(
        primary = lunamuxBlue,
        onPrimary = Color.White,
        secondary = lunamuxBlue,
        onSecondary = Color.White,
    )

    private val brandImage by lazy {
        runCatching {
            SettingsDialog::class.java.getResourceAsStream("/lunamux-icon.png")
                ?.use { ImageIO.read(it) }
        }.onFailure { log.warn("Failed to load lunamux-icon.png for settings dialog", it) }
            .getOrNull()
    }

    private val iconPainter by lazy {
        brandImage?.toComposeImageBitmap()?.let { BitmapPainter(it) }
    }

    /**
     * Pop the dialog on screen. Silently no-ops in headless JVMs.
     */
    fun show(repo: SettingsRepository) {
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Ignoring settings-dialog request in headless mode")
            return
        }
        // Dispatch onto the AWT EDT so Compose Desktop's recomposer observes
        // the state change. show() is called from Ktor worker threads.
        SwingUtilities.invokeLater {
            this.repo = repo
            showing.value = true
        }
    }

    /**
     * Call from a top-level Compose application scope (or wrapped in an
     * `application {}` block) so the window lifecycle is managed by Compose.
     *
     * The Window is always part of the composition tree (so Compose keeps
     * its recomposer active) but toggled via [visible]. This avoids the
     * problem where an initially-empty application scope has no frame
     * clock to drive recomposition of state changes.
     */
    @Composable
    fun renderIfShowing() {
        val isShowing by showing
        val currentRepo = repo

        val windowState = rememberWindowState(
            size = DpSize(640.dp, 780.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        Window(
            onCloseRequest = { showing.value = false },
            title = "Lunamux \u2014 Settings",
            state = windowState,
            icon = iconPainter,
            alwaysOnTop = true,
            resizable = true,
            visible = isShowing && currentRepo != null,
        ) {
            if (currentRepo != null) {
                MaterialTheme(colorScheme = lunamuxColorScheme) {
                    Surface {
                        SettingsContent(currentRepo, isShowing)
                    }
                }
            }
        }
    }

    /**
     * Compose the body of the settings window.
     *
     * Called from [renderIfShowing] whenever the window is part of the
     * composition tree. The [isShowing] flag is forwarded to the device
     * sections as a refresh key so their lists are re-read from the repo
     * each time the dialog is reopened — the Window is kept in the
     * composition permanently and only its `visible` flag is toggled, so
     * unkeyed `remember { }` would otherwise cache the lists for the
     * lifetime of the process.
     *
     * The sections are split across three tabs so the MCP configuration
     * (token minting, scopes, the `.mcp.json` snippet, agent-activity log,
     * TLS-trust line) doesn't clutter the everyday network/device controls:
     *  - **Devices** — Connections, Approved / Denied devices.
     *  - **Features** — [ClaudeUsageSection] on its own.
     *  - **MCP** — [McpSection] on its own.
     *
     * The header and the tab bar stay pinned at the top; only the selected
     * tab's section stack scrolls (with the scrollbar scoped to that area).
     *
     * @param repo settings repository backing the dialog
     * @param isShowing current visibility of the dialog window; flipping
     *   to true triggers a fresh read of trusted/denied device lists
     * @see DeniedDevicesSection
     * @see TrustedDevicesSection
     * @see McpSection
     */
    @Composable
    private fun SettingsContent(repo: SettingsRepository, isShowing: Boolean) {
        val scrollState = rememberScrollState()
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Devices", "Features", "MCP")

        // Bumped when a flow outside this window may have mutated settings —
        // currently the pairing dialog, whose QR approval adds a trusted
        // device. Paired with isShowing so both reopening the window and
        // closing the pairing dialog force a fresh repo read.
        var settingsRefresh by remember { mutableStateOf(0) }
        val refreshKey: Any = Pair(isShowing, settingsRefresh)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
        ) {
            // Header (pinned above the tabs — does not scroll).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                brandImage?.toComposeImageBitmap()?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Lunamux icon",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(22f)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        "Lunamux",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Settings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Tab bar (pinned) — selecting a tab swaps the scrollable body below.
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tabTitle ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tabTitle) },
                    )
                }
            }

            // Scrollable body for the selected tab. The scrollbar is scoped to
            // this Box so it tracks the section stack rather than the header/tabs.
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp)
                        .verticalScroll(scrollState),
                ) {
                    when (selectedTab) {
                        1 -> {
                            ClaudeUsageSection(repo)
                        }
                        2 -> {
                            McpSection(repo, refreshKey)
                        }
                        else -> {
                            ConnectionsSection(repo, refreshKey, onPairingDialogClosed = { settingsRefresh++ })
                            Spacer(Modifier.height(16.dp))
                            TrustedDevicesSection(repo, refreshKey)
                            Spacer(Modifier.height(16.dp))
                            DeniedDevicesSection(repo, refreshKey)
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp),
                    style = ScrollbarStyle(
                        minimalHeight = 32.dp,
                        thickness = 8.dp,
                        shape = RoundedCornerShape(4.dp),
                        hoverDurationMillis = 300,
                        unhoverColor = Color.White.copy(alpha = 0.25f),
                        hoverColor = lunamuxBlue.copy(alpha = 0.6f),
                    ),
                )
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
    }

    /**
     * Render the "Connections" section: how this server can be reached
     * (addresses + port), the allow-remote master switch, and the "Pair a
     * device" entry point to the QR pairing dialog.
     *
     * Called from [SettingsContent] on the General tab.
     *
     * "Pair via QR code" is gated on the allow-remote toggle: pairing can
     * only trust devices the network gate already admits, so the button and
     * its description are absent entirely while the switch is off.
     *
     * @param repo settings repository backing the allow-remote toggle.
     * @param refreshKey changing this value re-reads settings state from the
     *   repo, so reopening the window shows what is actually stored.
     * @param onPairingDialogClosed invoked when the pairing dialog closes so
     *   the parent can bump the refresh counter — a completed pairing adds a
     *   trusted device that the sections below must pick up.
     * @see PairingDialog
     */
    @Composable
    private fun ConnectionsSection(
        repo: SettingsRepository,
        refreshKey: Any,
        onPairingDialogClosed: () -> Unit,
    ) {
        SectionHeader("Connections")

        val port = listeningPort
        val addresses = remember { LocalAddresses.ipv4() }

        Text("Loopback: 127.0.0.1", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        if (addresses.isEmpty()) {
            Text(
                "No non-loopback IPv4 interfaces found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            Text(
                "LAN: ${addresses.joinToString(", ")}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        if (port != null) {
            Text("Port: $port", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        // Keyed on refreshKey so reopening the window re-reads the stored
        // value rather than showing whatever this composable last had.
        var allowRemote by remember(refreshKey) { mutableStateOf(repo.isAllowRemoteConnections()) }
        var showPairing by remember { mutableStateOf(false) }
        SettingToggleRow(
            title = "Allow connections from other devices",
            description = "When off, connections are only accepted from this " +
                "computer. They still need individual approval.",
            checked = allowRemote,
        ) {
            allowRemote = it
            repo.setAllowRemoteConnections(it)
            // Switching off closes an open QR window: the code it shows would
            // no longer get a scanning device past the network gate.
            if (!it) showPairing = false
            log.info("Settings: allow-remote toggled to {}", it)
        }

        // Pairing only ever trusts devices the network gate already admits,
        // so the whole control is absent until the user opts into remote
        // connections — there is nothing it could usefully do while off.
        if (allowRemote) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showPairing = true }) {
                Text("Pair via QR code")
            }
            Text(
                "Shows a QR code the Lunamux mobile app can scan to connect " +
                    "instantly — no addresses to type, no approval dialog.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        if (showPairing) {
            PairingDialog(
                port = port ?: SERVER_TLS_PORT,
                onClose = {
                    showPairing = false
                    onPairingDialogClosed()
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun ClaudeUsageSection(repo: SettingsRepository) {
        SectionHeader("Claude Code")

        var pollUsage by remember { mutableStateOf(repo.isClaudeUsagePollEnabled()) }
        SettingToggleRow(
            title = "Poll Claude Code usage data",
            description = "Periodically polls usage data and displays it in the " +
                "Mac app's sidebar.",
            checked = pollUsage,
        ) {
            pollUsage = it
            repo.setClaudeUsagePollEnabled(it)
            val monitor = usageMonitor
            if (monitor != null) {
                if (it) monitor.start() else monitor.stop()
            }
            log.info("Settings: claude-usage-poll toggled to {}", it)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /**
     * Render the "MCP" section: the global kill switch, token minting with
     * a ready-to-paste `.mcp.json` snippet, the scope/revoke list for
     * existing MCP tokens, and the TLS-trust instructions for Node-based
     * clients (`NODE_EXTRA_CA_CERTS` pointing at the exported leaf PEM).
     *
     * Called from [SettingsContent]. Tokens are stored hashed, so the raw
     * token (and the snippet embedding it) is only shown for the token
     * generated in this dialog session — once the dialog closes it cannot
     * be recovered, only revoked and re-minted.
     *
     * @param repo settings repository backing the kill switch and token list
     * @param refreshKey changing this value re-reads the token list (the
     *   dialog window stays composed while hidden — see [TrustedDevicesSection])
     * @see DeviceAuth.addTrustedToken
     * @see DeviceAuth.listMcpTokens
     * @see CertStore.leafPemFile
     */
    @Composable
    private fun McpSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("MCP server")

        var enabled by remember { mutableStateOf(repo.isMcpEnabled()) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = {
                enabled = !enabled
                repo.setMcpEnabled(enabled)
                log.info("Settings: MCP enabled toggled to {}", enabled)
            }),
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    repo.setMcpEnabled(it)
                    log.info("Settings: MCP enabled toggled to {}", it)
                },
            )
            Text("Enable MCP server (local connections only)", fontSize = 14.sp)
        }

        Spacer(Modifier.height(8.dp))

        var tokens by remember(refreshKey) { mutableStateOf(DeviceAuth.listMcpTokens(repo)) }
        // Raw token + snippet for the token minted in this dialog session.
        var freshSnippet by remember(refreshKey) { mutableStateOf<String?>(null) }

        Button(onClick = {
            val raw = generateMcpToken()
            DeviceAuth.addTrustedToken(repo, raw, DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
            tokens = DeviceAuth.listMcpTokens(repo)
            freshSnippet = mcpJsonSnippet(raw)
            log.info("Settings: generated a new MCP token (scope=read)")
        }) {
            Text("Generate MCP token")
        }

        freshSnippet?.let { snippet ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste into your project's .mcp.json (shown once — copy it now):",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(snippet, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row {
                Button(onClick = { copyToClipboard(snippet) }) { Text("Copy snippet") }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (tokens.isEmpty()) {
            Text(
                "No MCP tokens yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            val df = remember { SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.ENGLISH) }
            tokens.forEach { token ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "MCP token #${token.tokenHash.take(10)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            "scope: ${token.scope ?: "?"} · last used " +
                                df.format(Date(token.lastSeenEpochMs)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    val isWrite = token.scope == DeviceAuth.MCP_SCOPE_READ_WRITE
                    Button(onClick = {
                        val newScope = if (isWrite) DeviceAuth.MCP_SCOPE_READ
                        else DeviceAuth.MCP_SCOPE_READ_WRITE
                        DeviceAuth.setMcpTokenScope(repo, token.tokenHash, newScope)
                        tokens = DeviceAuth.listMcpTokens(repo)
                        log.info("Settings: MCP token {} scope set to {}", token.tokenHash.take(10), newScope)
                    }) {
                        Text(if (isWrite) "Make read-only" else "Allow writes")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        DeviceAuth.revokeTrustedDevice(repo, token.tokenHash)
                        tokens = DeviceAuth.listMcpTokens(repo)
                        log.info("Settings: revoked MCP token {}", token.tokenHash.take(10))
                    }) {
                        Text("Revoke")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        // Compact agent-activity list: the most recent MCP write calls
        // (tool + redacted args), newest first. Re-read on each dialog show.
        val activity = remember(refreshKey) { se.soderbjorn.lunamux.mcp.McpActivityLog.recent(12) }
        if (activity.isNotEmpty()) {
            Text("Recent agent activity:", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val adf = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
            activity.forEach { entry ->
                Text(
                    "${adf.format(Date(entry.atEpochMs))}  ${entry.tool}  ${entry.detail}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        val pemPath = remember { CertStore.leafPemFile().absolutePath }
        Text(
            "TLS trust for Node clients (Claude Code): export before launching —",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "NODE_EXTRA_CA_CERTS=$pemPath",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Row {
            Button(onClick = { copyToClipboard("NODE_EXTRA_CA_CERTS=$pemPath") }) {
                Text("Copy env line")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /** Mint a fresh 256-bit hex MCP token. */
    private fun generateMcpToken(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return "mcp-" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the ready-to-paste `.mcp.json` snippet embedding [rawToken] and
     * the server's live port.
     */
    private fun mcpJsonSnippet(rawToken: String): String {
        val port = listeningPort ?: 8443
        return """
            {
              "mcpServers": {
                "lunamux": {
                  "type": "http",
                  "url": "https://localhost:$port/mcp",
                  "headers": { "X-Termtastic-Auth": "$rawToken" }
                }
              }
            }
        """.trimIndent()
    }

    /** Copy [text] to the system clipboard (best effort). */
    private fun copyToClipboard(text: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(text), null,
            )
        }.onFailure { log.warn("Failed to copy to clipboard", it) }
    }

    /**
     * Render the "Approved devices" section.
     *
     * Called from [SettingsContent] on the Devices tab. Lists every approved
     * device and offers a "Revoke" action per row. ("Trusted" survives in the
     * identifiers and the persisted `auth.trusted_devices.v1` blob — only the
     * user-facing wording is "approved".) The list is read from [repo] each time
     * [refreshKey] changes —
     * callers pass the dialog's visibility flag so a fresh snapshot is
     * loaded whenever the window becomes visible (the Window stays in
     * the composition tree, so an unkeyed `remember { }` would only read
     * once on first show).
     *
     * @param repo settings repository to read trusted devices from
     * @param refreshKey changing this value discards the cached list and
     *   re-reads from the repo on the next composition
     * @see DeviceAuth.listTrustedDevices
     * @see DeviceAuth.revokeTrustedDevice
     */
    @Composable
    private fun TrustedDevicesSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("Approved devices")

        var devices by remember(refreshKey) { mutableStateOf(DeviceAuth.listTrustedDevices(repo)) }
        val df = remember { SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.ENGLISH) }

        if (devices.isEmpty()) {
            Text(
                "No approved devices yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    title = deviceTitle(device.label, device.connections),
                    hashPrefix = device.tokenHash.take(10),
                    tag = viaTag(device.trustedVia),
                    // A trusted record is only ever built at the moment of
                    // approval and never rewritten, so first-seen is the
                    // approval instant — including on entries that predate
                    // the trustedVia tag, which still get a correct date.
                    stamp = "Approved ${df.format(Date(device.firstSeenEpochMs))}",
                    connections = device.connections,
                    actionLabel = "Revoke",
                    df = df,
                    // The auto-approved device is this machine's own client —
                    // the one that opened this window. Revoking it is what
                    // makes the approval dialog the only way back in, and that
                    // dialog's Deny button bans the token for good, with no
                    // path left to reach "Unban": the settings window is only
                    // reachable over the /window socket of an approved client.
                    actionEnabled = device.trustedVia != DeviceAuth.TRUSTED_VIA_AUTO,
                    onAction = {
                        val removed = DeviceAuth.revokeTrustedDevice(repo, device.tokenHash)
                        log.info(
                            "Settings: revoke trusted device {} removed={}",
                            device.tokenHash.take(10),
                            removed,
                        )
                        devices = DeviceAuth.listTrustedDevices(repo)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /**
     * Render the "Denied devices" section.
     *
     * Called from [SettingsContent]. Lists every device the user has
     * explicitly denied via [DeviceAuth.promptOrReject] and offers an
     * "Unban" action per row. The list is read from [repo] each time
     * [refreshKey] changes — callers pass the dialog's visibility flag
     * so a fresh snapshot is loaded whenever the window becomes visible.
     * Without this, denials persisted while the dialog was closed would
     * not appear on reopen, because the Window stays in the composition
     * tree and an unkeyed `remember { }` only reads once on first show.
     *
     * @param repo settings repository to read denied devices from
     * @param refreshKey changing this value discards the cached list and
     *   re-reads from the repo on the next composition
     * @see DeviceAuth.listDeniedDevices
     * @see DeviceAuth.unbanDeniedDevice
     */
    @Composable
    private fun DeniedDevicesSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("Denied devices")

        var devices by remember(refreshKey) { mutableStateOf(DeviceAuth.listDeniedDevices(repo)) }
        val df = remember { SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.ENGLISH) }

        if (devices.isEmpty()) {
            Text(
                "No denied devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    title = deviceTitle(null, device.connections),
                    hashPrefix = device.tokenHash.take(10),
                    tag = null,
                    stamp = "Denied ${df.format(Date(device.firstSeenEpochMs))}",
                    connections = device.connections,
                    actionLabel = "Unban",
                    df = df,
                    onAction = {
                        val removed = DeviceAuth.unbanDeniedDevice(repo, device.tokenHash)
                        log.info(
                            "Settings: unban denied device {} removed={}",
                            device.tokenHash.take(10),
                            removed,
                        )
                        devices = DeviceAuth.listDeniedDevices(repo)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    /**
     * The client type is what a user actually recognises a device by
     * ("Mac", "Android"), so it heads the row. An explicit [label] wins
     * where one exists \u2014 that is the [DeviceAuth.MCP_LABEL] trust class,
     * which is a more meaningful name than the type would be.
     *
     * The desktop shell reports the generic `"Computer"`; since it only ships
     * for macOS, that is rendered as "Mac". Interpreting at display time
     * rather than changing what the client sends means rows already stored as
     * "Computer" read correctly too, with no new connection entry (the type
     * is part of a connection's identity, so changing it would fork one).
     * If the shell ever ships for another platform, this mapping is the lie
     * to fix \u2014 by sending a real platform, not by extending the guess.
     *
     * Called from [TrustedDevicesSection] and [DeniedDevicesSection].
     *
     * @param label the persisted trust-class label, or `null` for ordinary
     *   interactive devices.
     * @param connections the device's connection history; the most recent
     *   entry supplies the type.
     * @return a display title, falling back to "Device" when the type is
     *   absent or the server's `"Unknown"` placeholder.
     */
    private fun deviceTitle(
        label: String?,
        connections: List<DeviceAuth.ClientConnectionInfo>,
    ): String {
        label?.takeIf { it.isNotBlank() }?.let { return it }
        val type = connections
            .maxByOrNull { it.lastSeenEpochMs }
            ?.type
            ?.takeIf { it.isNotBlank() && it != DeviceAuth.UNKNOWN_CLIENT_TYPE }
        return when (type) {
            null -> "Device"
            DESKTOP_CLIENT_TYPE -> "Mac"
            else -> type
        }
    }

    /**
     * Human-readable tag for how a device came to be trusted.
     *
     * Called from [TrustedDevicesSection]. Returns `null` for devices
     * persisted before provenance was recorded, which are deliberately shown
     * untagged: the approval date still renders, and an absent tag reads as
     * "predates this" rather than implying a fact we do not have.
     *
     * @param via the persisted [DeviceAuth.TRUSTED_VIA_AUTO] / `_QR` /
     *   `_DIALOG` value, or `null`.
     * @return the tag to render beside the hash, or `null` for no tag.
     */
    private fun viaTag(via: String?): String? = when (via) {
        DeviceAuth.TRUSTED_VIA_AUTO -> "auto-approved (first install)"
        DeviceAuth.TRUSTED_VIA_QR -> "paired via QR"
        DeviceAuth.TRUSTED_VIA_DIALOG -> "approved in dialog"
        else -> null
    }

    /**
     * Render one device as a title line, an approval/denial stamp, and its
     * address history.
     *
     * Called from [TrustedDevicesSection] and [DeniedDevicesSection]. The
     * most recently used address is always visible and doubles as the "last
     * seen" line; any older addresses collapse behind a chevron, so the
     * common single-address device is just two lines and only a device that
     * has actually roamed can be expanded.
     *
     * @param title display name from [deviceTitle].
     * @param hashPrefix short form of the token hash, shown as `#abc123`.
     * @param tag optional provenance tag from [viaTag].
     * @param stamp the approval/denial line, already formatted.
     * @param connections the device's address history; empty renders as
     *   "Never connected" (an MCP token minted but never used).
     * @param actionLabel text for the trailing button ("Revoke"/"Unban").
     * @param df formatter shared with the caller for stable timestamps.
     * @param onAction invoked when the trailing button is clicked.
     * @param actionEnabled false greys the trailing button out; see the
     *   auto-approved carve-out in [TrustedDevicesSection].
     * @see ConnectionLine
     */
    @Composable
    private fun DeviceRow(
        title: String,
        hashPrefix: String,
        tag: String?,
        stamp: String,
        connections: List<DeviceAuth.ClientConnectionInfo>,
        actionLabel: String,
        df: SimpleDateFormat,
        onAction: () -> Unit,
        actionEnabled: Boolean = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "#$hashPrefix",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    if (tag != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tag,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    stamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                val sorted = remember(connections) {
                    connections.sortedByDescending { it.lastSeenEpochMs }
                }
                if (sorted.isEmpty()) {
                    Text(
                        "  Never connected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                } else {
                    // Keyed on the hash, not the list position: the sections
                    // re-read and re-render on refresh, so unkeyed state would
                    // stay bound to the slot and a revoke would hand this
                    // device's expansion to whichever row slid up into it.
                    var expanded by remember(hashPrefix) { mutableStateOf(false) }
                    val older = sorted.drop(1)
                    ConnectionLine(
                        c = sorted.first(),
                        df = df,
                        prefix = when {
                            older.isEmpty() -> "  "
                            expanded -> "\u25be "
                            else -> "\u25b8 "
                        },
                        onClick = if (older.isEmpty()) null else ({ expanded = !expanded }),
                    )
                    if (expanded) {
                        older.forEach { c ->
                            ConnectionLine(c = c, df = df, prefix = "   ", onClick = null)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel)
            }
        }
    }

    /**
     * Render a single address the device has connected from.
     *
     * Called from [DeviceRow] for both the always-visible most-recent line
     * and each collapsed older one. Leads with `remoteAddress` because that
     * is the only field here the server observed; the hostname and
     * self-reported IP are client-supplied and untrusted, so they are
     * demoted into parentheses where they cannot be mistaken for verified
     * facts.
     *
     * The client type is deliberately absent: [deviceTitle] already heads the
     * row with it, so repeating it here only added noise — and worse, it
     * repeated the *raw* type, so a desktop row read "Mac … (Computer)".
     *
     * @param c the connection to render.
     * @param df formatter for the last-seen timestamp, rendered inline after
     *   the address rather than justified to the far edge.
     * @param prefix indent or chevron glyph.
     * @param onClick toggles expansion; `null` renders a non-clickable line.
     */
    @Composable
    private fun ConnectionLine(
        c: DeviceAuth.ClientConnectionInfo,
        df: SimpleDateFormat,
        prefix: String,
        onClick: (() -> Unit)?,
    ) {
        val extras = listOfNotNull(
            c.hostname?.takeIf { it.isNotBlank() },
            c.selfReportedIp?.takeIf { it.isNotBlank() && it != c.remoteAddress },
        )
        val addr =
            if (extras.isEmpty()) c.remoteAddress
            else "${c.remoteAddress} (${extras.joinToString(", ")})"
        // One Text, not an address/timestamp pair justified to opposite
        // edges: the row is as wide as the dialog, so weighting the address
        // stranded the timestamp under the action button with a canyon of
        // empty space between the two facts that belong together.
        // fillMaxWidth stays so the chevron's click target spans the row.
        val base = Modifier.fillMaxWidth().padding(top = 2.dp)
        Row(modifier = if (onClick != null) base.clickable(onClick = onClick) else base) {
            Text(
                "$prefix$addr · ${df.format(Date(c.lastSeenEpochMs))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }

}
