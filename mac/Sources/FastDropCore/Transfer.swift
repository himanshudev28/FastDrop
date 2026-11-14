import Foundation

public enum TransferState: String, Sendable {
    case running, done, failed, cancelled
}

/// One transfer in either direction. Progress is a lock-protected counter the UI samples.
public final class Transfer: @unchecked Sendable, Identifiable {
    public let id = UUID()
    public let outgoing: Bool
    public let peerName: String
    public let peerType: String
    public let fileCount: Int
    public let names: [String]
    public let startedAt = Date()

    private let lock = NSLock()
    private var _total: Int64
    private var _done: Int64 = 0
    private var _state: TransferState = .running
    private var _message = ""
    private var _lastProgress = Date()
    private var sockets: [Sock] = []
    /// Sender: all bytes are written; only the receiver's final "ok" is outstanding.
    public var sentAll = false

    // Per-file bookkeeping for pause/skip (empty for text transfers): sizes, bytes counted so far, skipped files.
    private let sizes: [Int64]
    private var fileDone: [Int64]
    private var _skipped = Set<Int>()
    private var _paused = false

    private var _baseline: Int64 = 0
    /// Receiver: the confirmation code the sender shows. Sender: set when the offer went out.
    public var code: String?
    /// Sender: a text/link offer (no data streams).
    public var isText = false
    /// Per-chunk CRC32 was agreed for this transfer.
    public var crcOn = false

    /// Pause/resume/skip (protocol section 7) was agreed for this transfer.
    public private(set) var ctlOn = false
    private var link: CtlLink?
    /// Receiver sets this so a skip also updates its session; returns true when the skip applied.
    var skipHook: ((Int) -> Bool)?
    /// Called for every control message: `("pause", sent: true)` etc. (CLI test hook / logging).
    public var onControl: ((_ message: String, _ sent: Bool) -> Void)?
    private let pauseCond = NSCondition()

    private var finishHandlers: [(Transfer) -> Void] = []

    /// Runs once, on the thread that ends the transfer. Runs immediately if it already ended.
    public func onFinish(_ h: @escaping (Transfer) -> Void) {
        lock.lock()
        if _state == .running { finishHandlers.append(h); lock.unlock(); return }
        lock.unlock()
        h(self)
    }

    public init(outgoing: Bool, peerName: String, peerType: String, total: Int64, names: [String], sizes: [Int64] = []) {
        self.outgoing = outgoing; self.peerName = peerName; self.peerType = peerType
        self._total = total; self.fileCount = names.count; self.names = Array(names.prefix(50))
        self.sizes = sizes; self.fileDone = Array(repeating: 0, count: sizes.count)
    }

    /// Bytes this transfer expects to move in all; shrinks when files are skipped.
    public var total: Int64 { lock.lock(); defer { lock.unlock() }; return _total }
    public var done: Int64 { lock.lock(); defer { lock.unlock() }; return _done }
    public var state: TransferState { lock.lock(); defer { lock.unlock() }; return _state }
    public var message: String { lock.lock(); defer { lock.unlock() }; return _message }
    public var running: Bool { state == .running }
    /// When data last moved. While paused this is "now", so no-progress watchdogs never fire.
    public var lastProgress: Date {
        lock.lock(); defer { lock.unlock() }
        return _paused ? Date() : _lastProgress
    }

    /// Bytes that were already at the receiver when this session started (resume).
    public var baseline: Int64 { lock.lock(); defer { lock.unlock() }; return _baseline }
    /// Bytes moved in this session only.
    public var sessionDone: Int64 { lock.lock(); defer { lock.unlock() }; return _done - _baseline }

    /// Counts bytes that are already in place, before any data moves.
    public func preset(_ n: Int64, file: Int? = nil) {
        lock.lock(); _done += n; _baseline += n
        if let f = file, fileDone.indices.contains(f) { fileDone[f] += n }
        lock.unlock()
    }

    /// Counts moved bytes. Returns false when `file` was skipped (its bytes are not counted any more).
    @discardableResult
    public func add(_ n: Int, file: Int? = nil) -> Bool {
        lock.lock(); defer { lock.unlock() }
        _lastProgress = Date()
        if let f = file, fileDone.indices.contains(f) {
            if _skipped.contains(f) { return false }
            fileDone[f] += Int64(n)
        }
        _done += Int64(n)
        return true
    }

    // MARK: pause / skip (protocol section 7)

    public var paused: Bool { lock.lock(); defer { lock.unlock() }; return _paused }
    public var skipped: Set<Int> { lock.lock(); defer { lock.unlock() }; return _skipped }
    public func isSkipped(_ i: Int) -> Bool { lock.lock(); defer { lock.unlock() }; return _skipped.contains(i) }

