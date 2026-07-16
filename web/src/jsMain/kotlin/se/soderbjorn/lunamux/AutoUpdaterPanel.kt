/**
 * AutoUpdaterPanel.kt
 * -------------------
 * Renderer half of the Lunamux desktop auto-updater (Electron only).
 *
 * The Electron main process drives electron-updater (see the `electron-main`
 * module's `AutoUpdater.kt`) and forwards each lifecycle event to the renderer
 * over the preload bridge (`window.electronApi.onUpdate*`). This file:
 *
 *  - keeps a tiny [UpdaterUiState] state machine fed by those events
 *    ([initAutoUpdaterListeners]),
 *  - renders it as an **"Updates" section** in the App Settings sidebar
 *    ([buildUpdatesSection], registered from
 *    [buildAppSettingsContent]) with a status line, a download progress bar,
 *    and a single primary button (Check / Download / Restart), and
 *  - reflects "an update is pending" onto the existing top-bar bell (reusing
 *    [electronUpdatePending] / [applyNewsTopbarIcon] in NewsLabel.kt) and opens
 *    the panel on demand ([openUpdatesPanel] — used by the bell click and the
 *    "Check for Updates…" Help-menu item).
 *
 * All entry points are safe to call outside Electron: they no-op when the
 * `electronApi` bridge (or its updater methods) is absent, and the section is
 * only appended for [isElectronClient].
 *
 * @see initAutoUpdaterListeners
 * @see buildUpdatesSection
 * @see openUpdatesPanel
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.math.roundToInt
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** DOM id of the Updates section, so [openUpdatesPanel] can scroll it into view. */
private const val UPDATES_SECTION_ID = "lunamux-updates-section"

/**
 * The phases of an update, mirroring electron-updater's lifecycle events. Drives
 * the status text and which action the primary button performs.
 */
private enum class UpdaterStatus {
    /** No check has run yet this session. */
    IDLE,

    /** A check is in flight. */
    CHECKING,

    /** A newer version exists and can be downloaded. */
    AVAILABLE,

    /** The update is downloading. */
    DOWNLOADING,

    /** The update finished downloading and can be installed. */
    DOWNLOADED,

    /** The last check found the app already up to date. */
    NOT_AVAILABLE,

    /** The last operation failed. */
    ERROR,
}

/**
 * Immutable snapshot of the updater UI.
 *
 * @property status the current lifecycle phase.
 * @property version the target version, when known (available/downloaded).
 * @property percent download progress 0..100, when downloading.
 * @property message an error message, when [status] is [UpdaterStatus.ERROR].
 */
private data class UpdaterUiState(
    val status: UpdaterStatus = UpdaterStatus.IDLE,
    val version: String? = null,
    val percent: Double? = null,
    val message: String? = null,
)

/** The live updater state, updated by the events wired in [initAutoUpdaterListeners]. */
private var updaterState = UpdaterUiState()

/**
 * The mounted panel's re-render callback, or `null` when the Updates section is
 * not currently in the DOM. Set by [buildUpdatesSection] each time the sidebar
 * body is (re)built; only one App Settings sidebar exists at a time, so a single
 * slot suffices. Updates to a since-detached panel are harmless no-ops.
 */
private var panelRender: ((UpdaterUiState) -> Unit)? = null

/** Guards one-time event subscription in [initAutoUpdaterListeners]. */
private var listenersInitialized = false

/**
 * Whether the current (or most recent) update lifecycle was started by an
 * explicit user gesture — the panel's button or the "Check for Updates…" menu —
 * as opposed to the silent check at launch. [setUpdaterState] suppresses the
 * non-actionable phases (checking / up-to-date / error) unless this is set, so a
 * silent startup check never plants a stale resting state in the panel.
 * Available/downloading/downloaded are always surfaced regardless.
 */
private var userInitiatedAction = false

/**
 * Subscribe once to the main process's update lifecycle events and route them
 * into [updaterState] (+ the bell + any mounted panel).
 *
 * Called from `main.kt`'s startup inside the `isElectronClient` branch. No-ops
 * when the `electronApi` bridge or its updater methods are absent (a plain
 * browser, or an older preload), so it is always safe to call.
 */
