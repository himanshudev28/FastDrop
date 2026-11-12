import AppKit
import CoreImage.CIFilterBuiltins
import FastDropCore
import SwiftUI

/// Crisp QR rendering: the CIImage is scaled with nearest-neighbour by an integer factor.
enum QRRenderer {
    static func image(for text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage else { return nil }
        let scaled = out.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}

struct QRCodeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var cached: (url: String, image: NSImage)?
    @State private var shown = false

    var body: some View {
        let url = model.joinLink.url
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Text("Scan to connect").font(.headline)
                Text("On your phone: FastDrop → Send → Scan QR")
                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.white)
                if let img = cached?.image {
                    Image(nsImage: img)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 220, height: 220)
                        .accessibilityLabel("QR code to connect to \(model.deviceName)")
                }
            }
            .frame(width: 248, height: 248)
            .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
            .scaleEffect(shown ? 1 : 0.85)
            .opacity(shown ? 1 : 0)

            VStack(spacing: 3) {
                Text(model.deviceName).font(.subheadline.weight(.semibold))
                if model.lanIPs.isEmpty {
                    Label("Not connected to a network", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption).foregroundStyle(Palette.warning)
                } else {
                    ForEach(model.lanIPs, id: \.self) { ip in
                        Text(ip).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }
            }
            HStack {
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(url, forType: .string)
                    model.showToast("Link copied", .success)
                } label: { Label("Copy Link", systemImage: "doc.on.doc") }
                Button("Add Device…") {
                    model.showQR = false
                    model.showAddDevice = true
                }
            }
            .controlSize(.regular)
        }
        .padding(20)
        .frame(width: 300)
        .onAppear { refresh(url) }
        .onChange(of: url) { _, new in refresh(new) }
    }

    private func refresh(_ url: String) {
        if cached?.url != url, let img = QRRenderer.image(for: url) { cached = (url, img) }
        withAnimation(Motion.pop(reduceMotion)) { shown = true }
    }
}

struct AddDeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var text = ""
    @State private var running = false
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Add a device").font(.headline)
            TextField("192.168.1.23 or fastdrop://join…", text: $text)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .disabled(running)
                .onSubmit(connect)
                .accessibilityLabel("Device address or link")
            HStack {
                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.scale(scale: 0.9, anchor: .leading).combined(with: .opacity))
                }
                Spacer(minLength: 0)
                if running { ProgressView().controlSize(.small) }
                Button("Connect", action: connect)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(running || text.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let ssid = model.joining {
                Label("Joining “\(ssid)”…", systemImage: "wifi")
                    .font(.caption).foregroundStyle(.secondary)
                    .symbolEffect(.variableColor.iterative, isActive: !reduceMotion)
                    .transition(.opacity)
            }
            Divider()
            Button {
                model.showAddDevice = false
                model.showScanner = true
            } label: { Label("Scan the phone's QR with the camera", systemImage: "qrcode.viewfinder") }
                .disabled(running)
            Text("Not on the same Wi‑Fi? The Mac joins the phone's own direct link, like Quick Share.")
                .font(.footnote).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Divider()
            HStack {
                Button { model.scanNetwork() } label: { Label("Scan this network", systemImage: "dot.radiowaves.left.and.right") }
                    .disabled(model.scanning || running)
                if model.scanning { ProgressView().controlSize(.small) }
            }
            Text("Finds FastDrop on every address of your Wi‑Fi.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding(16)
        .frame(width: 320)
        .animation(Motion.pop(reduceMotion), value: error)
        .animation(Motion.pop(reduceMotion), value: model.joining)
        .onAppear { focused = true }
    }

    private func connect() {
        let input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty, !running else { return }
        running = true
        error = nil
        Task {
            let result = await model.addDevice(input)
            running = false
            if let result {
                error = result
                if !model.showAddDevice { model.showToast(result, .error) }   // popover closed meanwhile
            } else { model.showAddDevice = false }
        }
    }
}

struct HistoryView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("History").font(.headline)
                Spacer()
                Button { model.openSaveFolder() } label: { Label("Open Folder", systemImage: "folder") }
                    .labelStyle(.iconOnly).help("Open Folder")
                Button("Clear") { model.clearHistory() }.disabled(model.history.isEmpty)
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 12).padding(.vertical, 10)
            Divider()
            if model.history.isEmpty {
                ContentUnavailableView("No transfers yet", systemImage: "clock",
                                       description: Text("Files you send and receive show up here."))
            } else {
                List(model.history) { item in
                    HistoryRow(item: item)
                }
                .listStyle(.inset)
            }
        }
    }
}

