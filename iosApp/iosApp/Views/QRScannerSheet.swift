/// QR scanner for device pairing, presented as a sheet from `HostsView`.
///
/// This is the one part of the pairing flow that cannot mirror Android. There,
/// the Google code scanner supplies its own UI and runs the camera inside a
/// Play Services process, so the app needs no CAMERA permission at all. iOS
/// has no such out-of-process scanner, so we drive `AVCaptureSession`
/// ourselves and must ask for camera access (`NSCameraUsageDescription` in
/// Info.plist). The permission states below are the cost of that difference.
///
/// Contains `QRScannerSheet` (the presented SwiftUI view and its permission
/// state machine), `CameraPreview` (the `UIViewControllerRepresentable`
/// bridge), and `ScannerViewController` (the capture session itself).
///
/// - SeeAlso: `HostsView`, `HostsViewModel.handlePairingUri(_:)`

import SwiftUI
import AVFoundation

/// Sheet that shows a live camera viewfinder and reports the first QR code it
/// decodes. Validation is the caller's job: any QR is reported, and
/// `HostsViewModel.handlePairingUri` rejects the ones that are not Lunamux
/// pairing payloads — exactly as the Android scanner path does.
///
/// - Note: The sheet does not dismiss itself on a successful scan; it calls
///   `onScan` and lets the caller dismiss, so the hosts screen owns the
///   transition into "connecting".
struct QRScannerSheet: View {
    /// Invoked on the main actor with the raw decoded string of the first QR
    /// code seen. Called at most once per presentation.
    let onScan: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    /// Where the camera-permission conversation currently stands. Starts at
    /// `.checking` so the first frame of the sheet is never a wrong guess.
    @State private var access: CameraAccess = .checking

    /// Latches once `onScan` has fired so a burst of decoded frames cannot
    /// pair twice.
    @State private var handled = false

    /// The camera-permission states the sheet renders differently.
    private enum CameraAccess {
        /// Authorization status not yet read (the initial, pre-`task` state).
        case checking
        /// Camera available and permitted — show the viewfinder.
        case authorized
        /// Denied or restricted; the user must change it in Settings.
        case denied
        /// No usable capture device (Simulator, or hardware without a camera).
        case unavailable
    }

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .checking:
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .authorized:
                    scanner
                case .denied:
                    deniedState
                case .unavailable:
                    unavailableState
                }
            }
            .background(Palette.background)
            .navigationTitle("Scan pairing code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Palette.headerAccent)
                }
            }
        }
        .task { await resolveAccess() }
    }

    /// Viewfinder plus the standing instruction. The reticle is decorative —
    /// `AVCaptureMetadataOutput` scans the whole frame, not just the cutout —
    /// but it tells the user where to aim.
    private var scanner: some View {
        ZStack {
            CameraPreview { value in
                guard !handled else { return }
                handled = true
                onScan(value)
            }
            .ignoresSafeArea()

            VStack {
                Spacer()
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Palette.headerAccent, lineWidth: 3)
                    .frame(width: 240, height: 240)
                    .accessibilityHidden(true)
                Spacer()
                Text("On your Mac: Lunamux > Settings > Server & Security… > Devices > Pair a device")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
                    .shadow(radius: 3)
            }
        }
    }

    private var deniedState: some View {
        ContentUnavailableView {
            Label("Camera access needed", systemImage: "camera.fill")
        } description: {
            Text("Lunamux needs the camera to scan your Mac's pairing code. "
                 + "You can turn it on in Settings, or add the Mac's address manually.")
        } actions: {
            // UIApplication.openSettingsURLString deep-links straight to this
            // app's own Settings page, where the camera toggle lives.
            if let url = URL(string: UIApplication.openSettingsURLString) {
                Button { UIApplication.shared.open(url) } label: {
                    Text("Open Settings")
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.headerAccent)
            }
        }
    }

    private var unavailableState: some View {
        ContentUnavailableView {
            Label("No camera available", systemImage: "camera.fill")
        } description: {
            Text("This device has no camera to scan with. Add the Mac's address manually instead.")
        }
    }

    /// Read the current camera authorization and, when it has never been
    /// asked, prompt for it. Runs from `.task`, so the sheet shows a spinner
    /// rather than flashing a wrong state while the system alert is up.
    private func resolveAccess() async {
        guard hasCaptureDevice else {
            access = .unavailable
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            access = .authorized
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            access = granted ? .authorized : .denied
        default:
            // .denied and .restricted both mean "no camera for us, and we
            // cannot ask again from here".
            access = .denied
        }
    }

    /// Whether the device has a back-facing capture device at all. False on
    /// the Simulator, which would otherwise show a permanently black preview.
    private var hasCaptureDevice: Bool {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil
    }
}

// MARK: - Camera preview bridge

/// Bridges `ScannerViewController` into SwiftUI.
private struct CameraPreview: UIViewControllerRepresentable {
    /// Called on the main actor with each decoded QR string.
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScan = onScan
        return controller
    }

    /// No-op: the controller owns the capture session outright and has no
    /// SwiftUI-driven state to refresh.
    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

// MARK: - Capture session

/// Runs an `AVCaptureSession` wired to an `AVCaptureMetadataOutput` filtered
/// to QR codes, and reports each decoded payload through `onScan`.
///
/// Lifecycle is tied to the view controller's appearance so the camera (and
/// its indicator) stops the moment the sheet goes away.
final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    /// Called on the main actor for every decoded QR code. De-duplication is
    /// the caller's job — `QRScannerSheet` latches on the first hit.
    var onScan: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?

    /// `startRunning()` and `stopRunning()` both block until the session has
    /// (re)configured, which is far too slow for the main thread.
    private let sessionQueue = DispatchQueue(label: "se.soderbjorn.lunamux.scanner.session")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    /// Build the capture graph: back camera in, QR metadata out. Any failure
    /// leaves the session empty, which renders as a black preview — the sheet
    /// has already ruled out the two causes a user can act on (no camera, no
    /// permission) before this controller is ever created.
    private func configureSession() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            NSLog("[ScannerViewController] no usable camera input")
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            NSLog("[ScannerViewController] cannot add metadata output")
            return
        }
        session.addOutput(output)
        // Deliver on main: the callback hops straight into SwiftUI state.
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Must be set *after* addOutput — the available types are empty until
        // the output is attached to a session.
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        sessionQueue.async { [session] in
            guard !session.isRunning else { return }
            session.startRunning()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sessionQueue.async { [session] in
            guard session.isRunning else { return }
            session.stopRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue else { return }
        onScan?(value)
    }
}
