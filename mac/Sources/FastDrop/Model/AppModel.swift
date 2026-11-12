import AppKit
import FastDropCore
import Observation
import SwiftUI
import UserNotifications

/// A file or folder waiting in the tray to be sent.
struct PickedFile: Identifiable, Hashable {
    let url: URL
    let name: String
    let size: Int64
    let isFolder: Bool
    var id: URL { url }
}

/// "Connecting…" / "Waiting for Pixel to accept…": before the receiver says yes.
struct PendingSend: Equatable {
    let peer: Peer
    var status: String
    /// 4-digit code the receiver shows too, so the user can confirm it's the right device.
    var code: String?
}

/// A multi-device send: one normal transfer per device, one after another.
struct Batch {
    var peers: [Peer]
    var urls: [URL]
    var text: String?
    var next = 0
    var sent = 0
    var stopped = false
}

/// The "Send Text" sheet: a text/link to send to `peer` (or one picked in the sheet).
struct TextTarget: Identifiable {
    let id = UUID()
    var peer: Peer?
    var initial = ""
}

/// A sampled view of the running transfer, refreshed ~10×/s while it runs.
struct LiveTransfer: Equatable {
    let transfer: Transfer
    var peerID: String?
    var done: Int64 = 0
    var speed: Double = 0
    var state: TransferState = .running
    var message = ""

    var fraction: Double { transfer.total > 0 ? min(1, Double(done) / Double(transfer.total)) : 1 }
    var eta: Double { speed > 1 ? Double(transfer.total - done) / speed : .infinity }

    static func == (a: Self, b: Self) -> Bool {
        a.transfer === b.transfer && a.done == b.done && a.speed == b.speed && a.state == b.state && a.message == b.message
    }
}

struct HistoryItem: Codable, Identifiable, Hashable {
    var id = UUID()
    let name: String
    let size: Int64
    let path: String?      // received files only
    let peer: String
    let outgoing: Bool
    let date: Date
    var text: String? = nil   // text / link transfers (name is a short preview)
}

struct Toast: Identifiable, Equatable {
    enum Kind { case info, success, error }
    let id = UUID()
    let text: String
    let kind: Kind
}

@Observable @MainActor
final class AppModel {
    static let shared = AppModel()

    // MARK: settings (persisted)

    var deviceName: String { didSet { identity.name = deviceName; defaults.set(deviceName, forKey: "deviceName") } }
    var streams: Int { didSet { defaults.set(streams, forKey: "streams") } }
    var acceptMode: AcceptMode { didSet { defaults.set(acceptMode.rawValue, forKey: "acceptMode"); config.acceptMode = acceptMode } }
    /// Ask receivers to verify every chunk with CRC32 (off by default: it costs CPU and disables zero-copy sends).
    var verifyCRC: Bool { didSet { defaults.set(verifyCRC, forKey: "verifyCRC") } }
    var saveFolder: URL { didSet { defaults.set(saveFolder.path, forKey: "saveFolder"); config.saveFolder = saveFolder } }
    var revealWhenDone: Bool { didSet { defaults.set(revealWhenDone, forKey: "revealWhenDone") } }
    var showMenuBarIcon: Bool { didSet { defaults.set(showMenuBarIcon, forKey: "showMenuBarIcon") } }

    // MARK: live state

    private(set) var devices: [Peer] = []
    var picked: [PickedFile] = []
    private(set) var pending: PendingSend?
    private(set) var live: LiveTransfer?
    private(set) var offer: IncomingOffer?
    private(set) var trustedDevices: [TrustedDevice] = []
    /// Devices ticked for a multi-device send.
    private(set) var selectedIDs: Set<String> = []
    var composing: TextTarget?
    private(set) var batchStatus: String?
    private(set) var history: [HistoryItem] = []
    private(set) var toast: Toast?
    private(set) var lanIPs: [String] = []
    private(set) var listenError: String?
    private(set) var scanning = false
    /// SSID the Mac is switching to for a phone's direct link (shown while it happens).
    private(set) var joining: String?
    var showScanner = false
    /// Bumped on each successful transfer; drives the celebration burst.
    private(set) var celebrations = 0
    var showHistory = false
    var showQR = false
    var showAddDevice = false

