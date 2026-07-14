import SwiftUI
import Client

/// Host list screen — add, edit, delete saved servers and connect.
/// Mirrors the Android `HostsScreen` composable.
///
/// The screen is also the QR pairing entry point: the toolbar scanner (and the
/// `lunamux://pair` deep link relayed through `PendingPairingUri`) parses a
/// `PairingPayload`, saves or updates the host entry, and connects immediately
/// — scan → connected, with no approval dialog.
///
/// Unlike the workspace screens (tree, terminal, …), this screen renders
/// before any server connection exists, so no server-driven theme can apply
/// yet. It therefore uses native system colors and standard iOS list styling
/// instead of the `Palette` workspace theme.
struct HostsView: View {
    @Bindable var viewModel: HostsViewModel
    var onConnected: () -> Void
    var onOpenNews: () -> Void

    /// Roomier (iPad) vs compact (iPhone) sizing.
    @Environment(\.horizontalSizeClass) private var hSize

    /// Set when a long-press asks which address to use; only offered for
    /// entries that actually have a choice.
    @State private var addressPickerTarget: HostEntryLocal?
    @State private var editTarget: HostEntryLocal?
    @State private var showAddSheet = false
    @State private var showScanSheet = false
    @State private var deleteTarget: HostEntryLocal?

    /// Deep-linked pairing URIs parked by `LunamuxApp.onOpenURL`.
    private let pendingPairing = PendingPairingUri.shared

    var body: some View {
        baseView
            .modifier(HostsAlertsModifier(viewModel: viewModel, deleteTarget: $deleteTarget))
            .onChange(of: ConnectionHolder.shared.client != nil) { _, connected in
                if connected { onConnected() }
            }
            // Pairing deep link (system camera / browser → lunamux://pair).
            // `onAppear` catches a cold-launch link posted before this screen
            // existed; `onChange` catches one that arrives while it is up.
            // `consume()` clears the slot either way, so a re-render cannot
            // pair twice.
            .onAppear { consumePendingPairing() }
            .onChange(of: pendingPairing.uri) { _, uri in
                if uri != nil { consumePendingPairing() }
            }
    }

    /// Drain the pairing mailbox into the view model, if anything is waiting.
    private func consumePendingPairing() {
        if let uri = pendingPairing.consume() {
            viewModel.handlePairingUri(uri)
        }
    }

