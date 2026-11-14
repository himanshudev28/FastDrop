import Darwin
import Foundation

public struct OfferFile: Sendable, Hashable {
    public let name: String      // cleaned relative path, may contain "/"
    public let size: Int64
    public let appLabel: String? // set when the phone sends an app (base + split APKs)
    let rawName: String          // as sent: auth digests and resume keys use it
    let mtime: Int64?

    init(name: String, size: Int64, appLabel: String?, rawName: String? = nil, mtime: Int64? = nil) {
        self.name = name; self.size = size; self.appLabel = appLabel; self.rawName = rawName ?? name; self.mtime = mtime
    }
}

/// The three auto-accept modes (replaces the old on/off switch).
public enum AcceptMode: String, Sendable, CaseIterable {
    case ask, trusted, everyone
}

public extension AcceptMode {
    /// Old `autoAccept` bool: true -> Everyone, otherwise Trusted devices.
    static func migrated(legacyAutoAccept: Bool) -> AcceptMode { legacyAutoAccept ? .everyone : .trusted }
}

/// A file or text offer waiting for the user to accept or decline.
public final class IncomingOffer: @unchecked Sendable, Identifiable {
    public let id = UUID()
    public let from: String
    public let fromType: String
    public let files: [OfferFile]
    public let total: Int64
    public let deadline: Date
    /// Text or link sent instead of files.
    public let text: String?
    /// 4-digit code derived from the session id; the sender shows the same one.
    public let code: String
    /// The offer proved (HMAC) that it comes from a device the user already trusts.
    public let trusted: Bool
    /// The sender has a stable id, so "Always accept from this device" can be offered.
    public let canTrust: Bool

    private let sem = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var answered = false
    private var accepted = false
    private var wantsTrust = false

    /// Fires when the offer goes away without the user answering: "timeout", or "gone" when the sender gave up.
    public var onExpire: ((String) -> Void)?

    init(from: String, fromType: String, files: [OfferFile], total: Int64, timeout: TimeInterval,
         text: String?, code: String, trusted: Bool, canTrust: Bool) {
        self.from = from; self.fromType = fromType; self.files = files; self.total = total
        self.deadline = Date().addingTimeInterval(timeout)
        self.text = text; self.code = code; self.trusted = trusted; self.canTrust = canTrust
    }

    public func respond(_ accept: Bool, trust: Bool = false) {
        lock.lock()
        guard !answered else { lock.unlock(); return }
        answered = true; accepted = accept; wantsTrust = accept && trust && canTrust && !trusted
        lock.unlock()
        sem.signal()
    }

    /// Waits for the user, giving up at the deadline or when the sender hangs up.
    func wait(ctrl: Sock) -> (accepted: Bool, reason: String, trust: Bool) {
        func result() -> (accepted: Bool, reason: String, trust: Bool) {
            lock.lock(); defer { lock.unlock() }
            return (accepted, accepted ? "" : "declined", wantsTrust)
        }
        while true {
            if sem.wait(timeout: .now() + 0.4) == .success { return result() }
            let gone = ctrl.peerClosed()
            if gone || Date() >= deadline {
                lock.lock()
                let raced = answered
                answered = true
                lock.unlock()
                if raced { sem.wait(); return result() }
                onExpire?(gone ? "gone" : "timeout")
                return (false, gone ? "gone" : "timeout", false)
            }
        }
    }
}

/// Receiving side (Android `Server.kt`): answers HELLOs, accepts offers and writes the parallel
/// data streams straight to disk.
public final class Receiver: @unchecked Sendable {
    public static let offerTimeout: TimeInterval = 60

