import FastDropCore
import SwiftUI

/// The single main window: radar of nearby devices, the file tray, and overlays for the
/// running transfer, an incoming offer and toasts.
struct ContentView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var windowDropTargeted = false

    private var mood: Mood {
        switch model.activeDirectionOutgoing {
        case .some(true): return .sending
        case .some(false): return .receiving
        case .none: return .idle
        }
    }

    var body: some View {
        @Bindable var model = model
        ZStack {
            AmbientBackground(mood: mood)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                RadarStage(mood: mood)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                // The transfer card floats over this spot; hide the tray so it can't peek out or take clicks.
                let cardShown = model.pending != nil || model.live != nil
                BottomTray(dropTargeted: windowDropTargeted)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 18)
                    .opacity(cardShown ? 0 : 1)
                    .allowsHitTesting(!cardShown)
                    .accessibilityHidden(cardShown)
            }

            // Running / finished transfer floats above the tray.
            VStack {
                Spacer()
                if model.pending != nil || model.live != nil {
                    TransferCard()
                        .padding(.horizontal, 20)
                        .padding(.bottom, 18)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }

            VStack(spacing: 8) {
                if let ssid = model.joining {
                    JoiningPill(ssid: ssid)
                        .padding(.top, 12)
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                }
                if let toast = model.toast {
                    ToastView(toast: toast)
                        .padding(.top, 12)
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                        .id(toast.id)
                }
                Spacer()
            }

            if let offer = model.offer {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .transition(.opacity)
                OfferCard(offer: offer)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.86).combined(with: .opacity))
                    .id(offer.id)
                    .zIndex(1)
            }

            ConfettiBurst(trigger: model.celebrations)
                .allowsHitTesting(false)
                .ignoresSafeArea()
        }
        .dropDestination(for: URL.self) { urls, _ in
            model.addFiles(urls)
            return !urls.isEmpty
        } isTargeted: { t in
            withAnimation(Motion.snappy(reduceMotion)) { windowDropTargeted = t }
        }
        .navigationTitle("FastDrop")
        .navigationSubtitle(subtitle)
        .toolbar { toolbar }
        .toolbarBackground(.hidden, for: .windowToolbar)
        .inspector(isPresented: $model.showHistory) {
            HistoryView()
                .inspectorColumnWidth(min: 260, ideal: 300, max: 420)
        }
        .sheet(item: $model.composing) { target in
            TextComposer(target: target)
        }
        .sheet(isPresented: $model.showScanner) {
            QRScannerView(onCode: { model.connect(scanned: $0) }, onCancel: { model.showScanner = false })
        }
        .background(WindowTracker { model.mainWindow = $0 })
        .onAppear { model.openMainWindow = { [openWindow] in openWindow(id: "main") } }
    }

    private var subtitle: String {
        if let e = model.listenError { return e }
        if let ip = model.lanIPs.first { return "\(model.deviceName) · \(ip)" }
        return "\(model.deviceName) · not on a network"
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        @Bindable var model = model
        ToolbarItemGroup(placement: .primaryAction) {
            let offerUp = model.offer != nil
            Button { model.chooseFiles() } label: {
                Label("Add Files", systemImage: "plus")
            }
            .help("Add files or folders to send (⌘O)")
            .disabled(offerUp)

            Button { model.composeText() } label: {
                Label("Send Text", systemImage: "text.bubble")
            }
            .help("Send a text or link to a device, or the clipboard")
            .disabled(offerUp || model.devices.isEmpty)

            Button { model.showScanner = true } label: {
                Label("Scan Phone's QR", systemImage: "qrcode.viewfinder")
            }
            .help("Scan the QR on your phone. Works even when you're not on the same Wi‑Fi (⇧⌘J)")
            .disabled(model.joining != nil || offerUp)

            Button { model.showAddDevice.toggle() } label: {
                Label("Add Device", systemImage: "network")
            }
            .help("Connect to a device by IP address or fastdrop:// link")
            .disabled(offerUp)
            .popover(isPresented: $model.showAddDevice, arrowEdge: .bottom) { AddDeviceView() }

            Button { model.scanNetwork() } label: {
                Label("Scan Network", systemImage: "dot.radiowaves.left.and.right")
                    .symbolEffect(.variableColor.iterative, isActive: model.scanning)
            }
            .disabled(model.scanning || offerUp)
            .help("Look for FastDrop on every address of this network (⌘R)")

            Button { model.showQR.toggle() } label: {
                Label("My QR Code", systemImage: "qrcode")
            }
            .help("Show a QR code phones can scan to find this Mac")
            .popover(isPresented: $model.showQR, arrowEdge: .bottom) { QRCodeView() }

            Button { model.showHistory.toggle() } label: {
                Label("History", systemImage: "clock.arrow.circlepath")
            }
            .help("Recent transfers")
        }
        // While an offer waits for an answer, nothing else should open on top of it.
    }
}

/// Shown while the Mac switches its Wi‑Fi to a phone's direct link.
private struct JoiningPill: View {
    let ssid: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "wifi")
                .symbolEffect(.variableColor.iterative, isActive: !reduceMotion)
                .foregroundStyle(Palette.recvA)
            Text("Joining “\(ssid)”…").font(.callout.weight(.medium))
            ProgressView().controlSize(.small)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.thickMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
        .accessibilityElement(children: .combine)
    }
}

/// Hands the hosting NSWindow to the model, so it can bring the main window (not Settings) forward.
private struct WindowTracker: NSViewRepresentable {
    let found: (NSWindow?) -> Void
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async { [weak v] in found(v?.window) }
        return v
    }
    func updateNSView(_ v: NSView, context: Context) {
        if v.window != nil { DispatchQueue.main.async { [weak v] in found(v?.window) } }
    }
}