    /// Base list/empty-state view plus toolbar and sheets. Split out so the
    /// alerts (which SwiftUI's type checker struggles to resolve when chained
    /// on one body) live in a separate `ViewModifier`.
    @ViewBuilder
    private var baseView: some View {
        Group {
            if viewModel.hosts.isEmpty {
                emptyState
            } else {
                hostsList
            }
        }
        // Keep the list/empty state in a readable column on iPad instead of
        // stretching edge-to-edge, left-aligned so it lines up under the large
        // "Hosts" title rather than floating centred. A no-op on iPhone (< 700 pt).
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity, alignment: .leading)
        .safeAreaInset(edge: .bottom) {
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting: viewModel.connectingId == HostsViewModel.demoConnectingId,
                enabled: viewModel.connectingId == nil,
                onConnect: { viewModel.connectDemo() }
            )
        }
        .navigationTitle("Hosts")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NewsBellButton(action: onOpenNews)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showScanSheet = true } label: {
                    Label("Scan QR code", systemImage: "qrcode.viewfinder")
                }
                .tint(Palette.headerAccent)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddSheet = true } label: {
                    Label("Add Host", systemImage: "plus")
                }
                .tint(Palette.headerAccent)
            }
            // Shared info menu → support forum, website, legal pages. Mirrored
            // in the Sessions toolbar so both primary screens expose the same
            // links from the same place.
            ToolbarItem(placement: .topBarTrailing) {
                AboutMenu()
            }
        }
        .sheet(isPresented: $showAddSheet) {
            HostEditSheet(initial: nil) { label, host, port, _ in
                // A typed host has no candidates to carry: the sheet only shows
                // that section for entries that have some.
                viewModel.addHost(label: label, host: host, port: port)
                showAddSheet = false
            }
        }
        .sheet(isPresented: $showScanSheet) {
            // Dismiss first, then pair: the connect drives this screen's
            // spinner and, on success, the push to the tree — none of which
            // is visible under a presented sheet.
            QRScannerSheet { uri in
                showScanSheet = false
                viewModel.handlePairingUri(uri)
            }
        }
        .sheet(item: $addressPickerTarget) { entry in
            AddressPickerSheet(entry: entry) { address in
                addressPickerTarget = nil
                viewModel.connect(entry: entry, only: address)
            }
        }
        .sheet(item: $editTarget) { entry in
            HostEditSheet(initial: entry) { label, host, port, candidates in
                var updated = entry
                updated.label = label
                updated.host = host
                updated.port = port
                updated.candidates = candidates
                viewModel.updateHost(updated)
                editTarget = nil
            }
        }
    }

    /// Empty state. Pairing by QR is the primary path (scan → connected, no
    /// typing, no approval dialog); manual entry is demoted to a secondary
    /// action. Mirrors the Android `EmptyState` composable.
    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Hosts", systemImage: "qrcode.viewfinder")
        } description: {
            Text("On your Mac, in Lunamux, go to \"Settings > Server & Security… > Devices\" "
                 + "and tick \"Allow connections from other devices\" and then press "
                 + "\"Pair via QR Code\" - and then scan the code here.")
        } actions: {
            Button { showScanSheet = true } label: {
                Text("Scan QR code")
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.headerAccent)

            Button { showAddSheet = true } label: {
                Text("Add manually")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }

    private var hostsList: some View {
        List {
            ForEach(viewModel.hosts) { entry in
                HostRow(
                    entry: entry,
                    connecting: viewModel.connectingId == entry.id,
                    attemptingAddress: viewModel.connectingId == entry.id
                        ? viewModel.attemptingAddress : nil,
                    waitingForApproval: viewModel.waitingForApproval,
                    enabled: viewModel.connectingId == nil,
                    onConnect: { viewModel.connect(entry: entry) },
                    // No choice to offer for a one-address host, so no picker.
                    onPickAddress: entry.allAddresses.count > 1
                        ? { addressPickerTarget = entry } : nil,
                    onEdit: { editTarget = entry },
                    onDelete: { deleteTarget = entry }
                )
            }
        }
        .listStyle(.insetGrouped)
    }
}

// MARK: - Demo Footer

/// Discreet, bottom-pinned entry into the built-in demo. Tapping it connects
/// to the shared client's in-process demo simulation — instant, offline, and
/// stateless, so it carries no edit/delete affordances.
///
/// Rendered as a single muted row beneath a hairline divider, staying out of the
/// way of the user's own servers. External links (support forum, website, legal
/// pages) now live in the top bar's `AboutMenu` rather than here, keeping this
/// footer to the one demo affordance. Mirrors the Android `DemoFooter`
/// composable.
private struct DemoFooter: View {
    let connecting: Bool
    let enabled: Bool
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                // Live demo — the footer's sole affordance.
                Button(action: { if enabled { onConnect() } }) {
                    HStack(spacing: 6) {
                        if connecting {
                            ProgressView()
                                .scaleEffect(0.7)
                        } else {
                            Image(systemName: "play.circle")
                                .font(.system(size: 13))
                        }
                        Text("Try the live demo")
                            .font(.footnote)
                    }
                    .foregroundStyle(.secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel("Try the live demo, no server needed")

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 6)
        }
        // Opaque background that matches the list base (black in dark mode)
        // rather than the lighter `.bar` material. `.bar` filled the bottom
        // safe area with a visibly lighter band, reading as a big chunk of
        // padding beneath the text; a matching colour makes that region blend
        // into the screen. (A *transparent* background instead caused SwiftUI
        // to ghost the footer up under the status bar, so an opaque fill is
        // required.)
        .background(Color(.systemGroupedBackground))
    }
}

// MARK: - Host Row

