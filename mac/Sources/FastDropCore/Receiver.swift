import Darwin
import Foundation

public struct OfferFile: Sendable, Hashable {
    public let name: String      // cleaned relative path, may contain "/"
    public let size: Int64
    public let appLabel: String? // set when the phone sends an app (base + split APKs)
}

/// A file offer waiting for the user to accept or decline.
public final class IncomingOffer: @unchecked Sendable, Identifiable {
    public let id = UUID()
    public let from: String
    public let fromType: String
    public let files: [OfferFile]
    public let total: Int64
    public let deadline: Date

    private let sem = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var answered = false
    private var accepted = false

    /// Fires when the offer goes away without the user answering: "timeout", or "gone" when the sender gave up.
    public var onExpire: ((String) -> Void)?

    init(from: String, fromType: String, files: [OfferFile], total: Int64, timeout: TimeInterval) {
        self.from = from; self.fromType = fromType; self.files = files; self.total = total
        self.deadline = Date().addingTimeInterval(timeout)
    }

    public func respond(_ accept: Bool) {
        lock.lock()
        guard !answered else { lock.unlock(); return }
        answered = true; accepted = accept
        lock.unlock()
        sem.signal()
    }

    /// Waits for the user, giving up at the deadline or when the sender hangs up.
    func wait(ctrl: Sock) -> (accepted: Bool, reason: String) {
        while true {
            if sem.wait(timeout: .now() + 0.4) == .success {
                lock.lock(); defer { lock.unlock() }
                return (accepted, accepted ? "" : "declined")
            }
            let gone = ctrl.peerClosed()
            if gone || Date() >= deadline {
                lock.lock()
                let raced = answered
                answered = true
                lock.unlock()
                if raced { sem.wait(); lock.lock(); defer { lock.unlock() }; return (accepted, accepted ? "" : "declined") }
                onExpire?(gone ? "gone" : "timeout")
                return (false, gone ? "gone" : "timeout")
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
    public var autoAccept: () -> Bool = { false }
    public var saveDir: () -> URL = { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/FastDrop") }
    public var onStart: ((Transfer) -> Void)?
    public var onSaved: ((_ file: URL, _ from: String, _ size: Int64) -> Void)?
    public var onListenError: ((String?) -> Void)?

    private let me: Identity
    private let gate: Gate
    private let port: UInt16
    private let lock = NSLock()
    private var sessions: [String: RxSession] = [:]
    private var started = false

    public init(identity: Identity, gate: Gate, port: UInt16 = Proto.port) {
        self.me = identity; self.gate = gate; self.port = port
    }

    public func start() {
        guard !started else { return }
        started = true
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

    private func reply(_ c: Sock, _ accept: Bool, _ reason: String) {
        try? c.writeJSON(["accept": accept, "reason": reason])
    }

    // MARK: control connection

    private func offer(_ c: Sock) throws {
        let o = try c.readJSON(max: 4 << 20)
        guard let sid = o["id"] as? String, !sid.isEmpty, sid.utf8.count <= 200,
              let arr = o["files"] as? [[String: Any]], !arr.isEmpty, arr.count <= 10_000 else {
            reply(c, false, "invalid"); return
        }
        let fromName = String(((o["name"] as? String) ?? "Unknown device").prefix(60))
        let fromType = (o["type"] as? String) ?? "phone"
        var files: [OfferFile] = []
        var total: Int64 = 0
        for f in arr {
            guard let name = f["name"] as? String, let size = (f["size"] as? NSNumber)?.int64Value,
                  size >= 0, size <= Proto.maxFileSize else { reply(c, false, "invalid"); return }
            let (sum, overflow) = total.addingReportingOverflow(size)
            guard !overflow else { reply(c, false, "invalid"); return }
            total = sum
            let label = (f["app"] as? [String: Any])?["l"] as? String
            files.append(OfferFile(name: Storage.cleanName(name), size: size, appLabel: label.map { String($0.prefix(80)) }))
        }

        guard gate.tryReserve() else { reply(c, false, "busy"); return }
        var reserved = true
        defer { if reserved { gate.release() } }

        let accepted: Bool
        var reason = ""
        if autoAccept() {
            accepted = true
        } else if let present {
            let offer = IncomingOffer(from: fromName, fromType: fromType, files: files, total: total, timeout: Self.offerTimeout)
            c.setTimeout(0)
            present(offer)
            (accepted, reason) = offer.wait(ctrl: c)
        } else {
            accepted = false; reason = "closed"
        }
        guard accepted else {
            gate.release(); reserved = false   // free first so an instant retry isn't "busy"
            reply(c, false, reason)
            return
        }

        let t = Transfer(outgoing: false, peerName: fromName, peerType: fromType, total: total, names: files.map(\.name))
        let session: RxSession
        do {
            session = try RxSession(id: sid, files: files, dir: saveDir(), transfer: t) { [weak self] url, size in
                self?.onSaved?(url, fromName, size)
            }
        } catch {
            gate.release(); reserved = false
            reply(c, false, "storage")
            return
        }
        // The transfer owns the gate from here; it's released when the transfer finishes.
        reserved = false
        let gate = self.gate
        t.onFinish { _ in gate.release() }
        lock.lock(); sessions[sid] = session; lock.unlock()
        defer {
            session.close(deleteUnfinished: true)
            lock.lock(); sessions[sid] = nil; lock.unlock()
        }
        t.register(c)
        onStart?(t)

        do { try c.writeJSON(["accept": true, "reason": ""]) } catch {
            t.finish(.cancelled, "\(fromName) cancelled"); return
        }
        do { try session.start() } catch {
            t.finish(.failed, "Couldn't save: \(error.localizedDescription)"); return
        }

        while t.running && !session.complete {
            session.waitDone(0.5)
            if session.complete { break }
            if c.peerClosed() { t.finish(.cancelled, "\(fromName) cancelled"); break }
            if Date().timeIntervalSince(t.lastProgress) > 30 { t.finish(.failed, "Transfer stalled"); break }
        }
        if session.complete && t.running {
            c.setTimeout(10)
            t.finish(.done, "Received")   // frees the gate first so an instant follow-up isn't "busy"
            try? c.writeJSON(["ok": true])
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
        c.setTimeout(30)   // a stream that goes quiet for 30 s is a stall

        let cap = Proto.ioBuffer
        let buf = UnsafeMutableRawPointer.allocate(byteCount: cap, alignment: 16)
        let hdr = UnsafeMutableRawPointer.allocate(byteCount: 16, alignment: 8)
        defer { buf.deallocate(); hdr.deallocate() }
        do {
            while t.running {
                try c.readExact(hdr, 16)
                let fi = Int(Int32(bigEndian: hdr.loadUnaligned(as: Int32.self)))
                let off = Int64(bigEndian: hdr.loadUnaligned(fromByteOffset: 4, as: Int64.self))
                let len = Int64(Int32(bigEndian: hdr.loadUnaligned(fromByteOffset: 12, as: Int32.self)))
                if fi < 0 { break }
                guard fi < s.files.count, off >= 0, len >= 0, off <= s.files[fi].size - len else {
                    throw FDError.protocolError("bad frame")
                }
                let fd = try s.sink(fi)
                var pos = off, rem = len
                while rem > 0 {
                    let n = try c.readSome(buf, Int(min(rem, Int64(cap))))
                    if n == 0 { throw FDError.protocolError("Sender disconnected") }
                    var w = 0
                    while w < n {
                        let r = pwrite(fd, buf + w, n - w, off_t(pos) + off_t(w))
                        if r < 0 { if errno == EINTR { continue }; throw FDError.errno("Write failed") }
                        w += r
                    }
                    pos += Int64(n); rem -= Int64(n)
                    t.add(n)
                }
                try s.chunkDone(fi, off, len)
            }
        } catch {
            if t.running && !s.complete {
                t.finish(.failed, "Transfer interrupted: \(error.localizedDescription)")
                s.wake()
            }
        }
    }
}

/// Files of one incoming transfer. Each is written to a hidden `.part` file and renamed when complete,
/// so Finder never shows half-received files.
final class RxSession: @unchecked Sendable {
    let id: String
    let files: [OfferFile]
    let transfer: Transfer
    private let dir: URL
    private let saved: (URL, Int64) -> Void

    private let lock = NSLock()
    private let cond = NSCondition()
    private var fds: [Int32]
    private var temps: [URL?]
    private var got: [Int64]
    private var chunkOffsets: [Set<Int64>]
    private var active = 0
    private let activeCond = NSCondition()
    private var finished: [Bool]
    private var reserved = Set<String>()
    private var remaining: Int
    private var closed = false
    private(set) var complete = false

    init(id: String, files: [OfferFile], dir: URL, transfer: Transfer, saved: @escaping (URL, Int64) -> Void) throws {
        self.id = id; self.files = files; self.dir = dir; self.transfer = transfer; self.saved = saved
        fds = Array(repeating: -1, count: files.count)
        temps = Array(repeating: nil, count: files.count)
        got = Array(repeating: 0, count: files.count)
        chunkOffsets = Array(repeating: [], count: files.count)
        finished = Array(repeating: false, count: files.count)
        remaining = files.count
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard FileManager.default.isWritableFile(atPath: dir.path) else { throw FDError.system("Folder isn't writable") }
    }

    /// Empty files have no frames: create them right away.
    func start() throws {
        for (i, f) in files.enumerated() where f.size == 0 {
            _ = try sink(i)
            try finishFile(i)
        }
    }

    /// The open temp file for index `i`, created on first use.
    func sink(_ i: Int) throws -> Int32 {
        lock.lock(); defer { lock.unlock() }
        if closed { throw FDError.cancelled }
        if fds[i] >= 0 { return fds[i] }
        let rel = files[i].name
        let parent = (rel as NSString).deletingLastPathComponent
        let folder = parent.isEmpty ? dir : dir.appendingPathComponent(parent, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let tmp = folder.appendingPathComponent(".fastdrop-\(id.prefix(8))-\(i).part")
        let fd = open(tmp.path, O_RDWR | O_CREAT | O_TRUNC, 0o644)
        guard fd >= 0 else { throw FDError.errno("Can't create \(rel)") }
        if files[i].size > 0 { _ = ftruncate(fd, off_t(files[i].size)) }
        fds[i] = fd
        temps[i] = tmp
        return fd
    }

    /// A repeated chunk would let `got` reach the file size with holes left in the file, so reject it.
    func chunkDone(_ i: Int, _ off: Int64, _ len: Int64) throws {
        lock.lock()
        guard chunkOffsets[i].insert(off).inserted else {
            lock.unlock()
            throw FDError.protocolError("duplicate chunk")
        }
        got[i] += len
        let full = got[i] == files[i].size
        lock.unlock()
        if full { try finishFile(i) }
    }

    private func finishFile(_ i: Int) throws {
        lock.lock()
        guard !finished[i], !closed, let tmp = temps[i] else { lock.unlock(); return }
        finished[i] = true
        Darwin.close(fds[i]); fds[i] = -1
        let dest = uniqueURL(for: files[i].name)
        lock.unlock()

        try FileManager.default.moveItem(at: tmp, to: dest)
        lock.lock(); temps[i] = nil; lock.unlock()
        saved(dest, files[i].size)

        cond.lock()
        remaining -= 1
        if remaining == 0 { complete = true; cond.broadcast() }
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

    func waitDone(_ seconds: TimeInterval) {
        cond.lock()
        if !complete { cond.wait(until: Date().addingTimeInterval(seconds)) }
        cond.unlock()
    }

    func wake() { cond.lock(); cond.broadcast(); cond.unlock() }

    /// Data threads bracket their work with these so `close` never frees an fd mid-`pwrite`.
    func enter() { activeCond.lock(); active += 1; activeCond.unlock() }
    func leave() { activeCond.lock(); active -= 1; activeCond.broadcast(); activeCond.unlock() }

    /// Closes open files. Finished files are kept; half-written temp files are deleted.
    func close(deleteUnfinished: Bool) {
        lock.lock(); closed = true; lock.unlock()
        // Streams were shut down by Transfer.finish; give them a moment to leave pwrite.
        let until = Date().addingTimeInterval(3)
        activeCond.lock()
        while active > 0 && activeCond.wait(until: until) {}
        activeCond.unlock()
        lock.lock()
        for i in fds.indices where fds[i] >= 0 { Darwin.close(fds[i]); fds[i] = -1 }
        let leftovers = temps.compactMap { $0 }
        lock.unlock()
        if deleteUnfinished { leftovers.forEach { try? FileManager.default.removeItem(at: $0) } }
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
