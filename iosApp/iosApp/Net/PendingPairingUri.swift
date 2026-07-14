/// Hand-off point for `lunamux://pair` deep links between the app scene
/// (which receives the URL via `onOpenURL`) and the hosts screen (which
/// performs the pairing). Holds at most one pending URI.
///
/// Mirrors the Android `PendingPairingUri` object, which bridges the same gap
/// between `MainActivity.onNewIntent` and `HostsScreen`.

import Foundation
import Observation

/// Process-wide single-slot mailbox for a pairing deep link. `LunamuxApp`
/// posts into it from `onOpenURL`; `HostsView` observes `uri` and calls
/// `consume()` exactly once per posted link, so a re-render or a scene
/// re-activation can never trigger a duplicate pairing.
///
/// The mailbox exists because a cold-launch deep link is delivered before the
/// hosts screen has mounted — the URL would otherwise be dropped on the floor.
/// Parking it here lets the screen pick it up whenever it appears.
@Observable
final class PendingPairingUri {
    static let shared = PendingPairingUri()

    /// The pending pairing URI, or `nil` when none is waiting.
    private(set) var uri: String?

    private init() {}

    /// Post a freshly-received pairing link, replacing any unconsumed one
    /// (the newest scan wins).
    ///
    /// - Parameter value: the full `lunamux://pair?...` URL string.
    @MainActor
    func post(_ value: String) {
        uri = value
    }

    /// Take the pending URI, clearing the slot.
    ///
    /// - Returns: the URI, or `nil` if it was already consumed.
    @MainActor
    func consume() -> String? {
        defer { uri = nil }
        return uri
    }
}