    /// All bytes of file `i` have been counted (finished, or fully sent).
    public func isFileFinished(_ i: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return fileDone.indices.contains(i) && fileDone[i] >= sizes[i]
    }

    /// Marks `i` skipped and subtracts its not-yet-moved bytes from the total. False (nothing changes) when the index is
    /// out of range, already skipped, or the file is already complete.
    func markSkipped(_ i: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard fileDone.indices.contains(i), !_skipped.contains(i), fileDone[i] < sizes[i] else { return false }
        _skipped.insert(i)
        _total -= sizes[i] - fileDone[i]
        _lastProgress = Date()
        return true
    }

    /// The receiver's final message lists what it dropped; make sure we show those as skipped too.
    func adoptSkipped(_ list: [Int]) {
        lock.lock()
        for i in list where fileDone.indices.contains(i) { _skipped.insert(i) }
        lock.unlock()
    }

    func enableCtl(_ l: CtlLink) { lock.lock(); ctlOn = true; link = l; lock.unlock() }

    /// Sets the shared pause state; true when it changed.
    @discardableResult
    func setPausedLocal(_ p: Bool) -> Bool {
        lock.lock()
        let changed = _paused != p
        _paused = p
        if changed && !p { _lastProgress = Date() }   // watchdogs restart from the moment we resume
        lock.unlock()
        pauseCond.lock(); pauseCond.broadcast(); pauseCond.unlock()
        return changed
    }

    /// Blocks while paused (sender workers call this between frames). Returns when resumed or the transfer ends.
    func waitWhilePaused() {
        pauseCond.lock()
        while running && paused { _ = pauseCond.wait(until: Date().addingTimeInterval(0.5)) }
        pauseCond.unlock()
    }

    /// A control message arrived from the peer.
    func receive(_ m: CtlMessage) {
        guard running else { return }
        switch m {
        case .pause: setPausedLocal(true)
        case .resume: setPausedLocal(false)
        case .skip(let i): _ = applySkip(i)
        }
        onControl?(m.label, false)
    }

    private func applySkip(_ i: Int) -> Bool { skipHook?(i) ?? markSkipped(i) }

    /// User asked to pause or resume (UI / CLI hook): applies locally and tells the peer. No-op without `ctl`.
    public func setPaused(_ p: Bool) {
        guard ctlOn, running else { return }
        setPausedLocal(p)
        send(p ? .pause : .resume)
    }

    /// User asked to skip file `i`: applies locally and tells the peer. No-op without `ctl` or when `i` is finished.
    public func skipFile(_ i: Int) {
        guard ctlOn, running, applySkip(i) else { return }
        send(.skip(i))
    }

    private func send(_ m: CtlMessage) {
        link?.send(m)
        onControl?(m.label, true)
    }

    /// Writes the receiver's final `{"ok": true, "skipped": [...]}` (blocking, in order after queued control messages).
    func sendFinalOK() {
        var o: [String: Any] = ["ok": true]
        let sk = skipped.sorted()
        if !sk.isEmpty { o["skipped"] = sk }
        if let l = link { l.sendSync(o) }
    }

    /// Blocks until the transfer ends.
    func waitFinished() {
        let sem = DispatchSemaphore(value: 0)
        onFinish { _ in sem.signal() }
        sem.wait()
    }

    public func register(_ s: Sock) {
        lock.lock()
        sockets.append(s)
        let over = _state != .running
        lock.unlock()
        if over { s.shutdown() }
    }

    /// Ends the transfer once; later calls are ignored. Wakes every socket so worker threads exit.
    @discardableResult
    public func finish(_ st: TransferState, _ msg: String) -> Bool {
        lock.lock()
        guard _state == .running else { lock.unlock(); return false }
        _state = st; _message = msg
        let socks = sockets
        let handlers = finishHandlers
        finishHandlers = []
        lock.unlock()
        if st != .done { socks.forEach { $0.shutdown() } }
        handlers.forEach { $0(self) }
        pauseCond.lock(); pauseCond.broadcast(); pauseCond.unlock()
        return true
    }

    public func cancel() { finish(.cancelled, "Cancelled") }
}

/// Only one transfer runs at a time, in either direction (Android `Hub.tryReserve`).
public final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var busy = false
    public init() {}
    public func tryReserve() -> Bool { lock.lock(); defer { lock.unlock() }; if busy { return false }; busy = true; return true }
    public func release() { lock.lock(); busy = false; lock.unlock() }
    public var isBusy: Bool { lock.lock(); defer { lock.unlock() }; return busy }
}
