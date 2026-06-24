import SwiftUI
import Client

/// Graphical "overview" mode for the Sessions screen (issue #44): a
/// miniaturised, read-only replica of the web/Electron tabs-and-panes
/// experience, ported from the Android `OverviewContent` composable.
///
/// A swipeable strip of tab chips sits at the top; below it a paging canvas
/// shows the active tab's panes laid out by their server-owned fractional
/// geometry, each hosting a live miniature (terminal / file-browser / git).
/// Selecting a chip or swiping between pages activates that tab server-side (so
/// the choice syncs across clients); tapping any pane drills into that pane's
/// existing full-screen route, from which the back gesture returns here.
///
/// The layout is a faithful spatial replica rather than a phone-native reflow:
/// panes keep their `(x, y, width, height)` fractions, mapped onto the full
/// content area (no aspect letterboxing) so they fill the space while keeping
/// relative positions. All projection logic lives in the shared
/// `OverviewBackingViewModel` (via `OverviewViewModel`); this file is the
/// SwiftUI front-end.
///
/// - SeeAlso: `OverviewViewModel`
/// - SeeAlso: `MiniTerminalRegistry`
struct OverviewView: View {
    var onOpenTerminal: (String) -> Void
    var onOpenFileBrowser: (String) -> Void
    var onOpenGit: (String) -> Void

    @State private var viewModel = OverviewViewModel()
    /// One registry for the whole overview: it owns the terminal miniatures'
    /// sockets/emulators so they survive tab swipes. Created on appear once a
    /// live client is available, torn down on disappear.
    @State private var registry: MiniTerminalRegistry?
    /// The paged `TabView`'s selection, kept in sync with the server's active
    /// tab in both directions (chip tap / external change ⇄ swipe).
    @State private var selection: String = ""

