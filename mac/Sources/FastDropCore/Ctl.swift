import Foundation

/// Pause / resume / skip messages on the CTRL connection (docs/PROTOCOL.md section 7, capability "ctl").
public enum CtlMessage: Equatable, Sendable {
    case pause, resume, skip(Int)

    public var json: [String: Any] {
        switch self {
        case .pause: return ["t": "pause"]
        case .resume: return ["t": "resume"]
        case .skip(let i): return ["t": "skip", "file": i]
        }
    }

    /// nil for anything that isn't a known control message (unknown "t" values are ignored; the final {"ok"} has none).
    public static func parse(_ o: [String: Any]) -> CtlMessage? {
        switch o["t"] as? String {
        case "pause": return .pause
        case "resume": return .resume
        case "skip":
            guard let i = (o["file"] as? NSNumber)?.intValue else { return nil }
            return .skip(i)
        default: return nil
        }
    }

    /// "pause", "resume", "skip=3" (also the `--ctl-test` action syntax).
    public var label: String {
        switch self {
        case .pause: return "pause"
        case .resume: return "resume"
        case .skip(let i): return "skip=\(i)"
        }
    }

    public static func parse(label s: String) -> CtlMessage? {
        switch s {
        case "pause": return .pause
        case "resume": return .resume
        default:
            guard s.hasPrefix("skip="), let i = Int(s.dropFirst(5)), i >= 0 else { return nil }
            return .skip(i)
        }
    }
}

/// `--ctl-test "SECONDS:ACTION[,SECONDS:ACTION...]"` for the CLI: ACTION = pause | resume | skip=N.
public enum CtlTestPlan {
    public static func parse(_ s: String) -> [(at: Double, action: CtlMessage)]? {
        var out: [(at: Double, action: CtlMessage)] = []
        for part in s.split(separator: ",", omittingEmptySubsequences: false) {
            let kv = part.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2, let secs = Double(kv[0].trimmingCharacters(in: .whitespaces)), secs >= 0,
                  let m = CtlMessage.parse(label: kv[1].trimmingCharacters(in: .whitespaces)) else { return nil }
            out.append((secs, m))
        }
        return out.isEmpty ? nil : out.sorted { $0.at < $1.at }   // stable enough: sort is by time only
    }
}

/// The CTRL connection once `ctl` was agreed: a reader loop plus a serial writer queue (one writer at a time,
/// and callers on the UI thread never block on the network).
final class CtlLink: @unchecked Sendable {
    private let sock: Sock
    private let queue = DispatchQueue(label: "fd-ctl-write")
    private let lock = NSLock()
    private var broken = false

    init(sock: Sock) { self.sock = sock }

    /// Queues a control message.
    func send(_ m: CtlMessage) {
        queue.async { [self] in write(m.json) }
    }

    /// Writes `o` after everything already queued, and returns when it is out.
    func sendSync(_ o: [String: Any]) {
        queue.sync { write(o) }
    }

    private func write(_ o: [String: Any]) {
        lock.lock(); let b = broken; lock.unlock()
        if b { return }
        do { try sock.writeJSON(o) } catch {
            lock.lock(); broken = true; lock.unlock()   // a half-written message would corrupt framing: stop writing
        }
    }

    /// Reads messages until the connection ends (throws) or `other` returns true. Control messages go to the
    /// transfer; any other message (the receiver's final `{"ok"}`) goes to `other`. Never times out.
    func read(transfer t: Transfer, other: (([String: Any]) -> Bool)?) throws {
        while true {
            let o = try sock.readJSON(max: 1 << 20)
            if let m = CtlMessage.parse(o) { t.receive(m); continue }
            if o["t"] is String { continue }          // unknown "t": ignored
            if let other, other(o) { return }
        }
    }
}