    /// Set by the main window so offers can bring it back after it was closed.
    @ObservationIgnored var openMainWindow: (() -> Void)?
    /// The main window, tracked by the view so we can tell it apart from Settings.
    @ObservationIgnored weak var mainWindow: NSWindow?
    /// Keeps App Nap from throttling the receiver while FastDrop sits in the background.
    @ObservationIgnored private var listenActivity: NSObjectProtocol?
    /// Received-file history rows recorded for the current incoming transfer (capped).
    @ObservationIgnored private var rxRows = 0

    // MARK: core

    let identity: Identity
    let gate = Gate()
    private let defaults = UserDefaults.standard
    private let config = SharedConfig()
    private var receiver: Receiver!
    private var discovery: Discovery!
    private var lastSeen: [String: Date] = [:]
    private var job: SendJob?
    private var lastItems: [URL] = []
    private var lastText: String?
    private var batch: Batch?
    private let stores = Stores.shared
    private var lastPeer: Peer?
    private var samples: [(Date, Int64)] = []
    private var ticker: Timer?
    private var activity: NSObjectProtocol?
    private var started = false

    private init() {
        let d = UserDefaults.standard
        var id = d.string(forKey: "deviceID") ?? ""
        if id.isEmpty {
            id = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)).lowercased()
            d.set(id, forKey: "deviceID")
        }
        let name = d.string(forKey: "deviceName") ?? (Host.current().localizedName ?? "Mac")
        identity = Identity(id: id, name: name, type: "laptop")
        deviceName = name
        streams = d.object(forKey: "streams") as? Int ?? 4
        // The old on/off switch becomes a 3-way setting: on -> Everyone, off -> Trusted devices.
        let mode = d.string(forKey: "acceptMode").flatMap(AcceptMode.init(rawValue:))
            ?? AcceptMode.migrated(legacyAutoAccept: d.bool(forKey: "autoAccept"))
        d.set(mode.rawValue, forKey: "acceptMode")
        acceptMode = mode
        verifyCRC = d.bool(forKey: "verifyCRC")
        saveFolder = d.string(forKey: "saveFolder").map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/FastDrop", isDirectory: true)
        revealWhenDone = d.object(forKey: "revealWhenDone") as? Bool ?? false
        showMenuBarIcon = d.object(forKey: "showMenuBarIcon") as? Bool ?? true
        config.acceptMode = mode
        config.saveFolder = saveFolder
        history = Self.loadHistory()
        trustedDevices = Stores.shared.trust.list()
    }

    func start() {
        guard !started else { return }
        started = true
        // Permissions: notifications are asked for on first launch; `--setup-permissions` also asks for
        // the camera now (one-time setup) instead of on the first QR scan.
        let perms = Permissions.shared
        perms.refresh()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))   // let refresh() report before deciding
            if perms.notifications == .notAsked { perms.requestNotifications() }
            if CommandLine.arguments.contains("--setup-permissions"), perms.camera == .notAsked { perms.requestCamera() }
        }
        listenActivity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiatedAllowingIdleSystemSleep], reason: "FastDrop is listening for nearby devices")

        receiver = Receiver(identity: identity, gate: gate)
        let config = self.config
        receiver.acceptMode = { config.acceptMode }
        receiver.saveDir = { config.saveFolder }
        receiver.present = { [weak self] offer in
            offer.onExpire = { [weak self] reason in
                DispatchQueue.main.async { self?.offerExpired(offer, senderLeft: reason == "gone") }
            }
            DispatchQueue.main.async { self?.presentOffer(offer) }
        }
        receiver.onStart = { [weak self] t in DispatchQueue.main.async { self?.began(t, peerID: nil) } }
        receiver.onSaved = { [weak self] url, from, size in
            DispatchQueue.main.async { self?.saved(url, from: from, size: size) }
        }
        receiver.onText = { [weak self] text, from in
            DispatchQueue.main.async { self?.receivedText(text, from: from) }
        }
        receiver.onListenError = { [weak self] e in DispatchQueue.main.async { self?.listenError = e } }
        receiver.start()

        discovery = Discovery(identity: identity)
        discovery.onPeer = { [weak self] p in DispatchQueue.main.async { self?.seen(p) } }
        discovery.onBye = { [weak self] id in DispatchQueue.main.async { self?.gone(id) } }
        discovery.start()

        lanIPs = Net.lanIPs()
        Timer.scheduledTimer(withTimeInterval: 2.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.housekeeping() }
        }
    }

    func shutdown() {
        discovery?.bye()
        live?.transfer.cancel()
        job?.cancel()
    }

    // MARK: derived

    var isBusy: Bool { pending != nil || live?.state == .running || batch != nil }
    var activeDirectionOutgoing: Bool? {
        if pending != nil { return true }
        guard let l = live, l.state == .running else { return nil }
        return l.transfer.outgoing
    }
    var joinLink: JoinLink { JoinLink(id: identity.id, name: deviceName, type: "laptop", lanIPs: lanIPs) }
    var pickedTotal: Int64 { picked.reduce(0) { $0 + $1.size } }

    // MARK: discovery

    private func seen(_ p: Peer) {
        lastSeen[p.id] = Date()
        if let i = devices.firstIndex(where: { $0.id == p.id }) {
            var merged = p
            merged.manual = devices[i].manual || p.manual
            if devices[i] != merged { devices[i] = merged }
        } else {
            withAnimation(Motion.pop) {
                devices.append(p)
                devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            }
        }
    }

    private func gone(_ id: String) {
        guard let d = devices.first(where: { $0.id == id }), !d.manual else { return }
        withAnimation(Motion.pop) { devices.removeAll { $0.id == id } }
    }

    private func housekeeping() {
        let now = Date()
        let stale = devices.filter { !$0.manual && now.timeIntervalSince(lastSeen[$0.id] ?? .distantPast) > 10 }.map(\.id)
        if !stale.isEmpty { withAnimation(Motion.pop) { devices.removeAll { stale.contains($0.id) } } }
        let ips = Net.lanIPs()
        if ips != lanIPs {
            lanIPs = ips
            discovery.nudge()   // just joined a network: let the others see us now
        }
    }

    /// Connects to a device from an IP address or a scanned/pasted `fastdrop://join` link.
    /// When the link carries a network the phone hosts (Wi‑Fi Direct group or local hotspot) and the
    /// phone isn't reachable on our current network, the Mac joins that network first, like Quick Share.
    /// Returns nil on success, or a message for the user.
    func addDevice(_ input: String) async -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let myID = identity.id
        func probe(_ ips: [String], port: UInt16, timeout: TimeInterval) async -> Peer? {
            await Task.detached {
                for ip in ips {
                    if let p = Discovery.hello(ip: ip, port: port, timeout: timeout, myId: myID, manual: true) { return p }
                }
                return nil
            }.value
        }
        func added(_ p: Peer, _ how: String) -> String? {
            seen(p)
            showToast(how, .success)
            return nil
        }

        if Net.isIPv4(text) {
            guard let p = await probe([text], port: Proto.port, timeout: 2.5) else {
                return "No FastDrop device answered at \(text)."
            }
            return added(p, "Added \(p.name)")
        }
        guard let link = JoinLink.parse(text) else {
            return "That isn't a FastDrop code. Enter an IP like 192.168.1.23 or scan the phone's QR."
        }
        guard joining == nil else { return "Already connecting to “\(joining ?? "")”…" }

        // 1. Already reachable (same Wi‑Fi, or already on the phone's own network)? Host address first,
        //    and only accept the device the code belongs to.
        if !link.candidates.isEmpty, let p = await probe(link.candidates, port: link.port, timeout: 1.5), p.id == link.id {
            return added(p, "Added \(p.name)")
        }
        // 2. Join the network the phone hosts.
        guard let ssid = link.ssid else {
            return "Can't reach \(link.name). Put both devices on the same Wi‑Fi, or on the phone open Receive so it shows a code with its own network."
        }
        joining = ssid
        defer { joining = nil }
        let pass = link.pass
        let joinError: String? = await Task.detached {
            do { try WiFi.join(ssid: ssid, password: pass); return nil } catch { return error.localizedDescription }
        }.value
        if let joinError { return joinError }

        // 3. Wait for DHCP and for the phone to answer (up to ~20 s). The phone is the network's router,
        //    so try the gateway too in case the code had no host address.
        for _ in 0..<20 {
            let router = await Task.detached { WiFi.routerIP() }.value
            var targets = link.candidates
            for ip in [router, "192.168.49.1"].compactMap({ $0 }) where !targets.contains(ip) { targets.append(ip) }
            if let p = await probe(targets, port: link.port, timeout: 1), p.id == link.id || link.candidates.isEmpty {
                lanIPs = Net.lanIPs()
                discovery.nudge()
                return added(p, "Connected to \(p.name) over its direct Wi‑Fi link")
            }
            try? await Task.sleep(for: .seconds(1))
        }
        return "Joined “\(ssid)” but \(link.name) didn't answer. Keep FastDrop open on the phone and try again."
    }

    /// Code scanned by the camera: connect, and report problems as a toast.
    func connect(scanned code: String) {
        showScanner = false
        showAddDevice = false
        Task {
            if let err = await addDevice(code) { showToast(err, .error) }
        }
    }

    func forget(_ p: Peer) {
        withAnimation(Motion.pop) { devices.removeAll { $0.id == p.id } }
    }

    func scanNetwork() {
        guard !scanning else { return }
        scanning = true
        let myID = identity.id
        Task {
            let n = await Discovery.scan(myId: myID) { [weak self] p in
                DispatchQueue.main.async { self?.seen(p) }
            }
            scanning = false
            showToast(n == 0 ? "No new devices found" : "Found \(n) device\(n == 1 ? "" : "s")", n == 0 ? .info : .success)
        }
    }

    // MARK: picking

    func addFiles(_ urls: [URL]) {
        let fresh = urls.filter { u in u.isFileURL && !picked.contains { $0.url == u } }
        guard !fresh.isEmpty else { return }
        let entries: [PickedFile] = fresh.map { u in
            let isDir = (try? u.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
            let size = isDir ? SendItem.expand([u]).reduce(Int64(0)) { $0 + $1.size }
                             : Int64((try? u.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            return PickedFile(url: u, name: u.lastPathComponent, size: size, isFolder: isDir)
        }
        withAnimation(Motion.pop) { picked.append(contentsOf: entries) }
    }

    func remove(_ f: PickedFile) { withAnimation(Motion.pop) { picked.removeAll { $0.id == f.id } } }
    func clearPicked() { withAnimation(Motion.pop) { picked.removeAll() } }

    func chooseFiles(thenSendTo peer: Peer? = nil) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.prompt = peer == nil ? "Add" : "Send"
        panel.message = peer.map { "Choose files or folders to send to \($0.name)" } ?? "Choose files or folders to send"
        guard panel.runModal() == .OK, !panel.urls.isEmpty else { return }
        if let peer { send(to: peer, urls: panel.urls) } else { addFiles(panel.urls) }
    }

    // MARK: sending

    /// Click on a device: send the tray, or ask for files first if the tray is empty.
    func tap(_ peer: Peer) {
        if picked.isEmpty { chooseFiles(thenSendTo: peer) } else { send(to: peer, urls: picked.map(\.url)) }
    }

    /// Sends files (or, with `text`, a text/link) to one device.
    @discardableResult
    func send(to peer: Peer, urls: [URL], text: String? = nil) -> Bool {
        guard offer == nil else { showToast("Answer the incoming offer first", .info); return false }
        guard pending == nil, live?.state != .running else { showToast("A transfer is already running", .info); return false }
        let items = text == nil ? SendItem.expand(urls) : []
        guard text != nil || !items.isEmpty else { showToast("Nothing to send in that selection", .error); return false }
        lastItems = urls; lastPeer = peer; lastText = text
        defaults.set(peer.id, forKey: "recentPeerID")
        let job = SendJob(peer: peer, items: items, text: text, identity: identity, gate: gate, streams: streams)
        job.useCRC = verifyCRC
        self.job = job
        withAnimation(Motion.pop) { live = nil; pending = PendingSend(peer: peer, status: "Connecting to \(peer.name)…") }
        job.onStatus = { [weak self] s in
            DispatchQueue.main.async { if self?.pending != nil { self?.pending?.status = s } }
        }
        job.onCode = { [weak self] c in
            DispatchQueue.main.async { if self?.pending != nil { self?.pending?.code = c } }
        }
        job.onStart = { [weak self] t in
            DispatchQueue.main.async { self?.began(t, peerID: peer.id) }
        }
        Thread.detachNewThread { [weak self] in
            let err = job.run()
            DispatchQueue.main.async {
                guard let self, self.job === job else { return }
                self.job = nil
                if let err {
                    withAnimation(Motion.pop) { self.pending = nil }
                    if err != "Cancelled" { self.showToast(err, .error) }
                    self.batchStepDone(success: false, cancelled: err == "Cancelled")
                }
            }
        }
        return true
    }

    func retry() {
        guard let p = lastPeer, !lastItems.isEmpty || lastText != nil else { return }
        let peer = devices.first { $0.id == p.id } ?? p
        send(to: peer, urls: lastItems, text: lastText)   // same items + device: the receiver resumes where it stopped
    }

    func sendClipboard(to peer: Peer) {
        guard let t = NSPasteboard.general.string(forType: .string), !t.isEmpty else {
            showToast("The clipboard has no text", .info); return
        }
        sendText(t, to: peer)
    }

    func sendText(_ text: String, to peer: Peer) {
        guard text.unicodeScalars.count <= Spec.maxText else { showToast("That text is too long (max 100,000 characters)", .error); return }
        if peer.v < 2 { showToast("\(peer.name) needs FastDrop 2.1 or newer to receive text", .error); return }
        send(to: peer, urls: [], text: text)
    }

    func composeText(for peer: Peer? = nil, initial: String = "") { composing = TextTarget(peer: peer, initial: initial) }

    /// The device used for the last send, if it is online right now (for one-click sends from Services).
    var recentOnlinePeer: Peer? {
        guard let id = defaults.string(forKey: "recentPeerID") else { return nil }
        return devices.first { $0.id == id }
    }

    // MARK: several devices

    func toggleSelected(_ p: Peer) {
        withAnimation(Motion.pop) {
            if selectedIDs.contains(p.id) { selectedIDs.remove(p.id) } else { selectedIDs.insert(p.id) }
        }
    }
    func clearSelection() { withAnimation(Motion.pop) { selectedIDs.removeAll() } }
    var selectedPeers: [Peer] { devices.filter { selectedIDs.contains($0.id) } }

    /// Sends the tray (or asks for files first) to every ticked device, one after another.
    func sendToSelected() {
        let peers = selectedPeers
        guard !peers.isEmpty else { return }
        if picked.isEmpty {
            let panel = NSOpenPanel()
            panel.allowsMultipleSelection = true; panel.canChooseDirectories = true; panel.canChooseFiles = true
            panel.prompt = "Send"
            panel.message = "Choose files or folders to send to \(peers.count) devices"
            guard panel.runModal() == .OK, !panel.urls.isEmpty else { return }
            startBatch(peers, urls: panel.urls)
        } else {
            startBatch(peers, urls: picked.map(\.url))
        }
    }

    func startBatch(_ peers: [Peer], urls: [URL], text: String? = nil) {
        guard batch == nil, !isBusy, offer == nil else { showToast("A transfer is already running", .info); return }
        batch = Batch(peers: peers, urls: urls, text: text)
        clearSelection()
        advanceBatch()
    }

    private func advanceBatch() {
        guard var b = batch else { return }
        guard !b.stopped, b.next < b.peers.count else { finishBatch(); return }
        let peer = devices.first { $0.id == b.peers[b.next].id } ?? b.peers[b.next]
        b.next += 1
        batch = b
        batchStatus = "Sending to \(b.next) of \(b.peers.count) devices"
        if !send(to: peer, urls: b.urls, text: b.text) { batchStepDone(success: false, cancelled: false) }
    }

    /// One device of the batch finished (or failed to start).
    private func batchStepDone(success: Bool, cancelled: Bool) {
        guard var b = batch else { return }
        if success { b.sent += 1 }
        if cancelled { b.stopped = true }
        batch = b
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(success ? 900 : 300))   // let the result card register
            self?.advanceBatch()
        }
    }

    private func finishBatch() {
        guard let b = batch else { return }
        batch = nil; batchStatus = nil
        let n = b.peers.count
        showToast("Sent to \(b.sent) of \(n) device\(n == 1 ? "" : "s")", b.sent == n ? .success : (b.sent == 0 ? .error : .info))
    }

    func cancelTransfer() {
        if let l = live, l.state == .running { l.transfer.cancel() } else { job?.cancel() }
        if batch != nil { batch?.stopped = true }
    }

    func dismissTransfer() {
        guard live?.state != .running else { return }
        withAnimation(Motion.pop) { live = nil }
    }

    // MARK: transfer lifecycle

    private func began(_ t: Transfer, peerID: String?) {
        samples = [(Date(), t.done)]   // resumed bytes are already there: speed and ETA count only this session
        rxRows = 0
        withAnimation(Motion.pop) {
            pending = nil
            live = LiveTransfer(transfer: t, peerID: peerID)
        }
        activity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiated, .idleSystemSleepDisabled], reason: "Transferring files")
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        t.onFinish { [weak self] _ in DispatchQueue.main.async { self?.tick() } }
    }

    private func tick() {
        guard var l = live else { ticker?.invalidate(); return }
        guard l.state == .running else { return }   // the timer and onFinish can both land after the end
        let t = l.transfer
        let now = Date(), d = t.done
        samples.append((now, d))
        samples.removeAll { now.timeIntervalSince($0.0) > 1.5 }
        if let first = samples.first, now.timeIntervalSince(first.0) > 0.2 {
            let instant = Double(d - first.1) / now.timeIntervalSince(first.0)
            l.speed = l.speed == 0 ? instant : l.speed * 0.6 + instant * 0.4
        }
        l.done = d
        let st = t.state
        if st != .running {
            l.state = st
            l.message = t.message
            if st == .done { l.done = t.total }
            ticker?.invalidate(); ticker = nil
            if let a = activity { ProcessInfo.processInfo.endActivity(a); activity = nil }
            withAnimation(Motion.pop) { live = l }
            finished(l)
        } else {
            live = l
        }
    }

    private func finished(_ l: LiveTransfer) {
        let t = l.transfer
        switch l.state {
        case .done:
            celebrations += 1
            if t.outgoing {
                if t.isText, let text = lastText {
                    record(HistoryItem(name: Self.preview(text), size: Int64(text.utf8.count), path: nil, peer: t.peerName,
                                       outgoing: true, date: Date(), text: text))
                } else {
                    let label = t.fileCount == 1 ? (t.names.first ?? "1 file") : "\(t.fileCount) files"
                    record(HistoryItem(name: label, size: t.total, path: nil, peer: t.peerName, outgoing: true, date: Date()))
                    withAnimation(Motion.pop) { picked.removeAll { lastItems.contains($0.url) } }
                }
            } else {
                if revealWhenDone { NSWorkspace.shared.activateFileViewerSelecting([saveFolder]) }
                notifyIfInactive(title: "Received from \(t.peerName)", body: "\(t.fileCount) file\(t.fileCount == 1 ? "" : "s") · \(Fmt.bytes(t.total))")
            }
            // Success cards leave on their own; failures stay until dismissed.
            let id = t.id
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(3.5))
                guard let self, self.live?.transfer.id == id, self.live?.state == .done else { return }
                withAnimation(Motion.pop) { self.live = nil }
            }
        case .failed, .cancelled:
            if !t.outgoing { notifyIfInactive(title: "Transfer from \(t.peerName) stopped", body: l.message) }
        case .running:
            break
        }
        if t.outgoing { batchStepDone(success: l.state == .done, cancelled: l.state == .cancelled) }
    }

    // MARK: receiving

    private func presentOffer(_ o: IncomingOffer) {
        // Nothing may sit on top of the Accept card: close the scanner sheet and popovers.
        showScanner = false
        showAddDevice = false
        showQR = false
        composing = nil
        withAnimation(Motion.pop) { offer = o }
        let wasActive = NSApp.isActive
        showMainWindow()
        if !wasActive {
            NSApp.requestUserAttention(.criticalRequest)
            notifyIfInactive(title: o.text != nil ? "\(o.from) wants to send you text" : "\(o.from) wants to send you files",
                             body: o.text.map { Self.preview($0) } ?? "\(o.files.count) file\(o.files.count == 1 ? "" : "s") · \(Fmt.bytes(o.total))")
        }
    }

    /// Brings the main window forward, reopening it if it was closed.
    func showMainWindow() {
        if let w = mainWindow, w.isVisible || w.isMiniaturized {
            if w.isMiniaturized { w.deminiaturize(nil) }
            w.makeKeyAndOrderFront(nil)
        } else {
            openMainWindow?()
        }
        NSApp.activate()
    }

    func respond(_ accept: Bool, alwaysAccept: Bool = false) {
        guard let o = offer else { return }
        o.respond(accept, trust: alwaysAccept)
        withAnimation(Motion.pop) { offer = nil }
        if accept && alwaysAccept {
            // The receiver stores the key just before it replies; give it a moment, then refresh the list.
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(400))
                self?.refreshTrusted()
                self?.showToast("\(o.from) is now trusted", .success)
            }
        }
    }

    // MARK: trusted devices

    func refreshTrusted() { trustedDevices = stores.trust.list() }

    func removeTrusted(_ d: TrustedDevice) {
        stores.trust.remove(d.id)
        withAnimation(Motion.pop) { refreshTrusted() }
    }

    // MARK: received text

    nonisolated static func preview(_ text: String) -> String {
        let one = text.split(whereSeparator: \.isNewline).joined(separator: " ")
        return one.count > 80 ? String(one.prefix(80)) + "…" : one
    }

    static func url(in text: String) -> URL? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: t), let sc = u.scheme?.lowercased(), sc == "http" || sc == "https", u.host != nil,
              !t.contains(where: \.isWhitespace) else { return nil }
        return u
    }

    private func receivedText(_ text: String, from: String) {
        record(HistoryItem(name: Self.preview(text), size: Int64(text.utf8.count), path: nil, peer: from, outgoing: false,
                           date: Date(), text: text))
        showToast("Text from \(from) saved to History", .success)
        notifyIfInactive(title: "Text from \(from)", body: Self.preview(text))
        withAnimation(Motion.pop) { showHistory = true }
    }

    func copyText(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        showToast("Copied", .success)
    }

    func openLink(_ text: String) {
        if let u = Self.url(in: text) { NSWorkspace.shared.open(u) }
    }

    private func offerExpired(_ o: IncomingOffer, senderLeft: Bool) {
        guard offer === o else { return }
        withAnimation(Motion.pop) { offer = nil }
        showToast(senderLeft ? "\(o.from) cancelled the transfer" : "\(o.from)’s offer expired", .info)
    }

    private func saved(_ url: URL, from: String, size: Int64) {
        rxRows += 1
        guard rxRows <= 25 else { return }   // a big folder shouldn't flush the whole history
        record(HistoryItem(name: url.lastPathComponent, size: size, path: url.path, peer: from, outgoing: false, date: Date()))
    }

    // MARK: history

    private func record(_ h: HistoryItem) {
        withAnimation(Motion.pop) {
            history.insert(h, at: 0)
            if history.count > 300 { history.removeLast(history.count - 300) }
        }
        saveHistory()
    }

    func removeHistory(_ h: HistoryItem) { withAnimation(Motion.pop) { history.removeAll { $0.id == h.id } }; saveHistory() }
    func clearHistory() { withAnimation(Motion.pop) { history.removeAll() }; saveHistory() }

    nonisolated private static let historyQueue = DispatchQueue(label: "fastdrop.history", qos: .utility)

    nonisolated private static var historyURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FastDrop", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("history.json")
    }

    private static func loadHistory() -> [HistoryItem] {
        guard let d = try? Data(contentsOf: historyURL) else { return [] }
        return (try? JSONDecoder().decode([HistoryItem].self, from: d)) ?? []
    }

    private func saveHistory() {
        let snapshot = history
        Self.historyQueue.async {
            if let d = try? JSONEncoder().encode(snapshot) { try? d.write(to: Self.historyURL, options: .atomic) }
        }
    }

    // MARK: feedback

    func showToast(_ text: String, _ kind: Toast.Kind = .info) {
        let t = Toast(text: text, kind: kind)
        withAnimation(Motion.pop) { toast = t }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(kind == .error ? 4.5 : 3))
            guard let self, self.toast?.id == t.id else { return }
            withAnimation(Motion.pop) { self.toast = nil }
        }
    }

    func dismissToast() { withAnimation(Motion.pop) { toast = nil } }

    private func notifyIfInactive(title: String, body: String) {
        guard !NSApp.isActive, Bundle.main.bundleIdentifier != nil else { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { ok, _ in
            guard ok else { return }
            let c = UNMutableNotificationContent()
            c.title = title; c.body = body; c.sound = .default
            center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
        }
    }

    func copyIP(_ p: Peer) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(p.ip, forType: .string)
        showToast("Copied \(p.ip)", .success)
    }

    func openSaveFolder() {
        try? FileManager.default.createDirectory(at: saveFolder, withIntermediateDirectories: true)
        NSWorkspace.shared.open(saveFolder)
    }
}

/// Settings the receiver's background threads read without touching the main actor.
final class SharedConfig: @unchecked Sendable {
    private let lock = NSLock()
    private var _acceptMode = AcceptMode.trusted
    private var _saveFolder = URL(fileURLWithPath: NSHomeDirectory())
    var acceptMode: AcceptMode {
        get { lock.lock(); defer { lock.unlock() }; return _acceptMode }
        set { lock.lock(); _acceptMode = newValue; lock.unlock() }
    }
    var saveFolder: URL {
        get { lock.lock(); defer { lock.unlock() }; return _saveFolder }
        set { lock.lock(); _saveFolder = newValue; lock.unlock() }
    }
}
