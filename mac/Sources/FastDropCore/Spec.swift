import CryptoKit
import Foundation
import zlib

/// Pure protocol-2 math (docs/PROTOCOL.md): confirmation code, files digest, HMAC auth, resume keys,
/// chunk ranges and CRC32. No I/O, so every function is covered by `fastdrop-cli selftest` and the unit tests.
public enum Spec {
    public static let chunk = 8_388_608
    public static let maxText = 100_000
    public static let authWindowMs: Int64 = 24 * 3600 * 1000

    // MARK: hex

    public static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        var out = ""
        for b in bytes { out += String(format: "%02x", b) }
        return out
    }

    public static func unhex(_ s: String) -> Data? {
        guard s.utf8.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.utf8.count / 2)
        var it = s.utf8.makeIterator()
        func nib(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 97...102: return c - 87
            case 65...70: return c - 55
            default: return nil
            }
        }
        while let a = it.next(), let b = it.next() {
            guard let x = nib(a), let y = nib(b) else { return nil }
            out.append(x << 4 | y)
        }
        return out
    }

    public static func sha256Hex(_ s: String) -> String { hex(SHA256.hash(data: Data(s.utf8))) }

    // MARK: confirmation code

    /// First 4 bytes of SHA-256(id) as an unsigned big-endian int, mod 10000, zero-padded.
    public static func code(id: String) -> String {
        let d = Array(SHA256.hash(data: Data(id.utf8)))
        let v = UInt32(d[0]) << 24 | UInt32(d[1]) << 16 | UInt32(d[2]) << 8 | UInt32(d[3])
        return String(format: "%04d", v % 10000)
    }

    // MARK: trust

    public static func filesDigest(files: [(name: String, size: Int64)], text: String?) -> String {
        var s = ""
        for f in files { s += "\(f.name)\n\(f.size)\n" }
        if let text { s += "T\n" + text }
        return sha256Hex(s)
    }

    public static func canonical(id: String, dev: String, ts: Int64, filesDigest: String) -> String {
        "fastdrop-auth-v1\n\(id)\n\(dev)\n\(ts)\n\(filesDigest)"
    }

    public static func auth(key: Data, id: String, dev: String, ts: Int64, filesDigest: String) -> String {
        let c = canonical(id: id, dev: dev, ts: ts, filesDigest: filesDigest)
        return hex(HMAC<SHA256>.authenticationCode(for: Data(c.utf8), using: SymmetricKey(data: key)))
    }

    /// Constant-time check of a received `auth` value.
    public static func verifyAuth(key: Data, auth: String, id: String, dev: String, ts: Int64, filesDigest: String) -> Bool {
        guard auth.utf8.count == 64, let mac = unhex(auth) else { return false }
        let c = canonical(id: id, dev: dev, ts: ts, filesDigest: filesDigest)
        return HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: Data(c.utf8), using: SymmetricKey(data: key))
    }

    public static func randomKey() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }

    // MARK: resume

    public static func resumeKey(dev: String, name: String, size: Int64, mtime: Int64?) -> String {
        sha256Hex("\(dev)\n\(name)\n\(size)\n\(mtime.map(String.init) ?? "")")
    }

    public static func chunkCount(size: Int64) -> Int { Int((size + Int64(chunk) - 1) / Int64(chunk)) }
    public static func chunkLength(index: Int, size: Int64) -> Int64 { min(Int64(chunk), size - Int64(index) * Int64(chunk)) }

    /// Sorts and merges half-open `[start, end)` intervals, joining overlapping and touching ones.
    public static func mergeRanges(_ ranges: [[Int]]) -> [[Int]] {
        var out: [[Int]] = []
        for r in ranges.filter({ $0.count == 2 && $0[0] < $0[1] }).sorted(by: { $0[0] < $1[0] }) {
            if var last = out.last, r[0] <= last[1] {
                last[1] = max(last[1], r[1]); out[out.count - 1] = last
            } else { out.append([r[0], r[1]]) }
        }
        return out
    }

    public static func ranges(of set: Set<Int>) -> [[Int]] { mergeRanges(set.map { [$0, $0 + 1] }) }

    /// Chunk indices covered by `ranges`, dropping anything outside `0..<limit`.
    public static func indices(of ranges: [[Int]], limit: Int) -> Set<Int> {
        var s = Set<Int>()
        for r in mergeRanges(ranges) {
            let lo = max(0, r[0]), hi = min(limit, r[1])
            if lo < hi { for i in lo..<hi { s.insert(i) } }
        }
        return s
    }

    // MARK: crc32

    public static func crc32(_ data: Data, seed: UInt32 = 0) -> UInt32 {
        data.withUnsafeBytes { b in
            guard let p = b.baseAddress else { return seed }
            return crc32(seed, p.assumingMemoryBound(to: UInt8.self), b.count)
        }
    }

    /// Running zlib CRC32 over `count` bytes at `p`.
    public static func crc32(_ seed: UInt32, _ p: UnsafePointer<UInt8>, _ count: Int) -> UInt32 {
        var crc = UInt(seed)
        var off = 0
        while off < count {
            let n = min(count - off, 1 << 30)
            crc = zlib.crc32(crc, p + off, UInt32(n))
            off += n
        }
        return UInt32(truncatingIfNeeded: crc)
    }
}
