import Foundation
import Observation
import Client

/// Manages the hosts list and connection state. Observes the shared
/// `LocalRepository` (the single `local_state.json` store) for persistence and
/// wraps `ConnectionHolder` for WebSocket lifecycle.
///
/// The host list is mirrored from `LocalState.hosts` via a `FlowObserver`, and
/// every mutation (add/edit/delete, TOFU pin capture, re-pair) is written back
/// through the repository's suspend API. Mirrors the Android `HostsScreen`,
/// which observes the same shared repository.
@Observable
final class HostsViewModel {
    /// The saved hosts, mirrored from the repository's `LocalState.hosts`.
    var hosts: [HostEntryLocal] = []
    var connectingId: String?
    var errorMessage: String?
    /// Set when the latest connect attempt failed because the server's leaf
    /// cert no longer matches the stored pin. The view binds a dedicated
    /// alert to this so the user gets Re-pair / Forget / Cancel instead of
    /// the generic "Connection failed" message. Mirrors the
    /// `PinMismatchDialog` shown on Android.
    var pinMismatchEntry: HostEntryLocal?
    var waitingForApproval: Bool { ConnectionHolder.shared.pendingApproval }

    /// Sentinel id used in `connectingId` for the built-in demo row, which
    /// is not a persisted host entry. Drives the row's progress spinner and
    /// disables the rest of the list during the (instant) demo connect.
    static let demoConnectingId = "builtin-demo"

    private let repository = AppRepository.shared
    private let flowObserver = Client.FlowObserver()

