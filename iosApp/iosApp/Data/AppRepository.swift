import Foundation
import Client

/// App-wide owner of the shared KMP `LocalRepository`.
///
/// All on-device, non-server local state — the saved host list (with TLS pins),
/// the onboarding flag, and the news/update bookkeeping — lives in one
/// `LocalState` persisted to a single `local_state.json` file in the app's
/// Documents directory, owned by the shared `LocalRepository`. The device-auth
/// token is the one exception: the repository keeps it in the shared
/// `SecureStore`, whose iOS actual is the Keychain. A single process-wide
/// instance is required so the host list, the onboarding gate, and the news
/// checker all observe and mutate the same state.
///
/// Replaces the previous per-concern stores: `HostsStore` (Documents JSON),
/// `OnboardingStore` (`UserDefaults`), and `KeychainAuthTokenStore` (Keychain).
/// The token's Keychain storage is preserved by the shared `SecureStore` (it
/// reuses the same service/account), so existing devices keep their approval.
enum AppRepository {
    /// The shared repository, constructed and hydrated once on first access.
    static let shared: Client.LocalRepository = {
        let repo = Client.LocalRepositoryKt.createLocalRepository(
            localStore: Client.LocalStore(),
            secureStore: Client.SecureStore()
        )
        repo.start()
        return repo
    }()
}

/// Codable-free, SwiftUI-friendly mirror of the shared KMP `HostEntry`, mapped
/// from the repository's `LocalState.hosts`. Kept as the view-facing type so the
/// host list views (`HostsView`) bind to a native `Identifiable`/`Equatable`
/// value rather than the bridged Kotlin class.
///
/// The server speaks TLS only (see `SERVER_TLS_PORT`), so every connection is
/// `https`/`wss`. `pinnedFingerprintHex` is filled in by `HostsViewModel` after a
/// successful TOFU first-connect captures the leaf cert's SHA-256.
struct HostEntryLocal: Identifiable, Equatable {
    let id: String
    var label: String
    /// Every endpoint this server is known to answer at, in the order the
    /// connect walk tries them — `[0]` is tried first and is whatever last
    /// connected. Mirrors `HostEntry.addresses`; see `promoting(_:)` for how
    /// the winner reaches the front.
    var addresses: [Client.HostPort]
    var pinnedFingerprintHex: String?
    /// One-time QR pairing token, or nil; preserved on round-trip through
    /// `toShared()` so an edit cannot cost the entry its unspent token.
    var pairingToken: String?

    /// The address a connect tries first: the last one that worked. Backs the
    /// row subtitle and the cert-changed alert, both of which name one address
    /// rather than the whole list.
    var primary: Client.HostPort? { addresses.first }

    init(
        id: String,
        label: String,
        addresses: [Client.HostPort],
        pinnedFingerprintHex: String? = nil,
        pairingToken: String? = nil
    ) {
        self.id = id
        self.label = label
        self.addresses = addresses
        self.pinnedFingerprintHex = pinnedFingerprintHex
        self.pairingToken = pairingToken
    }

    /// Map a shared KMP `HostEntry` (from `LocalState.hosts`) into the native value.
    ///
    /// `addresses` and `pairingToken` are carried through verbatim so an edit
    /// that round-trips a QR-paired entry through `toShared()` does not
    /// silently drop them — which would cost the entry its multi-endpoint
    /// connect and its unspent pairing token.
    init(from entry: Client.HostEntry) {
        self.id = entry.id
        self.label = entry.label
        self.addresses = entry.addresses
        self.pinnedFingerprintHex = entry.pinnedFingerprintHex
        self.pairingToken = entry.pairingToken
    }

    /// This entry with `endpoint` moved to the front of `addresses`.
    ///
    /// Called after every successful connect: the address that answered is the
    /// one most likely to answer next time, and position is worth a full
    /// per-candidate timeout for each address ahead of it in the walk. Mirrors
    /// the shared `HostEntry.promoting(endpoint:)`, reimplemented here because
    /// this is the native mirror and never holds the Kotlin value.
    ///
    /// - Parameter endpoint: the address that just connected.
    /// - Returns: a copy with `endpoint` first, remaining order preserved.
    func promoting(_ endpoint: Client.HostPort) -> HostEntryLocal {
        var copy = self
        copy.addresses = [endpoint] + addresses.filter { $0 != endpoint }
        return copy
    }

    /// Convert back to the shared KMP `HostEntry` for persistence through the
    /// repository's `addHost`/`updateHost`.
    ///
    /// Every parameter is passed explicitly: Kotlin default arguments do not
    /// bridge to Swift, so the exported initializer requires all of them.
    func toShared() -> Client.HostEntry {
        Client.HostEntry(
            id: id,
            label: label,
            addresses: addresses,
            pinnedFingerprintHex: pinnedFingerprintHex,
            pairingToken: pairingToken
        )
    }
}
