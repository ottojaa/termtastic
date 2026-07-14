/// Coarse connectivity classification for connection-failure messaging.
///
/// When a connect attempt fails, the hosts screen uses this to tell the user
/// *why* it probably failed ("you're on mobile data") instead of surfacing a
/// generic timeout. Mirrors the Android `NetworkStatus` object, which reads
/// `ConnectivityManager` for the same purpose.

import Foundation
import Network

/// Live view of the current network path's transport.
///
/// Unlike Android's `ConnectivityManager`, `NWPathMonitor` is push-based: it
/// has no synchronous "what am I on right now" query, so a single long-lived
/// monitor keeps the last known path cached for the failure path to read. The
/// monitor is started once at first use and runs for the process lifetime —
/// it is cheap, and the alternative (spinning one up inside the error handler)
/// would race the very answer we need.
///
/// Failures degrade to `false` so callers fall back to generic copy.
final class NetworkStatus: @unchecked Sendable {
    static let shared = NetworkStatus()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var currentPath: NWPath?

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            lock.lock()
            currentPath = path
            lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "se.soderbjorn.lunamux.networkstatus"))
    }

    /// Whether the device's active path rides Wi-Fi.
    var isOnWifi: Bool { usesInterface(.wifi) }

    /// Whether the device's active path rides mobile data.
    var isOnCellular: Bool { usesInterface(.cellular) }

    /// Shared active-path transport probe backing the public queries.
    ///
    /// - Parameter type: the interface type to test for.
    /// - Returns: `true` when the satisfied path uses that interface.
    private func usesInterface(_ type: NWInterface.InterfaceType) -> Bool {
        lock.lock()
        let path = currentPath
        lock.unlock()
        guard let path, path.status == .satisfied else { return false }
        return path.usesInterfaceType(type)
    }
}