private struct HostRow: View {
    let entry: HostEntryLocal
    let connecting: Bool
    /// The address currently being tried, or nil when not connecting.
    let attemptingAddress: String?
    let waitingForApproval: Bool
    let enabled: Bool
    let onConnect: () -> Void
    /// Invoked on long-press for entries with more than one address; nil for
    /// entries with nothing to choose between, which get no picker rather than
    /// a menu of one.
    let onPickAddress: (() -> Void)?
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        Button(action: { if enabled { onConnect() } }) {
            HStack(spacing: hSize.scaled(12)) {
                TerminalGlyph()
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.label)
                        .font(hSize.pick(.headline, .title3))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    // While connecting, this line names the address actually
                    // being tried rather than the stored preferred one: the
                    // walk can spend the full per-candidate budget on each dead
                    // address, and a spinner over an unchanging host:port makes
                    // that look like a hang instead of progress.
                    //
                    // verbatim: interpolating an Int32 through Text's
                    // LocalizedStringKey path runs it through a locale-aware
                    // number formatter, rendering ports like "8 443".
                    Text(verbatim: attemptingAddress.map { "Trying \($0)…" }
                        ?? "\(entry.host):\(entry.port)")
                        .font(hSize.pick(.subheadline, .body))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if connecting {
                    if waitingForApproval {
                        Text("Waiting for approval…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    AttemptProgressView(attemptingAddress: attemptingAddress)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { onDelete() } label: {
                Label("Delete", systemImage: "trash")
            }
            Button { onEdit() } label: {
                Label("Edit", systemImage: "pencil")
            }
            .tint(.blue)
        }
        .contextMenu {
            // Long-press is where the address picker lives. Only offered when
            // there is a choice — see `onPickAddress`.
            if let onPickAddress {
                Button { onPickAddress() } label: {
                    Label("Connect using…", systemImage: "network")
                }
            }
            Button { onEdit() } label: {
                Label("Edit", systemImage: "pencil")
            }
            Button(role: .destructive) { onDelete() } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

// MARK: - Terminal Glyph (matches Android's TerminalGlyph Canvas)

private struct TerminalGlyph: View {
    var body: some View {
        Canvas { context, size in
            let w = size.width
            let px = w / 16.0
            let stroke = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)
            let tint = Color.secondary.opacity(0.7)

            // Rounded rect
            let rect = CGRect(x: 1*px, y: 2*px, width: 14*px, height: 12*px)
            context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect), with: .color(tint), style: stroke)

            // Chevron
            var chevron = Path()
            chevron.move(to: CGPoint(x: 4*px, y: 7*px))
            chevron.addLine(to: CGPoint(x: 6*px, y: 5*px))
            chevron.addLine(to: CGPoint(x: 4*px, y: 3*px))
            context.stroke(chevron, with: .color(tint), style: stroke)

            // Cursor line
            var line = Path()
            line.move(to: CGPoint(x: 7*px, y: 7*px))
            line.addLine(to: CGPoint(x: 11*px, y: 7*px))
            context.stroke(line, with: .color(tint), style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
        }
        .frame(width: 16, height: 16)
        .accessibilityLabel("Terminal")
    }
}

// MARK: - Host Edit Sheet

private struct HostEditSheet: View {
    let initial: HostEntryLocal?
    let onSave: (String, String, Int32, [String]) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var label: String
    @State private var host: String
    @State private var portText: String
    /// Editable copy of the entry's saved fallback endpoints. Scanning a QR
    /// merges new networks in (`HostEntry.mergeCandidates`), and this is where
    /// they can be pruned again — a laptop that has visited several offices
    /// accumulates addresses that are dead everywhere else, and each one costs
    /// up to 12s of the sequential connect walk.
    @State private var candidates: [String]
    @FocusState private var focusedField: Field?

    private enum Field { case label, host, port }

    init(initial: HostEntryLocal?, onSave: @escaping (String, String, Int32, [String]) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _label = State(initialValue: initial?.label ?? "")
        _host = State(initialValue: initial?.host ?? "")
        // New hosts default to the port every Lunamux server listens on.
        _portText = State(initialValue: initial.map { String($0.port) }
            ?? String(ConstantsKt.SERVER_TLS_PORT))
        _candidates = State(initialValue: initial?.candidates ?? [])
    }

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespaces).isEmpty
            && !host.trimmingCharacters(in: .whitespaces).isEmpty
            && Int32(portText) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $label)
                        .focused($focusedField, equals: .label)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .host }
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("A name for this server, shown in the host list.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                Section {
                    TextField("Host", text: $host)
                        .focused($focusedField, equals: .host)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .port }
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("Hostname or IP address of your Mac running Lunamux.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                Section {
                    TextField("Port", text: $portText)
                        .focused($focusedField, equals: .port)
                        .keyboardType(.numberPad)
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("Lunamux servers listen on port \(String(ConstantsKt.SERVER_TLS_PORT)) by default.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                if !candidates.isEmpty {
                    Section {
                        // Swipe-to-delete rather than a per-row button: it is
                        // the iOS idiom for a Form list, and it keeps each row
                        // free for the address itself.
                        ForEach(candidates.indices, id: \.self) { index in
                            TextField("Address", text: $candidates[index])
                                .keyboardType(.URL)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .foregroundStyle(Palette.textPrimary)
                        }
                        .onDelete { candidates.remove(atOffsets: $0) }
                    } header: {
                        Text("Other addresses")
                            .foregroundStyle(Palette.textSecondary)
                    } footer: {
                        Text("Tried in order when the address above doesn't answer. "
                             + "Scanning a QR code adds the addresses it carries; swipe to "
                             + "delete any that no longer exist, since each dead one slows "
                             + "down connecting.")
                            .foregroundStyle(Palette.textSecondary)
                    }
                    .listRowBackground(Palette.surface)
                }
            }
            // Theme the sheet to match the rest of the app: hide the system
            // grouped-list backdrop, paint the palette background behind it, and
            // tint the cursor / Cancel & Save actions with the theme accent.
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .tint(Palette.headerAccent)
            .navigationTitle(initial == nil ? "Add Host" : "Edit Host")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Palette.headerAccent)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let port = Int32(portText), canSave {
                            onSave(label.trimmingCharacters(in: .whitespaces),
                                   host.trimmingCharacters(in: .whitespaces),
                                   port,
                                   // Blank rows are how a user clears one by
                                   // hand; treat that the same as a delete
                                   // rather than saving an empty candidate the
                                   // connect walk would choke on.
                                   candidates
                                       .map { $0.trimmingCharacters(in: .whitespaces) }
                                       .filter { !$0.isEmpty })
                        }
                    }
                    .disabled(!canSave)
                    .tint(Palette.headerAccent)
                }
            }
            .onAppear {
                if initial == nil { focusedField = .label }
            }
        }
        // On iPad a `.medium` detent renders as a small floating card; present
        // the full form sheet there instead. iPhone keeps the half-sheet.
        .presentationDetents(hSize.pick([.medium, .large], [.large]))
    }
}