    var body: some View {
        // Measure the safe-area insets at the overview root, *outside* the paging
        // TabView. A `.page` TabView consumes/ignores the safe area for its pages,
        // so the GeometryReader inside `ExposeCanvas` reports `safeAreaInsets ==
        // 0` and a height that runs to the physical screen bottom (under the home
        // indicator). We read the real bottom inset here and hand it down so the
        // canvas can keep its panes above the home indicator.
        GeometryReader { rootGeo in
            Group {
                if !viewModel.isConnected {
                    placeholder("Disconnected")
                } else if viewModel.tabs.isEmpty {
                    placeholder("No tabs")
                } else {
                    VStack(spacing: 0) {
                        OverviewTabStrip(
                            tabs: viewModel.tabs,
                            activeTabId: viewModel.activeTabId,
                            onSelect: { viewModel.setActiveTab($0) }
                        )
                        TabView(selection: $selection) {
                            ForEach(viewModel.tabs, id: \.id) { tab in
                                ExposeCanvas(
                                    panes: tab.panes,
                                    bottomInset: rootGeo.safeAreaInsets.bottom,
                                    registry: registry,
                                    onOpenTerminal: onOpenTerminal,
                                    onOpenFileBrowser: onOpenFileBrowser,
                                    onOpenGit: onOpenGit
                                )
                                .tag(tab.id)
                            }
                        }
                        .tabViewStyle(.page(indexDisplayMode: .never))
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.background)
        .onAppear {
            if registry == nil, let client = ConnectionHolder.shared.client {
                registry = MiniTerminalRegistry(client: client)
            }
            viewModel.start()
        }
        .onDisappear {
            viewModel.stop()
            registry?.close()
            registry = nil
        }
        // Server → pager: an active-tab change (chip tap, swipe echo, or another
        // client) drives the page selection. No-op when already there, so the
        // two directions never fight.
        .onChange(of: viewModel.activeTabId) { _, newValue in
            if let newValue, newValue != selection { selection = newValue }
        }
        // Pager → server: settling on a new page activates that tab. Guarded so
        // the programmatic sync above does not echo back as a redundant command.
        .onChange(of: selection) { _, newValue in
            if !newValue.isEmpty, newValue != viewModel.activeTabId {
                viewModel.setActiveTab(newValue)
            }
        }
    }

    /// A centered dim caption for the disconnected / empty states.
    private func placeholder(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(Palette.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Tab strip

/// The top tab strip: a single horizontally-scrollable row of chips, the active
/// tab outlined in the accent colour (web parity) with its aggregate status dot
/// leading. Auto-scrolls to keep the active tab in view when it changes (e.g.
/// via a swipe or another client). Mirrors the Android `OverviewTabStrip`.
private struct OverviewTabStrip: View {
    let tabs: [Client.OverviewBackingViewModel.OverviewTab]
    let activeTabId: String?
    let onSelect: (String) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs, id: \.id) { tab in
                        OverviewTabChip(
                            title: tab.title,
                            isActive: tab.id == activeTabId,
                            aggregateState: tab.aggregateState
                        )
                        .id(tab.id)
                        .onTapGesture { onSelect(tab.id) }
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
            .background(Palette.background)
            .onChange(of: activeTabId) { _, id in
                guard let id else { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    proxy.scrollTo(id, anchor: .center)
                }
            }
        }
    }
}

/// One pill in the tab strip: an aggregate status dot (when working/waiting) and
/// the tab title, accent-outlined and tinted while active. Mirrors the Android
/// Material `FilterChip` styling.
private struct OverviewTabChip: View {
    let title: String
    let isActive: Bool
    let aggregateState: String?

    var body: some View {
        HStack(spacing: 4) {
            // The dot hides itself when idle, so include it only when it has
            // something to show — keeps idle chips tight (the web/Android chips
            // reserve the space, but a snug pill reads better on iOS).
            if aggregateState != nil {
                StatusDot(state: aggregateState, box: 12)
            }
            Text(title)
                .font(.subheadline)
                .lineLimit(1)
                .foregroundStyle(isActive ? Palette.headerAccent : Palette.textSecondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule().fill(isActive ? Palette.headerAccent.opacity(0.18) : Color.clear)
        )
        .overlay(
            Capsule().stroke(
                isActive ? Palette.headerAccent : Palette.textSecondary.opacity(0.4),
                lineWidth: isActive ? 2 : 1
            )
        )
        .contentShape(Capsule())
    }
}

// MARK: - Exposé canvas

/// Lays out a tab's panes by their fractional geometry mapped directly onto the
/// full available content area. Each pane's `(x, y, width, height)` fractions
/// are multiplied by the measured size, so panes fill the space (no aspect
/// letterboxing) while keeping their relative positions and proportions. Panes
/// arrive pre-sorted bottom-to-top by z, and the `ZStack` paints them in that
/// order. Mirrors the Android `ExposeCanvas`.
private struct ExposeCanvas: View {
    let panes: [Client.OverviewBackingViewModel.OverviewPane]
    /// The bottom safe-area inset, measured at the overview root (the paging
    /// TabView hides it from this view's own GeometryReader). Reserved at the
    /// bottom of the canvas so panes stay clear of the home indicator.
    let bottomInset: CGFloat
    let registry: MiniTerminalRegistry?
    let onOpenTerminal: (String) -> Void
    let onOpenFileBrowser: (String) -> Void
    let onOpenGit: (String) -> Void

    var body: some View {
        if panes.isEmpty {
            Text("No windows in this tab")
                .font(.system(size: 13))
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            // Inset the exposé by `margin` on every side so even the outermost
            // panes' full rounded border stays on-screen. The margin is folded
            // into the projection math rather than applied as a `.padding()` on
            // the `GeometryReader`: a padded GeometryReader still mapped the
            // fractions against the *outer* size, so the right/bottom panes (and,
            // shifted by the leading inset, the whole layout) overflowed the
            // canvas and their borders clipped at the screen edge. Subtracting
            // the margin from `geo.size` here pins every edge inside the canvas.
            let margin: CGFloat = 8
            GeometryReader { geo in
                let canvasW = geo.size.width - margin * 2
                let canvasH = geo.size.height - margin * 2 - bottomInset
                ZStack(alignment: .topLeading) {
                    ForEach(panes, id: \.leaf.id) { pane in
                        let maximized = pane.maximized
                        let x = maximized ? 0 : pane.x
                        let y = maximized ? 0 : pane.y
                        let w = maximized ? 1 : pane.width
                        let h = maximized ? 1 : pane.height
                        MiniPane(
                            pane: pane,
                            registry: registry,
                            onOpenTerminal: onOpenTerminal,
                            onOpenFileBrowser: onOpenFileBrowser,
                            onOpenGit: onOpenGit
                        )
                        .frame(width: canvasW * w, height: canvasH * h)
                        // Clip each miniature to its own frame. `.frame(width:height:)`
                        // sizes the frame but does NOT clip: `MiniPane`'s terminal
                        // content has a tall intrinsic height (one line per output
                        // row), so without this it overflows the frame and bleeds
                        // into the panes stacked below/around it — which read as
                        // "another pane's content showing in this pane" and "the
                        // right column only shows ~2 panes" (issue #57). Clipping to
                        // the frame keeps every pane's content inside its own border.
                        .clipped()
                        .offset(x: margin + canvasW * x, y: margin + canvasH * y)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            }
        }
    }
}

// MARK: - Mini pane

/// A single miniature pane: a themed, rounded card with a tiny title bar, the
/// type-specific live miniature, and a whole-card tap that drills into the
/// pane's full-screen route. The focused pane gets the accent outline, matching
/// the web's focused-pane treatment. Mirrors the Android `MiniPane`.
private struct MiniPane: View {
    let pane: Client.OverviewBackingViewModel.OverviewPane
    let registry: MiniTerminalRegistry?
    let onOpenTerminal: (String) -> Void
    let onOpenFileBrowser: (String) -> Void
    let onOpenGit: (String) -> Void

    /// The pane's content type, dispatched on the leaf's content (terminal is
    /// the default for `TerminalContent` and legacy null-content leaves).
    private var kind: LeafKind {
        if pane.leaf.content is Client.GitContent { return .git }
        if pane.leaf.content is Client.FileBrowserContent { return .fileBrowser }
        return .terminal
    }

    var body: some View {
        let focused = pane.isFocused
        VStack(spacing: 0) {
            titleBar(focused: focused)
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.surface)
        // Flatten title bar + content (each paints its own opaque background)
        // into a single layer before the rounded clip. Without this the nested
        // backgrounds rasterize separately and keep their square corners, so the
        // card's rounded corners read as "cut off". `compositingGroup` makes
        // `clipShape` round the whole card, content included.
        .compositingGroup()
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6).stroke(
                focused ? Palette.headerAccent : Palette.textSecondary.opacity(0.35),
                lineWidth: focused ? 2 : 1
            )
        )
        .padding(3)
        .contentShape(Rectangle())
        .onTapGesture { open() }
    }

    /// The compact title bar: status dot (when active), pane-type icon, title.
    private func titleBar(focused: Bool) -> some View {
        HStack(spacing: 3) {
            if pane.sessionState != nil {
                StatusDot(state: pane.sessionState, box: 12)
            }
            PaneIcon(kind: kind, floating: false, size: 12)
            Text(pane.leaf.title)
                .font(.system(size: 9))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.textPrimary : Palette.textSecondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 1)
    }

    /// The type-specific live miniature.
    @ViewBuilder
    private var content: some View {
        switch kind {
        case .fileBrowser:
            MiniFileBrowserPane(paneId: pane.leaf.id)
        case .git:
            MiniGitPane(paneId: pane.leaf.id)
        case .terminal, .empty:
            MiniTerminalPane(registry: registry, sessionId: pane.leaf.sessionId)
        }
    }

    /// Drill into the pane's existing full-screen route.
    private func open() {
        switch kind {
        case .fileBrowser: onOpenFileBrowser(pane.leaf.id)
        case .git: onOpenGit(pane.leaf.id)
        case .terminal, .empty: onOpenTerminal(pane.leaf.sessionId)
        }
    }
}

// MARK: - Terminal miniature

/// Read-only terminal miniature: fills the pane with the most recent output
/// lines at a legible monospace size, anchored to the bottom and growing upward
/// (older lines clip off the top), so you get a real sense of what the pane is
/// doing. A thin renderer — all socket/emulator lifecycle lives in the
/// overview-scoped `MiniTerminalRegistry`, which keeps one live emulator per
/// session across tab swipes, so swiping back renders instantly. Mirrors the
/// Android `MiniTerminalPane`.
///
/// CONSOLIDATION: this is the iOS "paint" end of the terminal-thumbnail pipeline
/// (Android analogue: `MiniTerminalPane`; web analogue: `renderThumbnail` in
/// `LinkThumbnailRenderer.kt`). The paint is irreducibly platform-specific
/// (SwiftUI bottom-aligned overlay here), but it leans on SwiftUI `Text` to
/// re-wrap each logical line to the pane width — the step web does by hand via
/// `wrapLine`, a candidate for a shared `client/commonMain` helper. See the
/// design note in `web/.../LinkThumbnailRenderer.kt`.
private struct MiniTerminalPane: View {
    let registry: MiniTerminalRegistry?
    let sessionId: String

    var body: some View {
        let theme = Palette.settings
        let bg = theme.map { Color(argb: $0.bg) } ?? Palette.background
        let fg = theme.map { Color(argb: $0.text) } ?? Palette.textPrimary
        // Resolve the line box synchronously from the *current* `sessionId` on
        // every render — the iOS analogue of the Android
        // `remember(sessionId) { registry.linesFor(sessionId) }`. `box(for:)` is
        // idempotent (one cached emulator entry per session, never re-opens the
        // socket) and the returned box is `@Observable`, so reading `.lines` here
        // registers a dependency and live feeds still re-render this view. Holding
        // the box in `@State` resolved asynchronously (`.onAppear`/`.task`) was
        // the overview's pane-content-mismatch bug: SwiftUI keys this view's
        // identity on the pane's `leaf.id` and reuses the instance when the pane
        // set re-sorts by z, but the stale `@State` box stayed bound to the
        // previous session, so a miniature showed another pane's output.
        let lines = registry?.box(for: sessionId).lines ?? []

        // Fill the slot with the theme background, then overlay the lines pinned
        // to the bottom. Using the background as the sizing base (it greedily
        // takes the whole offered slot) and the lines as a bottom-aligned overlay
        // is what actually pins the *newest* line to the bottom: a plain
        // `VStack(...).frame(maxHeight:.infinity, alignment:.bottom)` does not,
        // because the stack of Text rows has a tall minimum height (one row per
        // line), so it overflows the slot and the bottom (newest) rows get
        // clipped instead of the top (oldest) ones. The overlay grows upward past
        // the slot top and `.clipped()` trims that overflow — the iOS analogue of
        // the Android reverse-layout LazyColumn.
        bg
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(fg)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
            }
            .clipped()
    }
}

// MARK: - File-browser miniature

/// Read-only file-browser miniature: a compact replica of the file browser's
/// first screen (the root directory listing) — folders first then files, each a
/// single row with the same SF Symbol the full-screen `FileBrowserListView`
/// draws, only smaller. Reuses the shared `FileBrowserBackingViewModel` for
/// live listing updates. Mirrors the Android `MiniFileBrowserPane`.
private struct MiniFileBrowserPane: View {
    let paneId: String
    @State private var model = MiniFileBrowserModel()

    /// Max rows the miniature lists before it clips to the pane bounds.
    private let maxRows = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.entries.isEmpty {
                Text("\u{2026}")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.textSecondary)
            } else {
                ForEach(Array(model.entries.prefix(maxRows)), id: \.relPath) { entry in
                    HStack(spacing: 5) {
                        Image(systemName: entry.isDir ? "folder.fill" : "doc.text")
                            .font(.system(size: 9))
                            .foregroundStyle(entry.isDir ? Palette.headerAccent : Palette.textSecondary)
                            .frame(width: 12)
                        Text(entry.name)
                            .font(.system(size: 10))
                            .foregroundStyle(Palette.textPrimary)
                            .lineLimit(1)
                    }
                    .padding(.vertical, 1)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, 6)
        .padding(.vertical, 5)
        .background(Palette.background)
        .onAppear { model.start(paneId: paneId) }
        .onDisappear { model.stop() }
    }
}

// MARK: - Git miniature

/// Read-only git miniature: a compact replica of the git pane's first screen
/// (the changed-files list) — files grouped under uppercase directory headers,
/// each row carrying the same coloured status badge the full-screen
/// `GitListView` draws, only smaller. Reuses the shared `GitPaneBackingViewModel`
/// for live status updates. Mirrors the Android `MiniGitPane`.
private struct MiniGitPane: View {
    let paneId: String
    @State private var model = MiniGitModel()

    /// Max rows (headers + files) before the miniature clips to the pane bounds.
    private let maxRows = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.entries.isEmpty {
                Text(model.isLoading ? "\u{2026}" : "No changes")
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.textSecondary)
            } else {
                ForEach(miniGitRows(model.entries, budget: maxRows)) { row in
                    switch row {
                    case .header(let directory):
                        Text(directory.isEmpty ? "ROOT" : directory.uppercased())
                            .font(.system(size: 8, weight: .semibold))
                            .tracking(0.5)
                            .foregroundStyle(Palette.textSecondary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 3)
                            .padding(.bottom, 1)
                    case .file(let entry):
                        HStack(spacing: 5) {
                            GitStatusBadge(status: entry.status, size: 12)
                            Text(basename(entry.filePath))
                                .font(.system(size: 10))
                                .foregroundStyle(Palette.textPrimary)
                                .lineLimit(1)
                        }
                        .padding(.vertical, 1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, 6)
        .padding(.vertical, 5)
        .background(Palette.background)
        .onAppear { model.start(paneId: paneId) }
        .onDisappear { model.stop() }
    }

    private func basename(_ path: String) -> String {
        path.components(separatedBy: "/").last ?? path
    }
}

/// One row in the git miniature — a directory header or a changed file.
private enum MiniGitRow: Identifiable {
    case header(String)
    case file(Client.GitFileEntry)

    var id: String {
        switch self {
        case .header(let directory): return "h:\(directory)"
        case .file(let entry): return "f:\(entry.filePath)"
        }
    }
}

/// Flatten changed `entries` into directory-grouped rows (preserving the
/// server's order), capped at `budget` rows total (headers included) so the
/// thumbnail clips cleanly. Mirrors the Android `MiniGitPane`'s row-budget loop.
private func miniGitRows(_ entries: [Client.GitFileEntry], budget: Int) -> [MiniGitRow] {
    var order: [String] = []
    var grouped: [String: [Client.GitFileEntry]] = [:]
    for entry in entries {
        let dir = entry.directory
        if grouped[dir] == nil { order.append(dir) }
        grouped[dir, default: []].append(entry)
    }

    var rows: [MiniGitRow] = []
    var remaining = budget
    for dir in order {
        if remaining <= 0 { break }
        rows.append(.header(dir))
        remaining -= 1
        for entry in grouped[dir] ?? [] {
            if remaining <= 0 { break }
            rows.append(.file(entry))
            remaining -= 1
        }
    }
    return rows
}