    /// Show the offer to the user (called on a background thread). They must call `respond`.
    public var present: ((IncomingOffer) -> Void)?
    public var acceptMode: () -> AcceptMode = { .trusted }
    /// Whether to agree when a sender asks for per-chunk CRC32 checks.
    public var agreeCRC: () -> Bool = { true }
    public var saveDir: () -> URL = { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/FastDrop") }
    public var onStart: ((Transfer) -> Void)?
    public var onSaved: ((_ file: URL, _ from: String, _ size: Int64) -> Void)?
    /// A text or link offer was accepted (it arrives inside the offer; nothing else follows).
    public var onText: ((_ text: String, _ from: String) -> Void)?
    public var onListenError: ((String?) -> Void)?

    private let me: Identity
    private let gate: Gate
    private let port: UInt16
    private let stores: Stores
    private let lock = NSLock()
    private var sessions: [String: RxSession] = [:]
    private var started = false

    public init(identity: Identity, gate: Gate, port: UInt16 = Proto.port, stores: Stores = .shared) {
        self.me = identity; self.gate = gate; self.port = port; self.stores = stores
    }

    public func start() {
        guard !started else { return }
        started = true
        stores.resume.prune()
        let t = Thread { [weak self] in self?.acceptLoop() }
        t.name = "fd-server"; t.qualityOfService = .userInitiated; t.start()
    }

    private func listen() throws -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw FDError.errno("socket") }
        var one: Int32 = 1, buf = Int32(Proto.socketBuffer)
        let sz = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sz)
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sz)   // before bind -> accepted sockets get big windows
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if r != 0 || Darwin.listen(fd, 128) != 0 {
            let e = FDError.errno("Can't listen on port \(port)")
            Darwin.close(fd)
            throw e
        }
        return fd
    }

    private func acceptLoop() {
        while true {
            let fd: Int32
            do { fd = try listen() } catch {
                onListenError?(error.localizedDescription)
                Thread.sleep(forTimeInterval: 3)
                continue
            }
            onListenError?(nil)
            while true {
                var from = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let c = withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(fd, $0, &len) }
                }
                if c < 0 {
                    if errno == EINTR || errno == ECONNABORTED { continue }
                    break
                }
                let s = Sock(fd: c)
                let t = Thread { [weak self] in self?.handle(s) }
                t.name = "fd-conn"; t.qualityOfService = .userInitiated; t.start()
            }
            Darwin.close(fd)
            Thread.sleep(forTimeInterval: 1)
        }
    }

    private func handle(_ c: Sock) {
        defer { c.close() }
        do {
            c.setKeepAlive()
            c.setTimeout(10)   // anything that doesn't introduce itself in 10 s is dropped
            switch try c.readInt32() {
            case Proto.magicHello: try c.writeJSON(me.info())
            case Proto.magicCtrl: try offer(c)
            case Proto.magicData: try data(c)
            default: break
            }
        } catch {}
    }

    private func reply(_ c: Sock, _ accept: Bool, _ reason: String, extra: [String: Any] = [:]) {
        var o: [String: Any] = ["accept": accept, "reason": reason, "v": 2, "dev": me.id]
        for (k, v) in extra { o[k] = v }
        try? c.writeJSON(o)
    }

    /// Authenticated-offer check (see "Authenticated offers" in docs/PROTOCOL.md).
    private func isTrustedOffer(_ o: [String: Any], id: String, dev: String?, files: [(name: String, size: Int64)], text: String?) -> Bool {
        guard (o["v"] as? NSNumber)?.intValue ?? 1 >= 2, let dev, !dev.isEmpty,
              let auth = o["auth"] as? String, let ts = (o["ts"] as? NSNumber)?.int64Value,
              let t = stores.trust.device(dev), let key = Spec.unhex(t.key) else { return false }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard abs(now - ts) <= Spec.authWindowMs else { return false }
        guard Spec.verifyAuth(key: key, auth: auth, id: id, dev: dev, ts: ts,
                              filesDigest: Spec.filesDigest(files: files, text: text)) else { return false }
        return !stores.replay.seen(id)    // persisted, so a restart can't replay a captured offer
    }

    // MARK: control connection

    private func offer(_ c: Sock) throws {
        let o = try c.readJSON(max: 4 << 20)
        let text = o["text"] as? String
        let arr = (o["files"] as? [[String: Any]]) ?? []
        guard let sid = o["id"] as? String, !sid.isEmpty, sid.utf8.count <= 200, arr.count <= 10_000,
              text != nil ? (arr.isEmpty && !text!.isEmpty) : !arr.isEmpty else {
            reply(c, false, "invalid"); return
        }
        if let text, text.unicodeScalars.count > Spec.maxText { reply(c, false, "invalid"); return }
        let fromName = String(((o["name"] as? String) ?? "Unknown device").prefix(60))
        let fromType = (o["type"] as? String) ?? "phone"
        let v2 = ((o["v"] as? NSNumber)?.intValue ?? 1) >= 2
        let dev = v2 ? (o["dev"] as? String).flatMap { $0.isEmpty || $0.utf8.count > 200 ? nil : $0 } : nil
        var files: [OfferFile] = []
        var raw: [(name: String, size: Int64)] = []
        var total: Int64 = 0
        for f in arr {
            guard let name = f["name"] as? String, let size = (f["size"] as? NSNumber)?.int64Value,
                  size >= 0, size <= Proto.maxFileSize else { reply(c, false, "invalid"); return }
            let (sum, overflow) = total.addingReportingOverflow(size)
            guard !overflow else { reply(c, false, "invalid"); return }
            total = sum
            let label = (f["app"] as? [String: Any])?["l"] as? String
            files.append(OfferFile(name: Storage.cleanName(name), size: size, appLabel: label.map { String($0.prefix(80)) },
                                   rawName: name, mtime: v2 ? (f["mtime"] as? NSNumber)?.int64Value : nil))
            raw.append((name, size))
        }

        let trustedOffer = isTrustedOffer(o, id: sid, dev: dev, files: raw, text: text)
        let knownDevice = dev.map { stores.trust.device($0) != nil } ?? false
        let resumable = v2 && dev != nil && (o["chunk"] as? NSNumber)?.intValue == Proto.chunk && text == nil
        let wantCRC = v2 && (o["crc"] as? Bool) == true && text == nil
        let wantCtl = v2 && (o["ctl"] as? Bool) == true && text == nil

        guard gate.tryReserve() else { reply(c, false, "busy"); return }
        var reserved = true
        defer { if reserved { gate.release() } }

        let mode = acceptMode()
        let code = Spec.code(id: sid)
        let accepted: Bool
        var reason = ""
        var pair = false
        if mode == .everyone || (mode == .trusted && trustedOffer) {
            accepted = true
        } else if let present {
            let offer = IncomingOffer(from: fromName, fromType: fromType, files: files, total: total, timeout: Self.offerTimeout,
                                      text: text, code: code, trusted: trustedOffer,
                                      canTrust: dev != nil && !knownDevice)   // never replace a stored key from an offer
            c.setTimeout(0)
            present(offer)
            let r = offer.wait(ctrl: c)
            (accepted, reason, pair) = (r.accepted, r.reason, r.trust)
        } else {
            accepted = false; reason = "closed"
        }
        guard accepted else {
            gate.release(); reserved = false   // free first so an instant retry isn't "busy"
            reply(c, false, reason)
            return
        }
        c.setTimeout(10)

        var extra: [String: Any] = [:]
        if pair, let dev, stores.trust.device(dev) == nil { extra["trust"] = stores.trust.add(id: dev, name: fromName) }

        if let text {
            gate.release(); reserved = false
            reply(c, true, "", extra: extra)
            try? c.writeJSON(["ok": true])
            onText?(text, fromName)
            return
        }

        let t = Transfer(outgoing: false, peerName: fromName, peerType: fromType, total: total, names: files.map(\.name),
                         sizes: files.map(\.size))
        t.code = code
        if wantCtl { extra["ctl"] = true }
        let crc = wantCRC && agreeCRC()
        if crc { extra["crc"] = true }
        t.crcOn = crc
        let session: RxSession
        do {
            session = try RxSession(id: sid, files: files, dir: saveDir(), transfer: t, dev: resumable ? dev : nil, crc: crc,
                                    stores: stores) { [weak self] url, size in
                self?.onSaved?(url, fromName, size)
            }
        } catch {
            gate.release(); reserved = false
            reply(c, false, "storage")
            return
        }
        let have = session.haveJSON()
        if !have.isEmpty { extra["have"] = have }
        session.presetTransfer()
        let link = wantCtl ? CtlLink(sock: c) : nil
        if let link { t.enableCtl(link); t.skipHook = { [weak session] i in session?.skip(i) ?? false } }

        // The transfer owns the gate from here; it's released when the transfer finishes.
        reserved = false
        let gate = self.gate
        t.onFinish { _ in gate.release() }
        lock.lock(); sessions[sid] = session; lock.unlock()
        defer {
            session.close()
            lock.lock(); sessions[sid] = nil; lock.unlock()
        }
        t.register(c)
        onStart?(t)

        var accept: [String: Any] = ["accept": true, "reason": "", "v": 2, "dev": me.id]
        for (k, v) in extra { accept[k] = v }
        do { try c.writeJSON(accept) } catch {
            t.finish(.cancelled, "\(fromName) cancelled"); return
        }
        let reader = DispatchGroup()
        if let link {
            // Full duplex CTRL: read pause / resume / skip from the sender for as long as the transfer runs.
            c.setReadTimeout(0)
            reader.enter()
            let th = Thread {
                defer { reader.leave() }
                do { try link.read(transfer: t, other: nil) } catch {
                    if t.running && !session.complete { t.finish(.cancelled, "\(fromName) cancelled") }
                }
            }
            th.name = "fd-recv-ctl"; th.qualityOfService = .userInitiated; th.start()
        }
        defer {
            if link != nil { c.shutdown(); _ = reader.wait(timeout: .now() + 2) }   // reader must be gone before the fd closes
        }
        do { try session.start() } catch {
            t.finish(.failed, "Couldn't save: \(error.localizedDescription)"); return
        }

        var lastCheckpoint = Date()
        while t.running && !session.complete {
            session.waitDone(0.5)
            if session.complete { break }
            if link == nil && c.peerClosed() { t.finish(.cancelled, "\(fromName) cancelled"); break }   // with ctl the reader sees it
            // lastProgress stays "now" while paused, so a pause never trips the watchdog
            if Date().timeIntervalSince(t.lastProgress) > 30 { t.finish(.failed, "Transfer stalled"); break }
            if Date().timeIntervalSince(lastCheckpoint) >= 10 { session.checkpoint(); lastCheckpoint = Date() }
        }
        if session.complete && t.running {
            c.setTimeout(10)
            t.finish(.done, "Received")   // frees the gate first so an instant follow-up isn't "busy"
            if link != nil { t.sendFinalOK() } else { try? c.writeJSON(["ok": true]) }
        }
    }

    // MARK: data streams

    private func data(_ c: Sock) throws {
        let idLen = Int(try c.readInt32())
        guard (1...200).contains(idLen) else { throw FDError.protocolError("bad id") }
        let sid = String(decoding: try c.readData(idLen), as: UTF8.self)
        lock.lock(); let s = sessions[sid]; lock.unlock()
        guard let s else { throw FDError.protocolError("unknown session") }
        s.enter()
        defer { s.leave() }
        let t = s.transfer
        t.register(c)
        // A stream that goes quiet for 30 s is a stall. With ctl the sender may be paused, so the transfer's
        // watchdog (which ignores paused time) decides instead of a socket timeout.
        c.setTimeout(t.ctlOn ? 0 : 30)

        let cap = Proto.ioBuffer
        let buf = UnsafeMutableRawPointer.allocate(byteCount: cap, alignment: 16)
        let hdr = UnsafeMutableRawPointer.allocate(byteCount: 16, alignment: 8)
        defer { buf.deallocate(); hdr.deallocate() }
        let crcOn = s.crc
        do {
            while t.running {
                try c.readExact(hdr, 16)
                let fi = Int(Int32(bigEndian: hdr.loadUnaligned(as: Int32.self)))
                let off = Int64(bigEndian: hdr.loadUnaligned(fromByteOffset: 4, as: Int64.self))
                let len = Int64(Int32(bigEndian: hdr.loadUnaligned(fromByteOffset: 12, as: Int32.self)))
                if fi < 0 { break }
                // Every frame is exactly one chunk: aligned offset, 8 MB long except a file's last chunk.
                guard fi < s.files.count, off >= 0, len > 0, off % Int64(Proto.chunk) == 0,
                      off < s.files[fi].size, len == min(Int64(Proto.chunk), s.files[fi].size - off) else {
                    throw FDError.protocolError("bad frame")
                }
                // A skipped file's frames are still read (and CRC-checked) but their payload is thrown away.
                var discard = t.isSkipped(fi)
                let fd = try discard ? -1 : s.sink(fi)
                s.frameStart()
                do {
                    var pos = off, rem = len
                    var sum: UInt32 = 0
                    while rem > 0 {
                        let n = try c.readSome(buf, Int(min(rem, Int64(cap))))
                        if n == 0 { throw FDError.protocolError("Sender disconnected") }
                        if crcOn { sum = Spec.crc32(sum, buf.assumingMemoryBound(to: UInt8.self), n) }   // only when agreed
                        if !discard {
                            var w = 0
                            while w < n {
                                let r = pwrite(fd, buf + w, n - w, off_t(pos) + off_t(w))
                                if r < 0 { if errno == EINTR { continue }; throw FDError.errno("Write failed") }
                                w += r
                            }
                        }
                        pos += Int64(n); rem -= Int64(n)
                        if !t.add(n, file: fi) { discard = true }   // skipped meanwhile: stop writing, keep reading
                    }
                    if crcOn {
                        try c.readExact(buf, 4)
                        let want = UInt32(bigEndian: buf.loadUnaligned(as: UInt32.self))
                        if want != sum { throw FDError.dataCheck }
                    }
                    if !discard { try s.chunkDone(fi, off, len) }
                } catch {
                    s.frameEnd()
                    throw error
                }
                s.frameEnd()
            }
        } catch {
            if t.running && !s.complete {
                if case FDError.dataCheck = error { t.finish(.failed, "Data check failed") }
                else { t.finish(.failed, "Transfer interrupted: \(error.localizedDescription)") }
                s.wake()
            }
        }
    }
}