/// Long-press escape hatch: connect using one specific address rather than
/// walking them.
///
/// Tapping a host is the normal path and asks nothing — it tries the preferred
/// address, falls back through the rest, and promotes whatever answered. That
/// walk costs the per-candidate budget for every dead address, though, and the
/// phone cannot know which network it is on. When the user does know, this
/// skips straight to the right one. Deliberately not the default: it is an
/// override for the case the automatic path handles slowly, not a question
/// worth asking on every connect.
///
/// Only presented for entries with more than one address, so it never offers a
/// choice of one. Mirrors the Android `AddressPickerDialog`.
private struct AddressPickerSheet: View {
    let entry: HostEntryLocal
    /// Invoked with the chosen `host[:port]` string.
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(entry.allAddresses.enumerated()), id: \.element) { index, address in
                        Button { onPick(address) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: address)
                                    .foregroundStyle(Palette.textPrimary)
                                if index == 0 {
                                    Text("Last address that worked — what tapping the host tries first")
                                        .font(.caption)
                                        .foregroundStyle(Palette.textSecondary)
                                }
                            }
                        }
                    }
                    .listRowBackground(Palette.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .tint(Palette.headerAccent)
            .navigationTitle("Connect using")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Palette.headerAccent)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Progress ring for one connect attempt.
///
/// Determinate rather than a spinner: each attempt has a known budget
/// (`CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS`), so the ring can
/// show how much of it is left instead of merely asserting that something is
/// happening. It restarts whenever `attemptingAddress` changes, which is also
/// the signal that the walk moved on to the next address.
///
/// Falls back to an indeterminate spinner before the first attempt is
/// reported, when there is genuinely no deadline to count against.
private struct AttemptProgressView: View {
    let attemptingAddress: String?
    @State private var progress: Double = 0

    /// Seconds each attempt is allowed, read from the shared connector so the
    /// ring cannot drift from the real deadline.
    private var budget: Double {
        Double(Client.CandidateConnector.shared.DEFAULT_PER_CANDIDATE_TIMEOUT_MS) / 1000
    }

