/* AutoUpdater.kt
 * In-app auto-update lifecycle for the Lunamux Electron main process.
 *
 * A Kotlin/JS port of the acolite `app/updater.ts` pattern: it configures
 * electron-updater's [autoUpdater] singleton (declared in
 * [ElectronUpdaterExternals.kt]), relays each update lifecycle event to the
 * renderer over IPC, and exposes the three control entry points
 * ([checkForUpdates], [downloadUpdate], [quitAndInstallUpdate]) that the
 * renderer's Updates panel drives.
 *
 * The renderer half lives in `web/.../AutoUpdaterPanel.kt`; the IPC channel
 * names shared across main ↔ preload ↔ renderer are defined once in
 * [UpdateChannels]. Wiring (IPC handler registration, the startup check, and
 * the "Check for Updates…" menu item) is in [main] / `buildAppMenu`.
 */
package se.soderbjorn.lunamux.electron

/**
 * IPC channel names for the auto-update feature, shared conceptually with
 * `electron/preload.js` and the renderer (`web/.../AutoUpdaterPanel.kt`),
 * which use the same string literals. Grouped here so the main-process side
 * has a single source of truth.
 *
 * The `update:*` command channels flow renderer → main (via `ipcRenderer.invoke`
 * ⇄ `ipcMain.handle`); the remaining channels flow main → renderer (via
 * `webContents.send` ⇄ `ipcRenderer.on`). [SHOW_PANEL] is a main → renderer
 * signal that asks the renderer to open the Updates panel (fired by the
 * "Check for Updates…" menu item).
 */
object UpdateChannels {
    // ── renderer → main (commands) ──────────────────────────────────
    /** Ask the main process to check the provider for a newer version. */
    const val CHECK = "update:check"

    /** Ask the main process to download the available update. */
    const val DOWNLOAD = "update:download"

    /** Ask the main process to quit, install the update, and relaunch. */
    const val QUIT_AND_INSTALL = "update:quit-and-install"

    // ── main → renderer (lifecycle events) ──────────────────────────
    /** A check has started. */
    const val CHECKING = "update:checking"

    /** A newer version is available (payload: `{ version }`). */
    const val AVAILABLE = "update:available"

    /** No newer version is available. */
    const val NOT_AVAILABLE = "update:not-available"

    /** Download progress (payload: `{ percent, transferred, total, bytesPerSecond }`). */
    const val PROGRESS = "update:progress"

    /** The update has finished downloading and is ready to install (payload: `{ version }`). */
    const val DOWNLOADED = "update:downloaded"

    /** An update operation failed (payload: `{ message }`). */
    const val ERROR = "update:error"

    // ── main → renderer (navigation) ────────────────────────────────
    /** Ask the renderer to open the Updates panel (from the Help menu item). */
    const val SHOW_PANEL = "show-updates-panel"
}

/**
 * Guards one-time listener binding in [initAutoUpdater]. electron-updater's
 * `autoUpdater` is a process-wide singleton, so its lifecycle listeners must
 * be attached exactly once regardless of how many times a BrowserWindow is
 * (re)created.
 */
private var updaterListenersBound = false

/**
 * Configures [autoUpdater] and binds its lifecycle listeners once, forwarding
 * each event to the current renderer.
 *
 * Called once from [main] during startup. Because a BrowserWindow can be
 * rebuilt (e.g. the title-bar-style toggle destroys and recreates it), events
 * are sent to whatever window [currentWindow] resolves at fire time rather
 * than to a window captured here — so update progress keeps reaching the live
 * renderer across a window swap.
 *
 * @param currentWindow supplier of the live main [BrowserWindow], or `null`
 *   when none exists yet; provided by [main] where the `mainWindow` global is
 *   in scope. Events are dropped when it yields `null` or a destroyed window.
 * @see UpdateChannels for the channels each event maps to.
 */
fun initAutoUpdater(currentWindow: () -> BrowserWindow?) {
    // Never download without an explicit user action from the Updates panel.
    autoUpdater.autoDownload = false

    if (updaterListenersBound) return
    updaterListenersBound = true

    fun send(channel: String, payload: dynamic = null) {
        val w = currentWindow() ?: return
        if (!w.isDestroyed()) w.webContents.send(channel, payload)
    }

    autoUpdater.on("checking-for-update") { send(UpdateChannels.CHECKING) }

    autoUpdater.on("update-available") { info ->
        val payload = js("({})")
        payload.version = info?.version
        send(UpdateChannels.AVAILABLE, payload)
    }

    autoUpdater.on("update-not-available") { send(UpdateChannels.NOT_AVAILABLE) }

    autoUpdater.on("download-progress") { progress ->
        val payload = js("({})")
        payload.percent = progress?.percent
        payload.transferred = progress?.transferred
        payload.total = progress?.total
        payload.bytesPerSecond = progress?.bytesPerSecond
        send(UpdateChannels.PROGRESS, payload)
    }

    autoUpdater.on("update-downloaded") { info ->
        val payload = js("({})")
        payload.version = info?.version
        send(UpdateChannels.DOWNLOADED, payload)
    }

    autoUpdater.on("error") { err ->
        val payload = js("({})")
        payload.message = (err?.message as? String) ?: "Update failed"
        send(UpdateChannels.ERROR, payload)
    }
}

/**
 * Triggers an update check. No-ops in dev/demo launches: an unpackaged app has
 * no `app-update.yml`, so electron-updater would throw — the acolite original
 * lacked this guard and errored on every dev run.
 *
 * Called by the `update:check` IPC handler, the "Check for Updates…" menu item,
 * and once silently at startup (see [main]). Failures (offline, etc.) surface
 * to the renderer via the `error` event, so the promise rejection is swallowed
 * here to avoid an unhandled rejection.
 */
fun checkForUpdates() {
    if (!app.isPackaged) return
    try {
        autoUpdater.checkForUpdates().catch { /* surfaced via the "error" event */ }
    } catch (_: Throwable) {
        // Synchronous failures (misconfiguration) are non-fatal to the app.
    }
}

/**
 * Starts downloading the available update. Called by the `update:download` IPC
 * handler when the user clicks "Download" in the Updates panel. Progress and
 * completion are reported via the `download-progress` / `update-downloaded`
 * events bound in [initAutoUpdater].
 */
fun downloadUpdate() {
    try {
        autoUpdater.downloadUpdate().catch { /* surfaced via the "error" event */ }
    } catch (_: Throwable) {
    }
}

/**
 * Quits, installs the downloaded update, and relaunches.
 *
 * Called by the `update:quit-and-install` IPC handler after the caller has
 * cleared the app's quit-confirmation gate (an update install is an
 * already-confirmed quit). Deferred via [setImmediate] so the IPC reply is
 * delivered before the app tears down.
 *
 * @see AutoUpdaterApi.quitAndInstall
 */
fun quitAndInstallUpdate() {
    setImmediate {
        autoUpdater.quitAndInstall()
    }
}