/// Files of one incoming transfer. Each is written to a hidden `.<name>.<id>.fdpart` file next to its final
/// place and renamed when complete, so Finder never shows half-received files. Protocol-2 sessions keep
/// the partials (and a resume record) when they end early; protocol-1 sessions delete them.
final class RxSession: @unchecked Sendable {
    let id: String
    let files: [OfferFile]
    let transfer: Transfer
    let crc: Bool
    private let dir: URL
    private let saved: (URL, Int64) -> Void
    private let stores: Stores
    private let resumeKeys: [String?]    // non-nil only for resumable (v2) sessions

    private let lock = NSLock()
    private let cond = NSCondition()
    private var fds: [Int32]
    private var partials: [URL?]
    private var got: [Int64]
    private var done: [Set<Int>]         // finished chunk indices per file
    private var dirty = false
    private var active = 0
    private let activeCond = NSCondition()
    private var finished: [Bool]
    private var reserved = Set<String>()
    private var remaining: Int
    private var inflight = 0             // DATA frames currently being read
    private var closed = false
    private(set) var complete = false
    private(set) var presentBytes: Int64 = 0

    init(id: String, files: [OfferFile], dir: URL, transfer: Transfer, dev: String?, crc: Bool, stores: Stores,
         saved: @escaping (URL, Int64) -> Void) throws {
        self.id = id; self.files = files; self.dir = dir; self.transfer = transfer; self.saved = saved
        self.crc = crc; self.stores = stores
        // Resume needs a known (positive) mtime: without it two versions of a same-size file could be spliced.
        var seenKeys = Set<String>()
        resumeKeys = files.map { f in
            guard let d = dev, let m = f.mtime, m > 0 else { return nil }
            let k = Spec.resumeKey(dev: d, name: f.rawName, size: f.size, mtime: m)
            return seenKeys.insert(k).inserted ? k : nil     // two identical entries: only the first may resume
        }
        fds = Array(repeating: -1, count: files.count)
        partials = Array(repeating: nil, count: files.count)
        got = Array(repeating: 0, count: files.count)
        done = Array(repeating: [], count: files.count)
        finished = Array(repeating: false, count: files.count)
        remaining = files.count
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard FileManager.default.isWritableFile(atPath: dir.path) else { throw FDError.system("Folder isn't writable") }
        restore()
    }

