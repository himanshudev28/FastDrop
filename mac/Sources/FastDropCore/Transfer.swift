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
    public let total: Int64
    public let fileCount: Int
    public let names: [String]
    public let startedAt = Date()

    private let lock = NSLock()
    private var _done: Int64 = 0
    private var _state: TransferState = .running
    private var _message = ""
    private var _lastProgress = Date()
    private var sockets: [Sock] = []
    /// Sender: all bytes are written; only the receiver's final "ok" is outstanding.
    public var sentAll = false

    private var _baseline: Int64 = 0
    /// Receiver: the confirmation code the sender shows. Sender: set when the offer went out.
    public var code: String?
    /// Sender: a text/link offer (no data streams).
    public var isText = false
    /// Per-chunk CRC32 was agreed for this transfer.
    public var crcOn = false

    private var finishHandlers: [(Transfer) -> Void] = []

    /// Runs once, on the thread that ends the transfer. Runs immediately if it already ended.
    public func onFinish(_ h: @escaping (Transfer) -> Void) {
        lock.lock()
        if _state == .running { finishHandlers.append(h); lock.unlock(); return }
        lock.unlock()
        h(self)
    }

    public init(outgoing: Bool, peerName: String, peerType: String, total: Int64, names: [String]) {
        self.outgoing = outgoing; self.peerName = peerName; self.peerType = peerType
        self.total = total; self.fileCount = names.count; self.names = Array(names.prefix(50))
    }

    public var done: Int64 { lock.lock(); defer { lock.unlock() }; return _done }
    public var state: TransferState { lock.lock(); defer { lock.unlock() }; return _state }
    public var message: String { lock.lock(); defer { lock.unlock() }; return _message }
    public var running: Bool { state == .running }
    public var lastProgress: Date { lock.lock(); defer { lock.unlock() }; return _lastProgress }

    /// Bytes that were already at the receiver when this session started (resume).
    public var baseline: Int64 { lock.lock(); defer { lock.unlock() }; return _baseline }
    /// Bytes moved in this session only.
    public var sessionDone: Int64 { lock.lock(); defer { lock.unlock() }; return _done - _baseline }

    /// Counts bytes that are already in place, before any data moves.
    public func preset(_ n: Int64) {
        lock.lock(); _done += n; _baseline += n; lock.unlock()
    }

    public func add(_ n: Int) {
        lock.lock(); _done += Int64(n); _lastProgress = Date(); lock.unlock()
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