    init() {
        // Mirror the persisted host list into `hosts`. The value is `LocalState?`
        // (nil until hydration completes); an empty list is published until then.
        flowObserver.observe(flow: repository.state) { [weak self] value in
            let state = value as? Client.LocalState
            let mapped = (state?.hosts ?? []).map { HostEntryLocal(from: $0) }
            DispatchQueue.main.async { self?.hosts = mapped }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Connect to the built-in demo "server": the magic demo host makes the
    /// shared client run against its in-process simulation, so this never
    /// touches the network and completes instantly. No auth, no TLS pin, no
    /// saved host entry.
    func connectDemo() {
        connectingId = Self.demoConnectingId
        errorMessage = nil
        Task {
            do {
                let serverUrl = Client.ServerUrl(host: DemoModeKt.DEMO_HOST, port: 0)
                try await ConnectionHolder.shared.connect(
                    serverUrl: serverUrl,
                    authToken: "demo",
                    pinnedFingerprintHex: nil
                )
                // Demo settings resolve to the stock defaults; setting them
                // anyway keeps every colour accessor on the same path as a
                // real connection.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run { connectingId = nil }
            } catch {
                await MainActor.run {
                    connectingId = nil
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    /// Connect to a saved host entry: walks its candidate endpoints in order
    /// (a paired entry carries every address the server advertised) and, on
    /// success, persists the winner in one write — preferred host/port
    /// promoted, TOFU pin captured if this was a pinless first connect, spent
    /// pairing token cleared. Mirrors the Android `connectToEntry` lambda.
    func connect(entry: HostEntryLocal) {
        connectingId = entry.id
        errorMessage = nil
        Task {
            do {
                let token = try await repository.getOrCreateAuthToken()
                // The last endpoint that actually worked goes first; the rest
                // of the advertised set follows, minus that duplicate.
                let preferred = Client.HostPort(host: entry.host, port: entry.port)
                    .toCandidateString(defaultPort: nil)
                let candidates = [preferred] + entry.candidates.filter { $0 != preferred }
                let connection = try await ConnectionHolder.shared.connectMulti(
                    candidates: candidates,
                    defaultPort: entry.port,
                    authToken: token,
                    pinnedFingerprintHex: entry.pinnedFingerprintHex,
                    pairingToken: entry.pairingToken
                )
                // TOFU: on a pinless first connect, keep whatever fingerprint
                // the handshake observed so the next connect runs in
                // strict-verify mode.
                let pin = entry.pinnedFingerprintHex
                    ?? (connection.client.observedFingerprint.value as? String)
                var updated = entry
                updated.host = connection.endpoint.host
                updated.port = connection.endpoint.port
                updated.pinnedFingerprintHex = pin
                updated.pairingToken = nil
                if updated != entry {
                    try? await repository.updateHost(entry: updated.toShared())
                }
                // Fetch the user's theme settings so all views use the
                // selected theme from the start. Palette.settings is a
                // static var read by all colour accessors.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run {
                    connectingId = nil
                }
            } catch {
                await MainActor.run {
                    connectingId = nil
                    if ConnectionHolder.shared.lastPinMismatch {
                        pinMismatchEntry = entry
                    } else {
                        errorMessage = Self.connectFailureMessage(error)
                    }
                }
            }
        }
    }

    /// Handle a scanned QR / deep-linked pairing URI: parse, dedupe against
    /// existing entries, save, and connect straight away. Mirrors the Android
    /// `handlePairingUri` lambda.
    ///
    /// Invalid input is expected, not exceptional — the scanner reports any QR
    /// it decodes, and the deep link is reachable from any app that can open a
    /// URL — so a non-payload just raises the same friendly message Android
    /// shows.
    ///
    /// - Parameter uri: the raw `lunamux://pair?...` string.
    func handlePairingUri(_ uri: String) {
        guard let payload = Client.PairingPayload.companion.parse(uri: uri),
              let preferred = payload.candidates.first else {
            errorMessage = "That doesn't look like a Lunamux pairing code"
            return
        }
        Task {
            let candidateStrings = payload.candidates.map { $0.toCandidateString(defaultPort: nil) }
            // Same server = same cert fingerprint, or an overlapping endpoint.
            // Matching on the fingerprint is what makes re-pairing after a cert
            // rotation update the existing entry instead of forking a duplicate.
            let existing = try? await repository.ensureLoaded().hosts.first { host in
                host.pinnedFingerprintHex == payload.fingerprintHex
                    || candidateStrings.contains(
                        Client.HostPort(host: host.host, port: host.port)
                            .toCandidateString(defaultPort: nil)
                    )
                    || host.candidates.contains(where: { candidateStrings.contains($0) })
            }

            let entry: HostEntryLocal
            if let existing {
                var updated = HostEntryLocal(from: existing)
                updated.host = preferred.host
                updated.port = preferred.port
                updated.pinnedFingerprintHex = payload.fingerprintHex
                updated.candidates = candidateStrings
                updated.pairingToken = payload.token
                try? await repository.updateHost(entry: updated.toShared())
                entry = updated
            } else {
                let created = try? await repository.addPairedHost(
                    label: payload.serverName ?? "Paired Mac",
                    host: preferred.host,
                    port: preferred.port,
                    pinnedFingerprintHex: payload.fingerprintHex,
                    candidates: candidateStrings,
                    pairingToken: payload.token
                )
                guard let created else {
                    await MainActor.run { errorMessage = "Couldn't save the paired server" }
                    return
                }
                entry = HostEntryLocal(from: created)
            }
            await MainActor.run { connect(entry: entry) }
        }
    }

    /// Build a connection-failure message that explains the likely cause
    /// instead of echoing a transport error. The server is only reachable on
    /// its own network, so the device's current transport is the single most
    /// useful hint: on mobile data it cannot reach a LAN host at all, and on
    /// the wrong Wi-Fi it looks identical to a dead server.
    ///
    /// Mirrors the Android `connectFailureMessage`.
    ///
    /// - Parameter error: the connect failure (non-pin-mismatch).
    /// - Returns: a user-facing message for the alert.
    private static func connectFailureMessage(_ error: Error) -> String {
        // A device-auth rejection reaches the server but is turned away before
        // the first config (expired/foreign pairing token, allow-remote off, or
        // a revoked device). Its raw text is developer-facing, so translate the
        // known case into something the user can act on.
        if error.localizedDescription.contains("before sending a config") {
            return "The Mac declined this connection. Re-pair or approve this device on your "
                + "Mac: Lunamux > Settings > Server & Security… > Devices > Pair a device."
        }
        // Network-blame only when we genuinely couldn't reach the server. A
        // phase-2 failure (reached, but the server rejected the device / never
        // sent a config) carries a descriptive message we must not mask with
        // "check your Wi-Fi" advice.
        guard let unreachable = error as? ServerUnreachableError else {
            return error.localizedDescription
        }
        if NetworkStatus.shared.isOnCellular {
            return "You're on mobile data. This Mac is reachable on its own Wi-Fi network — "
                + "join that network and try again."
        }
        if NetworkStatus.shared.isOnWifi {
            return "Couldn't reach the Mac. Make sure this iPhone is on the same Wi-Fi "
                + "network as your computer."
        }
        // Transport unknown — fall back to the wrapped transport failure, not
        // the wrapper's own synthesized description.
        return unreachable.underlying.localizedDescription
    }

    func addHost(label: String, host: String, port: Int32) {
        Task { try? await repository.addHost(label: label, host: host, port: port) }
    }

    func updateHost(_ entry: HostEntryLocal) {
        Task { try? await repository.updateHost(entry: entry.toShared()) }
    }

    func deleteHost(id: String) {
        Task { try? await repository.deleteHost(id: id) }
    }

    /// Clear the stored pin so the next connect attempt re-runs the TOFU
    /// capture and re-fires the server's `DeviceAuth.ApprovalDialog`.
    /// Triggered from the cert-changed alert's "Re-pair" button when the
    /// user has decided the new certificate is legitimate (server was
    /// reinstalled, key rolled, etc.).
    func repairPin(_ entry: HostEntryLocal) {
        var updated = entry
        updated.pinnedFingerprintHex = nil
        Task { try? await repository.updateHost(entry: updated.toShared()) }
        pinMismatchEntry = nil
    }

    /// Delete the host entry from the cert-changed alert's "Forget" button.
    /// Distinct from `deleteHost(id:)` only in that it also clears the
    /// alert state.
    func forgetHost(_ entry: HostEntryLocal) {
        Task { try? await repository.deleteHost(id: entry.id) }
        pinMismatchEntry = nil
    }
}