    /// Picks up partial files from earlier interrupted transfers.
    private func restore() {
        var used = Set<String>()
        for i in files.indices where files[i].size > 0 {
            guard let key = resumeKeys[i], let r = stores.resume.record(key), r.size == files[i].size,
                  belongs(r.path, toFile: i, key: key), !used.contains(r.path),
                  let attrs = try? FileManager.default.attributesOfItem(atPath: r.path),
                  (attrs[.size] as? NSNumber)?.int64Value == files[i].size else { continue }
            let set = Spec.indices(of: r.chunks, limit: Spec.chunkCount(size: files[i].size))
            guard !set.isEmpty else { continue }
            done[i] = set
            partials[i] = URL(fileURLWithPath: r.path)
            used.insert(r.path)
            got[i] = set.reduce(Int64(0)) { $0 + Spec.chunkLength(index: $1, size: files[i].size) }
            presentBytes += got[i]
        }
    }

    /// A resume record's partial must be one this file (and key) could have produced: same folder, same
    /// name and the key's tag. Otherwise a stale or foreign record could point at someone else's data.
    private func belongs(_ path: String, toFile i: Int, key: String) -> Bool {
        let rel = files[i].name
        let parent = (rel as NSString).deletingLastPathComponent
        let folder = parent.isEmpty ? dir : dir.appendingPathComponent(parent, isDirectory: true)
        let u = URL(fileURLWithPath: path)
        let name = u.lastPathComponent
        return u.deletingLastPathComponent().standardizedFileURL.path == folder.standardizedFileURL.path
            && name.hasPrefix(".\((rel as NSString).lastPathComponent).\(key.prefix(12)).") && name.hasSuffix(".fdpart")
    }

