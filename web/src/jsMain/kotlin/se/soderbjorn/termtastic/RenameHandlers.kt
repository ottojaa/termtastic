/**
 * Inline rename handler for tab labels in the Termtastic web frontend.
 *
 * Pane title rename is handled by the toolkit: termtastic supplies
 * `AppShellSpec.paneRename` (which forwards to the lower-level
 * `PaneHeaderSpec.onRename`) and triggers the input swap on demand via
 * `AppShellHandle.beginPaneRename(paneId)`. The kebab "more" overflow
 * menu in `termtasticPaneActions` exposes a "Rename pane" item that
 * calls `beginPaneRename`. The toolkit's hover-arm gesture is *not*
 * enabled for pane headers (`PaneHeaderSpec.armRenameOnHover` defaults
 * `false`, and the flag isn't exposed through `AppShellSpec`), so
 * termtastic supplies the double-click trigger itself —
 * [installPaneTitleDoubleClickRename] routes a dblclick on a pane title
 * to the same `beginPaneRename`. The kebab item and the double-click are
 * the two entry points.
 *
 * Besides that installer this file carries the tab-label rename variant,
 * which still owns its own DOM swap because the tab strip isn't yet on
 * the toolkit primitive.
 *
 * @see startTabRename
 * @see installPaneTitleDoubleClickRename
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Starts an inline rename interaction for a tab label.
 *
 * Similar to [startRename] but operates on tab labels in the tab bar. Closes
 * any open tab menus, adds a "renaming" CSS class during editing, and sends
 * [WindowCommand.RenameTab] to the server on commit.
 *
 * @param labelEl the DOM element displaying the current tab label
 * @param tabId the unique tab identifier for the rename command
 * @see renderConfig
 */
fun startTabRename(labelEl: HTMLElement, tabId: String) {
    val current = labelEl.textContent ?: ""
    val parent = labelEl.parentElement ?: return
    parent.classList.add("renaming")
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "dt-tab-label-input"
    input.value = current
    parent.replaceChild(input, labelEl)
    input.focus()
    input.select()

    var settled = false
    fun cancel() {
        if (settled) return
        settled = true
        if (input.parentElement === parent) parent.replaceChild(labelEl, input)
        parent.classList.remove("renaming")
    }
    fun commit() {
        if (settled) return
        settled = true
        val newTitle = input.value.trim()
        if (newTitle.isEmpty() || newTitle == current) {
            if (input.parentElement === parent) parent.replaceChild(labelEl, input)
            parent.classList.remove("renaming")
            return
        }
        parent.classList.remove("renaming")
        launchCmd(WindowCommand.RenameTab(tabId = tabId, title = newTitle))
    }

    input.addEventListener("blur", { commit() })
    input.addEventListener("keydown", { ev ->
        val key = (ev as KeyboardEvent).key
        when (key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); cancel() }
        }
    })
    input.addEventListener("click", { ev -> ev.stopPropagation() })
    input.addEventListener("dblclick", { ev -> ev.stopPropagation() })
}

/** Guards against double-installation across re-entry. */
private var paneTitleRenameInstalled = false

/**
 * Wires double-click on a pane title to start an inline rename, mirroring
 * the double-click-to-rename gesture on tab labels.
 *
 * Called once from [bootViaToolkitShell] after `mountAppShell` (so
 * [appShellHandle] is set). The toolkit marks each renamable pane title
 * with `data-dt-pane-rename-target="<paneId>"` and stashes a start-rename
 * closure on it; we just trigger that closure via the toolkit's public
 * [se.soderbjorn.darkness.web.shell.AppShellHandle.beginPaneRename].
 *
 * A delegated **capture-phase** listener is used so it runs before the
 * pane's own bubble-phase dblclick (the toolkit's "raise/focus pane" on
 * `onFloatingFocused`, bound on `.dt-pane`); calling `stopPropagation`
 * then suppresses that raise, and `preventDefault` suppresses native
 * text selection — matching the toolkit's own rename gesture. Dblclicks
 * that aren't on a rename-target element fall through untouched, so
 * raise-on-dblclick still works everywhere else in the pane.
 */
internal fun installPaneTitleDoubleClickRename() {
    if (paneTitleRenameInstalled) return
    paneTitleRenameInstalled = true
    document.asDynamic().addEventListener("dblclick", { ev: Event ->
        val target = ev.target as? Element ?: return@addEventListener
        val renameTarget = target.closest("[data-dt-pane-rename-target]") ?: return@addEventListener
        val paneId = renameTarget.getAttribute("data-dt-pane-rename-target") ?: return@addEventListener
        ev.stopPropagation()
        ev.preventDefault()
        appShellHandle?.beginPaneRename(paneId)
    }, /* capture = */ true)
}
