import Darwin
import Foundation

public struct SendItem: Sendable, Hashable {
    public let url: URL
    public let name: String   // relative path the receiver will use ("Folder/sub/file.txt")
    public let size: Int64

    public init(url: URL, name: String, size: Int64) { self.url = url; self.name = name; self.size = size }

    /// Files stay as they are; folders become every regular file inside them, keeping the folder structure.
    public static func expand(_ urls: [URL]) -> [SendItem] {
        var out: [SendItem] = []
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .isDirectoryKey]
        for url in urls {
            let v = try? url.resourceValues(forKeys: Set(keys))
            if v?.isDirectory == true {
                let base = url.deletingLastPathComponent().standardizedFileURL.path
                guard let e = fm.enumerator(at: url, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else { continue }
                for case let f as URL in e {
                    guard let fv = try? f.resourceValues(forKeys: Set(keys)), fv.isRegularFile == true else { continue }
                    var rel = String(f.standardizedFileURL.path.dropFirst(base.count))
                    if rel.hasPrefix("/") { rel.removeFirst() }
                    out.append(SendItem(url: f, name: rel, size: Int64(fv.fileSize ?? 0)))
                }
            } else if v?.isRegularFile == true {
                out.append(SendItem(url: url, name: url.lastPathComponent, size: Int64(v?.fileSize ?? 0)))
            }
        }
        return out
    }
}

/// One outgoing transfer (Android `Sender.kt`): a control connection, then N parallel data
/// streams pulling 8 MB chunks from a shared queue and pushing them with zero-copy `sendfile()`.
public final class SendJob: @unchecked Sendable {
    public let peer: Peer
    public let items: [SendItem]
    public var onStatus: ((String) -> Void)?
    /// The receiver accepted; progress is on the transfer from now on.
    public var onStart: ((Transfer) -> Void)?

    private let me: Identity
    private let gate: Gate
    private let streams: Int
    private let lock = NSLock()
    private var ctrl: Sock?
    private var cancelled = false
    private var transfer: Transfer?

    public init(peer: Peer, items: [SendItem], identity: Identity, gate: Gate, streams: Int) {
        self.peer = peer; self.items = items; self.me = identity; self.gate = gate
        self.streams = max(1, min(8, streams))
    }

    public func cancel() {
        lock.lock()
        cancelled = true
        let c = ctrl, t = transfer
        lock.unlock()
        if let t { t.cancel() } else { c?.shutdown() }
    }

