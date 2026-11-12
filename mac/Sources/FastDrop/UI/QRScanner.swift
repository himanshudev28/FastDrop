import AVFoundation
import AppKit
import SwiftUI
import Vision

// Camera QR scanner sheet: live preview, QR detection through Vision, and an image-file fallback.

// MARK: - Capture engine

/// Owns the capture session and the video-data delegate. All session work happens on `queue`.
/// `found` is guarded by a lock so the payload is delivered exactly once.
private final class QRCaptureEngine: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    enum StartResult: Sendable { case started, noCamera, failed }

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "fastdrop.qr.capture")
    private let lock = NSLock()
    private var found = false
    private var frameCount = 0
    private var configured = false
    private let onPayload: @Sendable (String) -> Void

    init(onPayload: @escaping @Sendable (String) -> Void) {
        self.onPayload = onPayload
        super.init()
    }

    func start(completion: @escaping @Sendable (StartResult) -> Void) {
        queue.async { [self] in
            if !configured {
                guard let device = Self.pickCamera() else { completion(.noCamera); return }
                guard let input = try? AVCaptureDeviceInput(device: device) else { completion(.failed); return }
                session.beginConfiguration()
                session.sessionPreset = .high
                let output = AVCaptureVideoDataOutput()
                output.alwaysDiscardsLateVideoFrames = true
                output.setSampleBufferDelegate(self, queue: queue)
                guard session.canAddInput(input), session.canAddOutput(output) else {
                    session.commitConfiguration()
                    completion(.failed)
                    return
                }
                session.addInput(input)
                session.addOutput(output)
                session.commitConfiguration()
                configured = true
            }
            if !session.isRunning { session.startRunning() }
            completion(.started)
        }
    }

    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private static func pickCamera() -> AVCaptureDevice? {
        if let d = AVCaptureDevice.default(for: .video) { return d }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .external, .continuityCamera],
            mediaType: .video, position: .unspecified)
        return discovery.devices.first
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        frameCount &+= 1
        guard frameCount % 3 == 0 else { return }
        lock.lock()
        let already = found
        lock.unlock()
        if already { return }

        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cmSampleBuffer: sampleBuffer, options: [:])
        guard (try? handler.perform([request])) != nil,
              let payload = request.results?.compactMap({ $0.payloadStringValue }).first(where: { !$0.isEmpty })
        else { return }

        lock.lock()
        let first = !found
        found = true
        lock.unlock()
        guard first else { return }
        session.stopRunning()
        onPayload(payload)
    }
}

// MARK: - Model

@MainActor
private final class QRScannerModel: ObservableObject {
    enum Phase: Equatable { case starting, scanning, denied, noCamera, failed }

    @Published var phase: Phase = .starting
    @Published var succeeded = false
    @Published var imageMessage: String?

    private var engine: QRCaptureEngine!
    private var delivered = false
    private var onCode: (String) -> Void = { _ in }

    var session: AVCaptureSession { engine.session }

    init() {
        engine = QRCaptureEngine { [weak self] payload in
            Task { @MainActor in self?.handle(payload) }
        }
    }

    func configure(onCode: @escaping (String) -> Void) { self.onCode = onCode }

    func begin() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startEngine()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor [weak self] in
                    if granted { self?.startEngine() } else { self?.phase = .denied }
                }
            }
        default:
            phase = .denied
        }
    }

    private func startEngine() {
        engine.start { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .started: self.phase = .scanning
                case .noCamera: self.phase = .noCamera
                case .failed: self.phase = .failed
                }
            }
        }
    }

    func stop() { engine.stop() }

    private func handle(_ payload: String) {
        guard !delivered else { return }
        delivered = true
        withAnimation(Motion.bouncy) { succeeded = true }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 450_000_000)
            onCode(payload)
        }
    }

    func chooseImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.message = "Choose an image that contains the phone's QR code"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        imageMessage = nil
        Task {
            let payload = await Task.detached(priority: .userInitiated) { Self.decode(url: url) }.value
            if let payload {
                handle(payload)
            } else {
                imageMessage = "No QR code found in that image"
            }
        }
    }

    nonisolated private static func decode(url: URL) -> String? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        guard (try? handler.perform([request])) != nil else { return nil }
        return request.results?.compactMap { $0.payloadStringValue }.first(where: { !$0.isEmpty })
    }
}

// MARK: - Preview

private final class PreviewNSView: NSView {
    let previewLayer = AVCaptureVideoPreviewLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: -1, y: 1)) // mirror, like FaceTime
        layer?.addSublayer(previewLayer)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
    }
}

private struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> PreviewNSView {
        let view = PreviewNSView(frame: .zero)
        view.previewLayer.session = session
        return view
    }

    func updateNSView(_ nsView: PreviewNSView, context: Context) {}
}

// MARK: - Viewfinder

private struct CornerBrackets: Shape {
    var length: CGFloat = 28
    var radius: CGFloat = 10

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let l = length, r = radius
        // top-left
        p.move(to: CGPoint(x: rect.minX, y: rect.minY + l))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + l, y: rect.minY))
        // top-right
        p.move(to: CGPoint(x: rect.maxX - l, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + l))
        // bottom-right
        p.move(to: CGPoint(x: rect.maxX, y: rect.maxY - l))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.maxX - l, y: rect.maxY))
        // bottom-left
        p.move(to: CGPoint(x: rect.minX + l, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - l))
        return p
    }
}

private struct Viewfinder: View {
    let succeeded: Bool
    let reduceMotion: Bool

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height) * 0.66
            ZStack {
                CornerBrackets()
                    .stroke(succeeded ? Palette.success : Color.white,
                            style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                    .frame(width: side, height: side)
                    .scaleEffect(succeeded ? 1.06 : 1)
                    .shadow(color: .black.opacity(0.35), radius: 3)

                if !succeeded {
                    // Clock-driven (not repeatForever-in-onAppear, which also animates the sheet's first layout).
                    TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { ctx in
                        let t = ctx.date.timeIntervalSinceReferenceDate
                        let phase = reduceMotion ? 0.5 : (1 - cos(t * .pi / 1.8)) / 2   // 0…1…0 every 3.6 s
                        Capsule()
                            .fill(LinearGradient(colors: [.clear, Palette.recvA.opacity(0.9), .clear],
                                                 startPoint: .leading, endPoint: .trailing))
                            .frame(width: side - 24, height: 3)
                            .offset(y: (-side / 2 + 16) + phase * (side - 32))
                            .opacity(reduceMotion ? 0.5 : 1)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(Motion.bouncy(reduceMotion), value: succeeded)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: - View

struct QRScannerView: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void

    @StateObject private var model = QRScannerModel()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(onCode: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.onCode = onCode
        self.onCancel = onCancel
    }

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Text("Scan the phone's QR code")
                    .font(.title2.weight(.semibold))
                Text("On the phone open FastDrop \u{2192} Receive (or Send \u{2192} Show my QR). Hold the code up to your Mac's camera.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ZStack {
                Color.black.opacity(0.85)
                switch model.phase {
                case .scanning:
                    CameraPreview(session: model.session)
                    Viewfinder(succeeded: model.succeeded, reduceMotion: reduceMotion)
                case .starting:
                    ProgressView().controlSize(.large).colorScheme(.dark)
                case .denied:
                    message(icon: "video.slash",
                            text: "FastDrop needs camera access to scan the QR code.",
                            showSettings: true)
                case .noCamera:
                    message(icon: "video.slash", text: "No camera found on this Mac.", showSettings: false)
                case .failed:
                    message(icon: "exclamationmark.triangle", text: "The camera could not be started.", showSettings: false)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
            )
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Camera preview for scanning a QR code")

            VStack(spacing: 4) {
                Button {
                    model.chooseImage()
                } label: {
                    Label("Choose Image\u{2026}", systemImage: "photo")
                }
                .accessibilityLabel("Choose an image containing a QR code")
                .accessibilityHint("Opens a file picker")

                if let msg = model.imageMessage {
                    Text(msg)
                        .font(.footnote)
                        .foregroundStyle(Palette.danger)
                        .accessibilityLabel(msg)
                }
            }

            HStack {
                Spacer()
                Button("Cancel", action: onCancel)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(20)
        .frame(width: 460, height: 440)
        .onAppear {
            model.configure(onCode: onCode)
            model.begin()
        }
        .onDisappear { model.stop() }
    }

    private func message(icon: String, text: String, showSettings: Bool) -> some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.largeTitle)
                .foregroundStyle(.white.opacity(0.8))
                .accessibilityHidden(true)
            Text(text)
                .font(.callout)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
            if showSettings {
                Button("Open Camera Settings") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera") {
                        NSWorkspace.shared.open(url)
                    }
                }
                .accessibilityHint("Opens System Settings so you can allow camera access")
            }
        }
        .padding(24)
    }
}
