/* HotkeysSidebarContent.kt (jsMain)
 *
 * Body factory for termtastic's "Keyboard shortcuts" right-sidebar (the
 * toolkit-supplied [se.soderbjorn.darkness.web.settings.buildHotkeysSidebar]
 * slot, wired via `AppShellSpec.hotkeysContent` in
 * [bootViaToolkitShell] and opened through
 * `AppShellHandle.openHotkeysSidebar`).
 *
 * This is termtastic's single keyboard-shortcut reference: every shortcut
 * is listed with an availability badge — **Both** (desktop app + browser),
 * **Desktop** (bundled Electron app only) or **Web** (browser only) — and,
 * for the one shortcut whose chord differs between runtimes (positional tab
 * switching), both chords are shown side by side and labelled. The toolkit
 * owns the sidebar chrome (header, close, slide-in); this file only builds
 * the inner body.
 *
 * When a new chord is wired anywhere in termtastic's web frontend, add a
 * corresponding [HotkeyRow] here so the reference stays accurate.
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.hotkey.Hotkey
import se.soderbjorn.darkness.web.hotkey.StandardHotkeys
import se.soderbjorn.darkness.web.hotkey.tabSwitchHotkeyEntry
import se.soderbjorn.darkness.web.hotkey.toChordLabel
import se.soderbjorn.darkness.web.hotkey.webTabSwitchHotkeyEntry

/**
 * Where a shortcut is available. Derived per row from which of the two
 * runtime chords are present / whether they match; drives the row badge.
 */
private enum class HotkeyScope { BOTH, DESKTOP, WEB }

/**
 * One shortcut row in the Hotkeys sidebar.
 *
 * @property label       human-readable action name.
 * @property desktopChord cap labels for the bundled Electron desktop app, or
 *   `null` when the shortcut isn't available there.
 * @property webChord     cap labels for the browser, or `null` when the
 *   shortcut isn't available there.
 */
private class HotkeyRow(
    val label: String,
    val desktopChord: List<String>?,
    val webChord: List<String>?,
)

/** A titled group of [HotkeyRow]s, rendered as one section. */
private class HotkeyGroupModel(val title: String, val rows: List<HotkeyRow>)

/** Row available identically in both runtimes (same chord). */
private fun bothRow(label: String, chord: List<String>) =
    HotkeyRow(label, desktopChord = chord, webChord = chord)

/** Row available in both runtimes but with a different chord in each. */
private fun splitRow(label: String, desktop: List<String>, web: List<String>) =
    HotkeyRow(label, desktopChord = desktop, webChord = web)

/** Row available only in the bundled Electron desktop app. */
private fun desktopOnlyRow(label: String, chord: List<String>) =
    HotkeyRow(label, desktopChord = chord, webChord = null)

/**
 * The full shortcut model, grouped for display. Chords are built from the
 * same [StandardHotkeys] / entry helpers the live bindings use, so the
 * reference can't drift from the actual chords.
 *
 * @return the ordered groups shown in the sidebar.
 */
private fun hotkeyGroups(): List<HotkeyGroupModel> = listOf(
    HotkeyGroupModel(
        "Windows",
        listOf(
            // Ctrl+Opt+Arrow spatially focuses the pane in that direction.
            bothRow("Focus window left", StandardHotkeys.PreviousPane.toChordLabel()),
            bothRow("Focus window right", StandardHotkeys.NextPane.toChordLabel()),
            bothRow("Focus window up", StandardHotkeys.FocusPaneUp.toChordLabel()),
            bothRow("Focus window down", StandardHotkeys.FocusPaneDown.toChordLabel()),
        ),
    ),
    HotkeyGroupModel(
        "Tabs",
        listOf(
            bothRow("Previous tab", StandardHotkeys.PreviousTab.toChordLabel()),
            bothRow("Next tab", StandardHotkeys.NextTab.toChordLabel()),
            // The only chord that differs by runtime: a real browser reserves
            // plain Cmd/Ctrl+digit for its own tabs, so the web build adds an
            // Alt/Option modifier.
            splitRow(
                "Switch to tab 1–9 (9 = last)",
                desktop = tabSwitchHotkeyEntry().chord,
                web = webTabSwitchHotkeyEntry().chord,
            ),
        ),
    ),
    HotkeyGroupModel(
        "Dialogs",
        listOf(
            bothRow("Confirm / submit", listOf("⏎")),
            bothRow("Dismiss / cancel", listOf("Esc")),
        ),
    ),
    HotkeyGroupModel(
        "App",
        listOf(
            // OS-level global hotkey owned by the desktop app (ElectronMain):
            // summons the window from anywhere, or hides it if it's already
            // frontmost. Not available to a browser tab.
            desktopOnlyRow("Summon / hide window", summonChord()),
        ),
    ),
)

/**
 * Chord for the desktop app's global summon/hide hotkey. Mirrors
 * `SUMMON_ACCELERATOR` ("Control+Alt+Command+Space") in the Electron main
 * process, rendered with the current platform's modifier glyphs.
 */
private fun summonChord(): List<String> =
    Hotkey(key = " ", ctrl = true, alt = true, meta = true).toChordLabel()