    var body: some View {
        Group {
            if attemptingAddress == nil {
                ProgressView().scaleEffect(0.8)
            } else {
                ProgressView(value: progress)
                    .progressViewStyle(.circular)
                    .scaleEffect(0.8)
            }
        }
        .onChange(of: attemptingAddress) {
            guard attemptingAddress != nil else { return }
            progress = 0
            withAnimation(.linear(duration: budget)) { progress = 1 }
        }
    }
}

// MARK: - Alerts

/// All four alert modifiers (delete confirmation, generic connection error,
/// cert-changed re-pair prompt, re-pair result) extracted out of
/// `HostsView.body`. SwiftUI's type checker times out when this many `.alert`
/// modifiers chain off the same body, so they live here as a single
/// `ViewModifier`.
private struct HostsAlertsModifier: ViewModifier {
    @Bindable var viewModel: HostsViewModel
    @Binding var deleteTarget: HostEntryLocal?

    func body(content: Content) -> some View {
        content
            .alert("Delete host?", isPresented: .init(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            )) {
                Button("Cancel", role: .cancel) { deleteTarget = nil }
                Button("Delete", role: .destructive) {
                    if let entry = deleteTarget {
                        viewModel.deleteHost(id: entry.id)
                        deleteTarget = nil
                    }
                }
            } message: {
                if let entry = deleteTarget {
                    Text("\"\(entry.label)\" will be removed from this device.")
                }
            }
            .alert("Connection failed", isPresented: .init(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { viewModel.errorMessage = nil }
            } message: {
                if let msg = viewModel.errorMessage {
                    Text(msg)
                }
            }
            .alert("Server certificate changed", isPresented: .init(
                get: { viewModel.pinMismatchEntry != nil },
                set: { if !$0 { viewModel.pinMismatchEntry = nil } }
            )) {
                // Mirrors Android's PinMismatchDialog (HostsScreen.kt:511).
                Button("Re-pair") {
                    if let e = viewModel.pinMismatchEntry { viewModel.repairPin(e) }
                }
                Button("Forget", role: .destructive) {
                    if let e = viewModel.pinMismatchEntry { viewModel.forgetHost(e) }
                }
                Button("Cancel", role: .cancel) { viewModel.pinMismatchEntry = nil }
            } message: {
                if let e = viewModel.pinMismatchEntry {
                    Text(pinMismatchMessage(for: e))
                }
            }
            // Mirrors Android's RePairDialog. Offers the connect that the scan
            // implied, so skipping the auto-connect costs the user nothing.
            .alert(
                viewModel.rePairResult.map { "\($0.entry.label) updated" } ?? "Updated",
                isPresented: .init(
                    get: { viewModel.rePairResult != nil },
                    set: { if !$0 { viewModel.rePairResult = nil } }
                )
            ) {
                Button("Connect") {
                    if let r = viewModel.rePairResult {
                        viewModel.rePairResult = nil
                        viewModel.connect(entry: r.entry)
                    }
                }
                Button("Done", role: .cancel) { viewModel.rePairResult = nil }
            } message: {
                if let r = viewModel.rePairResult {
                    Text(rePairMessage(for: r))
                }
            }
    }

    /// Explains what a re-pair scan actually changed — see `RePairResult` for
    /// why saying so matters.
    ///
    /// - Parameter result: the merged entry and how many addresses it gained.
    /// - Returns: the alert body text.
    private func rePairMessage(for result: RePairResult) -> String {
        let label = result.entry.label
        switch result.added {
        case 0:
            return "This code carried no addresses that \"\(label)\" didn't already know, "
                + "so nothing changed."
        case 1:
            return "1 new address was added. \"\(label)\" can now be reached on this network "
                + "as well as the ones it already knew."
        default:
            return "\(result.added) new addresses were added. \"\(label)\" can now be reached "
                + "on this network as well as the ones it already knew."
        }
    }

    private func pinMismatchMessage(for entry: HostEntryLocal) -> String {
        "The server at \"\(entry.label)\" (\(entry.host):\(entry.port)) is presenting a different certificate than the one you paired with. "
            + "This could mean the server was reinstalled, or someone is trying to intercept your connection.\n\n"
            + "Re-pair if you trust the new certificate; Forget to remove the host."
    }
}
