import Foundation

public struct TrustedDevice: Codable, Hashable, Sendable, Identifiable {
    public var id: String          // the sender's `dev`
    public var name: String
    public var key: String         // 64 hex chars
    public var added: Date
}

public struct ResumeRecord: Codable, Sendable {
    public var key: String
    public var path: String        // the hidden partial file
    public var size: Int64
    public var chunks: [[Int]]     // done chunk ranges, half-open, merged
    public var updated: Date
}

/// Small JSON files in one folder (`Application Support/FastDrop` by default). The CLI points this at
/// `--state-dir` so tests don't touch the app's trust list.
public final class Stores: @unchecked Sendable {
    public static var shared = Stores(dir: Stores.defaultDir)

    public static var defaultDir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FastDrop", isDirectory: true)
    }

    public let dir: URL
    public let trust: TrustStore
    public let resume: ResumeStore

    public init(dir: URL) {
        self.dir = dir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        trust = TrustStore(dir: dir)
        resume = ResumeStore(dir: dir)
    }

    static func load<T: Decodable>(_ type: T.Type, _ url: URL) -> T? {
        guard let d = try? Data(contentsOf: url) else { return nil }
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .secondsSince1970
        return try? dec.decode(T.self, from: d)
    }

    static func save<T: Encodable>(_ v: T, _ url: URL) {
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .secondsSince1970
        if let d = try? enc.encode(v) { try? d.write(to: url, options: .atomic) }
    }
}

/// Receiver side: devices allowed to send without a prompt. Sender side: the key each receiver gave us.
public final class TrustStore: @unchecked Sendable {
    private let lock = NSLock()
    private let trustedURL: URL, keysURL: URL
    private var trusted: [String: TrustedDevice]
    private var keys: [String: String]

    init(dir: URL) {
        trustedURL = dir.appendingPathComponent("trusted.json")
        keysURL = dir.appendingPathComponent("sender-keys.json")
        trusted = Stores.load([String: TrustedDevice].self, trustedURL) ?? [:]
        keys = Stores.load([String: String].self, keysURL) ?? [:]
    }

    public func list() -> [TrustedDevice] {
        lock.lock(); defer { lock.unlock() }
        return trusted.values.sorted { $0.added < $1.added }
    }

    public func device(_ id: String) -> TrustedDevice? { lock.lock(); defer { lock.unlock() }; return trusted[id] }

    /// Pairing: makes a fresh key for `id` (replacing any old one) and returns it as hex.
    public func add(id: String, name: String) -> String {
        let key = Spec.hex(Spec.randomKey())
        lock.lock()
        trusted[id] = TrustedDevice(id: id, name: name, key: key, added: Date())
        Stores.save(trusted, trustedURL)
        lock.unlock()
        return key
    }

    public func remove(_ id: String) {
        lock.lock()
        trusted[id] = nil
        Stores.save(trusted, trustedURL)
        lock.unlock()
    }

    public func senderKey(for receiver: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return keys[receiver].flatMap(Spec.unhex)
    }

    public func setSenderKey(_ hex: String, for receiver: String) {
        guard hex.utf8.count == 64, Spec.unhex(hex) != nil else { return }
        lock.lock()
        keys[receiver] = hex
        Stores.save(keys, keysURL)
        lock.unlock()
    }
}

public final class ResumeStore: @unchecked Sendable {
    public static let maxAge: TimeInterval = 7 * 24 * 3600
    private let lock = NSLock()
    private let url: URL
    private var records: [String: ResumeRecord]

    init(dir: URL) {
        url = dir.appendingPathComponent("resume.json")
        records = Stores.load([String: ResumeRecord].self, url) ?? [:]
    }

    public func record(_ key: String) -> ResumeRecord? { lock.lock(); defer { lock.unlock() }; return records[key] }
    public var count: Int { lock.lock(); defer { lock.unlock() }; return records.count }

    public func save(_ r: ResumeRecord) {
        lock.lock(); records[r.key] = r; Stores.save(records, url); lock.unlock()
    }

    public func remove(_ key: String) {
        lock.lock()
        if records.removeValue(forKey: key) != nil { Stores.save(records, url) }
        lock.unlock()
    }

    /// Deletes records (and their partial files) older than 7 days.
    public func prune(now: Date = Date()) {
        lock.lock()
        let old = records.values.filter { now.timeIntervalSince($0.updated) > Self.maxAge }
        for r in old { records[r.key] = nil }
        if !old.isEmpty { Stores.save(records, url) }
        lock.unlock()
        for r in old { try? FileManager.default.removeItem(atPath: r.path) }
    }
}