/**
 * Open the dedicated Hotkeys sidebar.
 *
 * Routes through the toolkit's [se.soderbjorn.darkness.web.shell.AppShellHandle],
 * which animates any other right-side panel closed first and mounts the
 * [buildHotkeysSidebarContent] body. Shared entry point for the App Settings
 * "Hotkeys" button and the Electron "Keyboard Shortcuts" menu item (the
 * latter via the `show-hotkeys` IPC routed in `main.kt`). No-op until the
 * shell has mounted ([appShellHandle] is null before then).
 *
 * @see buildHotkeysSidebarContent
 */
fun openHotkeysSidebar() {
    appShellHandle?.openHotkeysSidebar()
}

/**
 * Build the body element for the Hotkeys sidebar.
 *
 * Wired via `AppShellSpec.hotkeysContent` in [bootViaToolkitShell]; invoked
 * each time the sidebar opens so chords reflect the current platform.
 *
 * @return the freshly-built body `<div>`.
 * @see openHotkeysSidebar
 */
fun buildHotkeysSidebarContent(): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.className = "termtastic-hotkeys-body"

    val intro = document.createElement("p") as HTMLElement
    intro.className = "termtastic-hotkeys-intro"
    intro.textContent = "Window and tab shortcuts work even when a terminal is focused."
    container.appendChild(intro)

    for (group in hotkeyGroups()) {
        container.appendChild(buildGroupSection(group))
    }

    container.appendChild(buildLegend())
    return container
}

/** Build one titled group section (header + rows). */
private fun buildGroupSection(group: HotkeyGroupModel): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "termtastic-hotkeys-group"

    val title = document.createElement("h3") as HTMLElement
    title.className = "termtastic-hotkeys-group-title"
    title.textContent = group.title
    section.appendChild(title)

    val list = document.createElement("div") as HTMLElement
    list.className = "termtastic-hotkeys-list"
    for (row in group.rows) list.appendChild(buildRow(row))
    section.appendChild(list)

    return section
}

/**
 * Build one shortcut row: label on the left, chord(s) + availability badge
 * on the right.
 *
 * When the desktop and web chords match, the chord is shown once with a
 * "Both" badge. When they differ, both chords are stacked, each tagged with
 * its runtime. A desktop- or web-only shortcut shows its single chord with
 * the matching badge.
 */
private fun buildRow(row: HotkeyRow): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "termtastic-hotkeys-row"

    val label = document.createElement("span") as HTMLElement
    label.className = "termtastic-hotkeys-label"
    label.textContent = row.label
    el.appendChild(label)

    val right = document.createElement("div") as HTMLElement
    right.className = "termtastic-hotkeys-right"

    val desktop = row.desktopChord
    val web = row.webChord
    when {
        desktop != null && web != null && desktop == web -> {
            right.appendChild(buildChord(desktop))
            right.appendChild(buildBadge(HotkeyScope.BOTH))
        }
        desktop != null && web != null -> {
            // Differing chords — show each labelled with its runtime.
            right.appendChild(buildTaggedChord(desktop, HotkeyScope.DESKTOP))
            right.appendChild(buildTaggedChord(web, HotkeyScope.WEB))
        }
        desktop != null -> {
            right.appendChild(buildChord(desktop))
            right.appendChild(buildBadge(HotkeyScope.DESKTOP))
        }
        web != null -> {
            right.appendChild(buildChord(web))
            right.appendChild(buildBadge(HotkeyScope.WEB))
        }
    }

    el.appendChild(right)
    return el
}

/** A chord line paired with a runtime tag (used for the split tab-switch row). */
private fun buildTaggedChord(caps: List<String>, scope: HotkeyScope): HTMLElement {
    val line = document.createElement("div") as HTMLElement
    line.className = "termtastic-hotkeys-tagged"
    line.appendChild(buildChord(caps))
    line.appendChild(buildBadge(scope))
    return line
}

/** Render a chord as a row of keycap pills. */
private fun buildChord(caps: List<String>): HTMLElement {
    val chord = document.createElement("span") as HTMLElement
    chord.className = "termtastic-hotkeys-chord"
    for (cap in caps) {
        val capEl = document.createElement("kbd") as HTMLElement
        capEl.className = "termtastic-hotkeys-cap"
        capEl.textContent = cap
        chord.appendChild(capEl)
    }
    return chord
}

/** Render the availability badge for a [scope]. */
private fun buildBadge(scope: HotkeyScope): HTMLElement {
    val badge = document.createElement("span") as HTMLElement
    val (text, mod) = when (scope) {
        HotkeyScope.BOTH -> "Both" to "both"
        HotkeyScope.DESKTOP -> "Desktop" to "desktop"
        HotkeyScope.WEB -> "Web" to "web"
    }
    badge.className = "termtastic-hotkeys-badge termtastic-hotkeys-badge--$mod"
    badge.textContent = text
    return badge
}

/** Small legend explaining the three availability badges. */
private fun buildLegend(): HTMLElement {
    val legend = document.createElement("div") as HTMLElement
    legend.className = "termtastic-hotkeys-legend"
    legend.appendChild(buildBadge(HotkeyScope.BOTH))
    appendLegendText(legend, "Desktop app & browser")
    legend.appendChild(buildBadge(HotkeyScope.DESKTOP))
    appendLegendText(legend, "Bundled desktop app only")
    legend.appendChild(buildBadge(HotkeyScope.WEB))
    appendLegendText(legend, "Browser only")
    return legend
}

private fun appendLegendText(parent: HTMLElement, text: String) {
    val span = document.createElement("span") as HTMLElement
    span.className = "termtastic-hotkeys-legend-text"
    span.textContent = text
    parent.appendChild(span)
}