fun initAutoUpdaterListeners() {
    if (listenersInitialized) return
    val api = window.asDynamic().electronApi ?: return
    if (api.onUpdateAvailable == null) return
    listenersInitialized = true

    api.onUpdateChecking({ setUpdaterState(UpdaterUiState(UpdaterStatus.CHECKING)) })
    api.onUpdateAvailable({ info: dynamic ->
        setUpdaterState(UpdaterUiState(UpdaterStatus.AVAILABLE, version = info?.version as? String))
    })
    api.onUpdateNotAvailable({ setUpdaterState(UpdaterUiState(UpdaterStatus.NOT_AVAILABLE)) })
    api.onUpdateProgress({ p: dynamic ->
        setUpdaterState(UpdaterUiState(UpdaterStatus.DOWNLOADING, percent = (p?.percent as? Number)?.toDouble()))
    })
    api.onUpdateDownloaded({ info: dynamic ->
        setUpdaterState(UpdaterUiState(UpdaterStatus.DOWNLOADED, version = info?.version as? String))
    })
    api.onUpdateError({ err: dynamic ->
        setUpdaterState(UpdaterUiState(UpdaterStatus.ERROR, message = err?.message as? String))
    })
}

/**
 * Commit a new [UpdaterUiState]: store it, mirror "update pending" onto the
 * top-bar bell, and re-render the panel if it is mounted.
 *
 * @param next the state to apply.
 */
private fun setUpdaterState(next: UpdaterUiState) {
    // Don't let the silent startup check plant a resting state: suppress the
    // non-actionable phases (checking / up-to-date / error) unless the user asked.
    // Available/downloading/downloaded are always surfaced (actionable regardless).
    when (next.status) {
        UpdaterStatus.CHECKING -> if (!userInitiatedAction) return
        UpdaterStatus.NOT_AVAILABLE, UpdaterStatus.ERROR -> {
            if (!userInitiatedAction) return
            userInitiatedAction = false // terminal outcome of the user's action
        }
        else -> {}
    }
    updaterState = next
    // Light the shared bell while an update is available/downloading/ready. The
    // flag is OR'd with the news state inside applyNewsTopbarIcon, so this never
    // clobbers a concurrent news signal (and vice versa).
    electronUpdatePending = when (next.status) {
        UpdaterStatus.AVAILABLE, UpdaterStatus.DOWNLOADING, UpdaterStatus.DOWNLOADED -> true
        else -> false
    }
    applyNewsTopbarIcon()
    panelRender?.invoke(next)
}

/**
 * Mark the next update lifecycle as user-initiated, so its "Checking…" /
 * "up to date" / error outcome is shown in the panel (the silent startup check
 * is not). Called by `main.kt`'s "Check for Updates…" menu handler, which
 * triggers a main-process check; the panel's own button sets the flag directly.
 */
fun markUserInitiatedUpdateAction() {
    userInitiatedAction = true
}

/**
 * Open the App Settings sidebar and scroll the Updates section into view.
 *
 * Called by the top-bar bell click (when an update is pending, see
 * `buildNewsTopbarAction`) and by the "Check for Updates…" Help-menu item (via
 * the `onShowUpdatesPanel` bridge). No-op-safe outside Electron.
 *
 * @see openAppSettingsSidebar
 */
fun openUpdatesPanel() {
    openAppSettingsSidebar()
    // The sidebar body is (re)built and slid in by the toolkit; wait a beat for
    // it to mount before scrolling the section into view.
    window.setTimeout({
        val section = document.getElementById(UPDATES_SECTION_ID)
        section?.asDynamic()?.scrollIntoView(js("({ behavior: 'smooth', block: 'start' })"))
        Unit
    }, 250)
}

/**
 * Build the "Updates" section for the App Settings sidebar body.
 *
 * Registers itself as the live [panelRender] target and renders the current
 * [updaterState] immediately, so a section opened after an update was already
 * found shows the right state. Appended by [buildAppSettingsContent], gated on
 * [isElectronClient].
 *
 * @return the freshly-built section element.
 */