private struct HistoryRow: View {
    @Environment(AppModel.self) private var model
    let item: HistoryItem

    private var fileURL: URL? { item.path.map { URL(fileURLWithPath: $0) } }
    private var exists: Bool { fileURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false }

    var body: some View {
        HStack(spacing: 10) {
            icon.frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).lineLimit(1).truncationMode(.middle)
                Text("\(item.outgoing ? "to" : "from") \(item.peer) · \(item.text != nil ? "text" : Fmt.bytes(item.size)) · \(item.date.formatted(.relative(presentation: .named)))")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            if let text = item.text {
                Button { model.copyText(text) } label: { Image(systemName: "doc.on.doc") }
                    .help("Copy").accessibilityLabel("Copy text")
                if AppModel.url(in: text) != nil {
                    Button { model.openLink(text) } label: { Image(systemName: "arrow.up.right.square") }
                        .help("Open link").accessibilityLabel("Open link")
                }
            }
            Image(systemName: item.outgoing ? "arrow.up" : "arrow.down")
                .font(.caption.weight(.bold))
                .foregroundStyle(item.outgoing ? Palette.sendA : Palette.recvA)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { open() }
        .accessibilityElement(children: .combine)
        .contextMenu {
            if let text = item.text {
                Button("Copy") { model.copyText(text) }
                if AppModel.url(in: text) != nil { Button("Open") { model.openLink(text) } }
                Divider()
            }
            Button("Open") { open() }.disabled(!exists)
            Button("Show in Finder") {
                if let u = fileURL { NSWorkspace.shared.activateFileViewerSelecting([u]) }
            }.disabled(!exists)
            Divider()
            Button("Remove from History", role: .destructive) { model.removeHistory(item) }
        }
    }

    @ViewBuilder private var icon: some View {
        if item.outgoing {
            Image(systemName: "arrow.up.circle.fill")
                .resizable().scaledToFit()
                .foregroundStyle(Palette.send)
        } else if item.text != nil {
            Image(systemName: AppModel.url(in: item.text ?? "") != nil ? "link.circle.fill" : "text.bubble.fill")
                .resizable().scaledToFit().foregroundStyle(Palette.receive)
        } else if let u = fileURL, exists {
            Image(nsImage: FileIcon.image(for: u)).resizable().scaledToFit()
        } else {
            Image(nsImage: FileIcon.image(forName: item.name)).resizable().scaledToFit()
        }
    }

    private func open() {
        if let u = fileURL, exists { NSWorkspace.shared.open(u) }
        else if let t = item.text { model.openLink(t) }
    }
}

/// "Send Text": a message or link, or the clipboard, to one device.
struct TextComposer: View {
    let target: TextTarget
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var peerID = ""
    @FocusState private var focused: Bool

    private var capable: [Peer] { model.devices.filter { $0.v >= 2 } }
    private var peer: Peer? { capable.first { $0.id == peerID } }
    private var count: Int { text.unicodeScalars.count }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Send text or a link").font(.headline)
            if capable.isEmpty {
                Text("No nearby device supports text yet. Update FastDrop on the other device to 2.1 or newer.")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                Picker("To", selection: $peerID) {
                    ForEach(capable) { Text($0.name).tag($0.id) }
                }
            }
            TextEditor(text: $text)
                .font(.body)
                .focused($focused)
                .frame(minHeight: 120)
                .scrollContentBackground(.hidden)
                .padding(6)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.primary.opacity(0.06)))
                .accessibilityLabel("Text to send")
            HStack {
                Button { text = NSPasteboard.general.string(forType: .string) ?? text } label: {
                    Label("Paste Clipboard", systemImage: "doc.on.clipboard")
                }
                Text("\(count.formatted()) / \(Spec.maxText.formatted())")
                    .font(.caption).monospacedDigit()
                    .foregroundStyle(count > Spec.maxText ? Palette.danger : .secondary)
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Send") {
                    if let p = peer { model.sendText(text, to: p) }
                    dismiss()
                }
                .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction)
                .disabled(peer == nil || text.isEmpty || count > Spec.maxText)
            }
        }
        .padding(20)
        .frame(width: 420)
        .onAppear {
            text = target.initial
            peerID = target.peer.flatMap { p in capable.contains { $0.id == p.id } ? p.id : nil } ?? capable.first?.id ?? ""
            focused = true
        }
    }
}