    private var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    /// Blocking: run on a background thread; returns when everything is over. Returns nil if a transfer
    /// started (its outcome is on the `Transfer` from `onStart`), or a message explaining why nothing was sent.
    public func run() -> String? {
        guard gate.tryReserve() else { return "A transfer is already running" }
        var handedOff = false
        defer { if !handedOff { gate.release() } }

        // Re-read sizes right before sending; drop files that disappeared.
        let items = self.items.compactMap { it -> SendItem? in
            guard let v = try? it.url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]), v.isRegularFile == true else { return nil }
            return SendItem(url: it.url, name: it.name, size: Int64(v.fileSize ?? Int(it.size)))
        }
        guard !items.isEmpty else { return "Couldn't read the selected files" }

        let c: Sock
        do {
            onStatus?("Connecting to \(peer.name)…")
            c = try Sock.connect(host: peer.ip, port: peer.port, timeout: 5)
        } catch {
            return isCancelled ? "Cancelled" : "Can't reach \(peer.name) (\(peer.ip))"
        }
        defer { c.close() }
        lock.lock(); ctrl = c; let stop = cancelled; lock.unlock()
        if stop { return "Cancelled" }

        let sid = UUID().uuidString
        let resp: [String: Any]
        do {
            c.setNoDelay()
            c.setKeepAlive()
            onStatus?("Waiting for \(peer.name) to accept…")
            var files: [[String: Any]] = []
            for it in items { files.append(["name": it.name, "size": it.size]) }
            try c.write(withUnsafeBytes(of: Proto.magicCtrl.bigEndian) { Data($0) })
            try c.writeJSON(["id": sid, "name": me.name, "type": me.type, "files": files])
            c.setTimeout(75)   // the receiver has 60 s to answer
            resp = try c.readJSON()
        } catch {
            if isCancelled { return "Cancelled" }
            if case FDError.timeout = error { return "\(peer.name) didn't answer" }
            return "Couldn't send to \(peer.name): \(error.localizedDescription)"
        }
        guard resp["accept"] as? Bool == true else {
            switch resp["reason"] as? String {
            case "busy": return "\(peer.name) is busy with another transfer"
            case "closed": return "FastDrop isn't open on \(peer.name)"
            case "timeout": return "\(peer.name) didn't respond"
            case "invalid": return "\(peer.name) rejected the file list"
            case "storage": return "\(peer.name) can't save files right now"
            default: return "\(peer.name) declined"
            }
        }

        let total = items.reduce(Int64(0)) { $0 + $1.size }
        let t = Transfer(outgoing: true, peerName: peer.name, peerType: peer.type, total: total, names: items.map(\.name))
        let gate = self.gate
        t.onFinish { _ in gate.release() }
        handedOff = true
        lock.lock(); transfer = t; let stopNow = cancelled; lock.unlock()
        t.register(c)
        onStart?(t)
        if stopNow { t.cancel() }

        stream(items: items, sid: sid, ctrl: c, transfer: t)
        return nil
    }

    private struct Chunk { let file: Int; let off: Int64; let len: Int }

    private func stream(items: [SendItem], sid: String, ctrl: Sock, transfer t: Transfer) {
        var chunks: [Chunk] = []
        for (i, it) in items.enumerated() {
            var off: Int64 = 0
            while off < it.size {
                let len = Int(min(Int64(Proto.chunk), it.size - off))
                chunks.append(Chunk(file: i, off: off, len: len)); off += Int64(len)
            }
        }
        let qlock = NSLock()
        var next = 0
        func take() -> Chunk? {
            qlock.lock(); defer { qlock.unlock() }
            guard next < chunks.count else { return nil }
            next += 1
            return chunks[next - 1]
        }

        let n = max(1, min(streams, chunks.count))
        let group = DispatchGroup()
        let errLock = NSLock()
        var firstError: Error?
        for k in 0..<n {
            group.enter()
            let th = Thread {
                defer { group.leave() }
                do { try self.worker(items: items, sid: sid, take: take, transfer: t) } catch {
                    errLock.lock()
                    let first = firstError == nil
                    if first { firstError = error }
                    errLock.unlock()
                    if first && t.running { t.finish(.failed, "Transfer failed: \(error.localizedDescription)") }
                }
            }
            th.name = "fd-send-\(k)"; th.qualityOfService = .userInitiated; th.start()
        }
        group.wait()
        guard t.running else { return }
        t.sentAll = true

        // Every byte is out; the receiver answers once it has flushed everything to disk.
        ctrl.setTimeout(60)
        do {
            let fin = try ctrl.readJSON()
            if fin["ok"] as? Bool == true { t.finish(.done, "Sent") } else { t.finish(.failed, "Receiver reported an error") }
        } catch {
            t.finish(.failed, "\(peer.name) didn't confirm: \(error.localizedDescription)")
        }
    }

    private func worker(items: [SendItem], sid: String, take: () -> Chunk?, transfer t: Transfer) throws {
        let s = try Sock.connect(host: peer.ip, port: peer.port, timeout: 5, sendBuffer: Proto.socketBuffer)
        defer { s.close() }
        t.register(s)
        s.setTimeout(30)

        var hello = Data()
        let idb = Data(sid.utf8)
        hello.appendBE(Proto.magicData); hello.appendBE(Int32(idb.count)); hello.append(idb)
        try s.write(hello)

        var fd: Int32 = -1
        var cur = -1
        defer { if fd >= 0 { close(fd) } }
        var fallback: UnsafeMutableRawPointer?
        defer { fallback?.deallocate() }
        var useSendfile = true

        while t.running, let c = take() {
            if c.file != cur {
                if fd >= 0 { close(fd) }
                fd = open(items[c.file].url.path, O_RDONLY)
                guard fd >= 0 else { throw FDError.errno("Can't open \(items[c.file].name)") }
                cur = c.file
            }
            var hdr = Data(capacity: 16)
            hdr.appendBE(Int32(c.file)); hdr.appendBE(c.off); hdr.appendBE(Int32(c.len))
            try s.write(hdr)

            var pos = c.off
            var rem = Int64(c.len)
            while rem > 0 {
                guard t.running else { throw FDError.cancelled }
                let slice = min(rem, Int64(Proto.ioBuffer))
                var sent: Int64 = 0
                if useSendfile {
                    var len = off_t(slice)
                    let r = sendfile(fd, s.fd, off_t(pos), &len, nil, 0)
                    sent = Int64(len)
                    if r != 0 {
                        let e = errno
                        if e == ENOTSUP || e == EOPNOTSUPP || e == ENOTSOCK || e == EINVAL, sent == 0 {
                            useSendfile = false   // e.g. network volumes: fall back to read/write
                            continue
                        }
                        if e == EAGAIN, sent == 0 { throw FDError.timeout }
                        if e != EAGAIN && e != EINTR { throw FDError.system("Send failed: \(String(cString: strerror(e)))") }
                    }
                    if r == 0 && sent == 0 { throw FDError.protocolError("\(items[c.file].name) got shorter while sending") }
                } else {
                    let buf = fallback ?? UnsafeMutableRawPointer.allocate(byteCount: Proto.ioBuffer, alignment: 16)
                    fallback = buf
                    let r = pread(fd, buf, Int(slice), off_t(pos))
                    if r < 0 { if errno == EINTR { continue }; throw FDError.errno("Read failed") }
                    if r == 0 { throw FDError.protocolError("\(items[c.file].name) got shorter while sending") }
                    try s.writeAll(buf, r)
                    sent = Int64(r)
                }
                pos += sent; rem -= sent
                if sent > 0 { t.add(Int(sent)) }
            }
        }
        guard t.running else { throw FDError.cancelled }
        var end = Data(capacity: 16)
        end.appendBE(Int32(-1)); end.appendBE(Int64(0)); end.appendBE(Int32(0))
        // Completion is decided by bytes received, not end markers: a late stream whose session the
        // receiver already closed must not fail a finished transfer.
        try? s.write(end)
    }
}