fun buildUpdatesSection(): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunamux-app-settings-section"
    section.id = UPDATES_SECTION_ID

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-app-settings-section-title"
    title.textContent = "Updates"
    section.appendChild(title)

    val row = document.createElement("div") as HTMLElement
    row.className = "lunamux-app-settings-toggle-row"

    val header = document.createElement("div") as HTMLElement
    header.className = "lunamux-app-settings-toggle-header"
    val statusLabel = document.createElement("span") as HTMLElement
    statusLabel.className = "lunamux-app-settings-toggle-label"
    header.appendChild(statusLabel)
    row.appendChild(header)

    // Download progress bar — a thin track with a currentColor fill, shown only
    // while downloading. currentColor keeps it readable in either theme.
    val progressTrack = document.createElement("div") as HTMLElement
    progressTrack.style.height = "4px"
    progressTrack.style.borderRadius = "2px"
    progressTrack.style.background = "rgba(127, 127, 127, 0.25)"
    progressTrack.style.margin = "8px 0"
    progressTrack.style.setProperty("overflow", "hidden")
    val progressBar = document.createElement("div") as HTMLElement
    progressBar.style.height = "100%"
    progressBar.style.width = "0%"
    progressBar.style.background = "currentColor"
    progressBar.style.opacity = "0.8"
    progressBar.style.transition = "width 120ms linear"
    progressTrack.appendChild(progressBar)
    row.appendChild(progressTrack)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "dt-settings-button-row"
    val primaryBtn = document.createElement("button") as HTMLElement
    (primaryBtn.asDynamic()).type = "button"
    primaryBtn.className = "dt-settings-choice-btn"
    // One handler, dispatching on the live status at click time so we never
    // stack listeners across re-renders.
    primaryBtn.addEventListener("click", { _: Event ->
        val api = window.asDynamic().electronApi ?: return@addEventListener
        // Explicit user action, so its outcome (including errors) is shown.
        userInitiatedAction = true
        // Guard each method: an older preload (renderer/preload version skew) may
        // expose electronApi without the updater methods.
        when (updaterState.status) {
            UpdaterStatus.AVAILABLE -> if (api.downloadUpdate != null) api.downloadUpdate()
            UpdaterStatus.DOWNLOADED -> if (api.quitAndInstall != null) api.quitAndInstall()
            else -> if (api.checkForUpdates != null) api.checkForUpdates()
        }
        Unit
    })
    btnRow.appendChild(primaryBtn)
    row.appendChild(btnRow)

    section.appendChild(row)

    /** Reflect [s] onto the status line, progress bar, and primary button. */
    fun render(s: UpdaterUiState) {
        val pct = s.percent?.roundToInt()
        progressTrack.style.display = if (s.status == UpdaterStatus.DOWNLOADING) "block" else "none"
        if (s.status == UpdaterStatus.DOWNLOADING) progressBar.style.width = "${pct ?: 0}%"

        val (statusText, buttonText, disabled) = when (s.status) {
            UpdaterStatus.IDLE ->
                Triple("Check whether a newer version is available.", "Check for updates", false)
            UpdaterStatus.CHECKING ->
                Triple("Checking for updates…", "Checking…", true)
            UpdaterStatus.AVAILABLE ->
                Triple(
                    "Update available" + (s.version?.let { " — v$it" } ?: "") + ".",
                    "Download", false,
                )
            UpdaterStatus.DOWNLOADING ->
                Triple("Downloading update…" + (pct?.let { " $it%" } ?: ""), "Downloading…", true)
            UpdaterStatus.DOWNLOADED ->
                Triple(
                    "Update ready" + (s.version?.let { " (v$it)" } ?: "") + " — restart to install.",
                    "Restart to install", false,
                )
            UpdaterStatus.NOT_AVAILABLE ->
                Triple("You're on the latest version.", "Check for updates", false)
            UpdaterStatus.ERROR ->
                Triple("Update check failed" + (s.message?.let { ": $it" } ?: "") + ".", "Try again", false)
        }
        statusLabel.textContent = statusText
        primaryBtn.textContent = buttonText
        (primaryBtn.asDynamic()).disabled = disabled
    }

    panelRender = ::render
    render(updaterState)

    return section
}
