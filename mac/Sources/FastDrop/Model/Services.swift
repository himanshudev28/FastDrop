import AppKit
import FastDropCore

/// Receives "Send with FastDrop" from the Services menu (Finder, text selections).
/// The selection is put in the tray (or the Send Text sheet); if the device used last is online, a small
/// chooser offers a one-click send.
@MainActor
final class ServicesProvider: NSObject {
    static let shared = ServicesProvider()

    @objc nonisolated func sendFiles(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = Self.fileURLs(from: pboard)
        guard !urls.isEmpty else { error.pointee = "FastDrop found no files in the selection." as NSString; return }
        DispatchQueue.main.async { MainActor.assumeIsolated { Self.shared.handle(files: urls) } }
    }

    @objc nonisolated func sendText(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "FastDrop found no text in the selection." as NSString; return
        }
        DispatchQueue.main.async { MainActor.assumeIsolated { Self.shared.handle(text: text) } }
    }

    nonisolated static func fileURLs(from pboard: NSPasteboard) -> [URL] {
        if let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            return urls
        }
        if let paths = pboard.propertyList(forType: NSPasteboard.PasteboardType("NSFilenamesPboardType")) as? [String] {
            return paths.map { URL(fileURLWithPath: $0) }
        }
        return []
    }

    private func handle(files urls: [URL]) {
        let model = AppModel.shared
        model.addFiles(urls)
        model.showMainWindow()
        guard !model.isBusy, model.offer == nil, let peer = model.recentOnlinePeer else { return }
        let n = model.picked.count
        let alert = NSAlert()
        alert.messageText = "Send \(n) item\(n == 1 ? "" : "s") with FastDrop?"
        alert.informativeText = "\(peer.name) is online. Or pick another device in the FastDrop window."
        alert.addButton(withTitle: "Send to \(peer.name)")
        alert.addButton(withTitle: "Choose Device")
        if alert.runModal() == .alertFirstButtonReturn {
            model.send(to: peer, urls: model.picked.map(\.url))
        }
    }

    private func handle(text: String) {
        let model = AppModel.shared
        model.showMainWindow()
        if !model.isBusy, model.offer == nil, let peer = model.recentOnlinePeer, peer.v >= 2 {
            let alert = NSAlert()
            alert.messageText = "Send this text with FastDrop?"
            alert.informativeText = "\(peer.name) is online. “\(AppModel.preview(text))”"
            alert.addButton(withTitle: "Send to \(peer.name)")
            alert.addButton(withTitle: "Choose Device")
            if alert.runModal() == .alertFirstButtonReturn { model.sendText(text, to: peer); return }
        }
        model.composeText(initial: text)
    }
}

// MARK: - Drop files on the menu bar icon

/// SwiftUI's MenuBarExtra owns its NSStatusItem, so we can't make it a drop target directly. Instead a
/// transparent overlay view, registered for file drags, is added to the status item's button. Mouse clicks
/// are forwarded to the button so the menu still opens normally. It is purely additive: if the button
/// isn't found, nothing happens and the icon works as before.
@MainActor
final class MenuBarDrop {
    static let shared = MenuBarDrop()
    private var attempts = 0

    func start() {
        install()
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.install() }
        }
        retry()
    }

    /// The status item appears a moment after launch; poll briefly.
    private func retry() {
        guard attempts < 12 else { return }
        attempts += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            MainActor.assumeIsolated { self?.install(); self?.retry() }
        }
    }

    private func install() {
        for w in NSApp.windows where String(describing: type(of: w)).contains("StatusBarWindow") {
            guard let button = Self.findButton(in: w.contentView) else { continue }
            if button.subviews.contains(where: { $0 is DropOverlay }) { continue }
            let overlay = DropOverlay(frame: button.bounds)
            overlay.autoresizingMask = [.width, .height]
            overlay.button = button
            button.addSubview(overlay)
        }
    }

    private static func findButton(in view: NSView?) -> NSButton? {
        guard let view else { return nil }
        if let b = view as? NSButton { return b }
        for s in view.subviews { if let b = findButton(in: s) { return b } }
        return nil
    }
}

private final class DropOverlay: NSView {
    weak var button: NSButton?

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL, .string])
    }
    required init?(coder: NSCoder) { fatalError() }

    // Be hit-testable (needed to receive drags) but hand clicks to the real button.
    override func mouseDown(with event: NSEvent) { button?.mouseDown(with: event) }
    override func rightMouseDown(with event: NSEvent) { button?.rightMouseDown(with: event) }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let pb = sender.draggingPasteboard
        let urls = ServicesProvider.fileURLs(from: pb)
        let text = urls.isEmpty ? pb.string(forType: .string) : nil
        guard !urls.isEmpty || !(text ?? "").isEmpty else { return false }
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let model = AppModel.shared
                if !urls.isEmpty { model.addFiles(urls) } else if let text { model.composeText(initial: text) }
                model.showMainWindow()
            }
        }
        return true
    }
}