    /// `have` for the accept reply: done chunk ranges of reopened partials, by file index.
    func haveJSON() -> [String: Any] {
        var out: [String: Any] = [:]
        for i in files.indices where !done[i].isEmpty { out[String(i)] = Spec.ranges(of: done[i]) }
        return out
    }

    /// Empty files have no frames: create them right away. A partial that already holds every chunk is finished too.
    func start() throws {
        for (i, f) in files.enumerated() {
            if f.size == 0 { _ = try sink(i); try finishFile(i) }
            else if got[i] == f.size { _ = try sink(i); try finishFile(i) }
        }
    }

    /// The open partial file for index `i`, created (or reopened when resuming) on first use.
    func sink(_ i: Int) throws -> Int32 {
        lock.lock(); defer { lock.unlock() }
        if closed { throw FDError.cancelled }
        if fds[i] >= 0 { return fds[i] }
        let rel = files[i].name
        if let p = partials[i] {
            let fd = open(p.path, O_RDWR)
            guard fd >= 0 else { throw FDError.errno("Can't reopen \(rel)") }
            fds[i] = fd
            return fd
        }
        let parent = (rel as NSString).deletingLastPathComponent
        let folder = parent.isEmpty ? dir : dir.appendingPathComponent(parent, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let tag = resumeKeys[i].map { String($0.prefix(12)) } ?? String(id.prefix(8))
        // unique per file slot, so two offered files called x.bin never share one partial
        let tmp = folder.appendingPathComponent(".\((rel as NSString).lastPathComponent).\(tag).\(i).fdpart")
        let fd = open(tmp.path, O_RDWR | O_CREAT | O_TRUNC, 0o644)
        guard fd >= 0 else { throw FDError.errno("Can't create \(rel)") }
        if files[i].size > 0 { _ = ftruncate(fd, off_t(files[i].size)) }
        fds[i] = fd
        partials[i] = tmp
        return fd
    }

    /// A chunk counts only now, after its whole payload is written (and its CRC passed).
    /// A repeated chunk would let `got` reach the file size with holes left in the file, so reject it.
    func chunkDone(_ i: Int, _ off: Int64, _ len: Int64) throws {
        lock.lock()
        guard done[i].insert(Int(off / Int64(Proto.chunk))).inserted else {
            lock.unlock()
            throw FDError.protocolError("duplicate chunk")
        }
        got[i] += len
        dirty = true
        let full = got[i] == files[i].size
        lock.unlock()
        if full { try finishFile(i) }
    }

    /// Per-file bytes already in place from earlier sessions (resume).
    func presetTransfer() {
        for i in files.indices where got[i] > 0 { transfer.preset(got[i], file: i) }
    }

    /// A data stream starts / ends a frame. The session isn't complete while a frame is still being read, so the
    /// final "ok" never goes out ahead of frames of skipped files that are still arriving.
    func frameStart() { cond.lock(); inflight += 1; cond.unlock() }
    func frameEnd() { cond.lock(); inflight -= 1; evalComplete(); cond.unlock() }

    /// Caller holds `cond`.
    private func evalComplete() {
        if remaining == 0 && inflight == 0 && !complete { complete = true; cond.broadcast() }
    }

    /// Skip file `i` (section 7): true when it applied. Ignored when `i` is out of range, already skipped or complete.
    /// Counts `i` as done; its partial file and resume record are deleted when the session ends.
    func skip(_ i: Int) -> Bool {
        guard transfer.markSkipped(i) else { return false }
        cond.lock(); remaining -= 1; evalComplete(); cond.unlock()
        return true
    }

    private func finishFile(_ i: Int) throws {
        lock.lock()
        guard !finished[i], !closed, !transfer.isSkipped(i), let tmp = partials[i] else { lock.unlock(); return }
        finished[i] = true
        if fds[i] >= 0 { Darwin.close(fds[i]); fds[i] = -1 }
        let dest = uniqueURL(for: files[i].name)
        lock.unlock()

        try FileManager.default.moveItem(at: tmp, to: dest)
        lock.lock(); partials[i] = nil; lock.unlock()
        if let key = resumeKeys[i] { stores.resume.remove(key) }
        saved(dest, files[i].size)

        cond.lock()
        remaining -= 1
        evalComplete()
        cond.unlock()
    }

    /// "photo.jpg" -> "photo (1).jpg" if taken on disk or earlier in this transfer. Caller holds `lock`.
    private func uniqueURL(for rel: String) -> URL {
        let base = dir.appendingPathComponent(rel)
        let ext = base.pathExtension
        let stem = base.deletingPathExtension().lastPathComponent
        let folder = base.deletingLastPathComponent()
        var url = base, k = 1
        while FileManager.default.fileExists(atPath: url.path) || reserved.contains(url.path.lowercased()) {
            url = folder.appendingPathComponent(ext.isEmpty ? "\(stem) (\(k))" : "\(stem) (\(k)).\(ext)")
            k += 1
        }
        reserved.insert(url.path.lowercased())
        return url
    }

    /// Writes resume records for unfinished files that have at least one done chunk.
    func checkpoint() {
        lock.lock()
        guard !finished.isEmpty else { lock.unlock(); return }
        let wasDirty = dirty
        dirty = false
        var recs: [ResumeRecord] = []
        for i in files.indices where !finished[i] && !done[i].isEmpty && !transfer.isSkipped(i) {
            guard let key = resumeKeys[i], let p = partials[i] else { continue }
            recs.append(ResumeRecord(key: key, path: p.path, size: files[i].size, chunks: Spec.ranges(of: done[i]), updated: Date()))
        }
        lock.unlock()
        if wasDirty { recs.forEach { stores.resume.save($0) } }
    }

    func waitDone(_ seconds: TimeInterval) {
        cond.lock()
        if !complete { cond.wait(until: Date().addingTimeInterval(seconds)) }
        cond.unlock()
    }

    func wake() { cond.lock(); cond.broadcast(); cond.unlock() }

    /// Data threads bracket their work with these so `close` never frees an fd mid-`pwrite`.
    func enter() { activeCond.lock(); active += 1; activeCond.unlock() }
    func leave() { activeCond.lock(); active -= 1; activeCond.broadcast(); activeCond.unlock() }

    /// Closes open files. Finished files are kept. Unfinished ones keep their partial (and record) in a
    /// protocol-2 session when at least one chunk is done; everything else is deleted.
    func close() {
        lock.lock(); closed = true; lock.unlock()
        // Streams were shut down by Transfer.finish; give them a moment to leave pwrite.
        let until = Date().addingTimeInterval(3)
        activeCond.lock()
        while active > 0 && activeCond.wait(until: until) {}
        activeCond.unlock()
        lock.lock()
        for i in fds.indices where fds[i] >= 0 { Darwin.close(fds[i]); fds[i] = -1 }
        lock.unlock()

        checkpointFinal()
        lock.lock()
        var drop: [URL] = []
        for i in files.indices where !finished[i] {
            guard let p = partials[i] else { continue }
            if resumeKeys[i] == nil || done[i].isEmpty || transfer.isSkipped(i) {   // skipped: never kept for resume
                drop.append(p)
                if let key = resumeKeys[i] { stores.resume.remove(key) }
            }
        }
        lock.unlock()
        drop.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func checkpointFinal() {
        lock.lock(); dirty = true; lock.unlock()
        checkpoint()
    }
}

public enum Storage {
    /// Same rules as Android `Storage.cleanName`: keep sub-folders, drop "." / ".." and unsafe characters.
    public static func cleanName(_ rel: String) -> String {
        let bad = Set(":*?\"<>|")
        let parts = rel.split(whereSeparator: { $0 == "/" || $0 == "\\" }).map { p -> String in
            String(String(p.filter { $0 >= " " && !bad.contains($0) }).trimmingCharacters(in: .whitespaces).prefix(120))
        }.filter { !$0.isEmpty && $0 != "." && $0 != ".." }
        return parts.isEmpty ? "file" : parts.joined(separator: "/")
    }
}
