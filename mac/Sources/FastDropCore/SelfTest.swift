import Foundation

/// The protocol-2 test vectors from docs/PROTOCOL.md, with expected values computed independently
/// (python3 hashlib / hmac / zlib). Used by `fastdrop-cli selftest` and the unit tests.
public enum SelfTest {
    public static let id = "123e4567-e89b-12d3-a456-426614174000"
    public static let dev = "dev-abc"
    public static let ts: Int64 = 1731400000000
    public static let mtime: Int64 = 1731300000000
    public static let files: [(name: String, size: Int64)] = [("a.txt", 5), ("dir/b.bin", 9_000_000)]
    public static let key = Data(0..<32)

    /// (label, actual, expected)
    public static func cases() -> [(String, String, String)] {
        let dText = Spec.filesDigest(files: files, text: "hello")
        let dPlain = Spec.filesDigest(files: files, text: nil)
        return [
            ("code", Spec.code(id: id), "8249"),
            ("filesDigest (no text)", dPlain, "1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184"),
            ("filesDigest (text hello)", dText, "0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837"),
            ("canonical", Spec.canonical(id: id, dev: dev, ts: ts, filesDigest: dText),
             "fastdrop-auth-v1\n\(id)\n\(dev)\n\(ts)\n0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837"),
            ("auth (text hello)", Spec.auth(key: key, id: id, dev: dev, ts: ts, filesDigest: dText),
             "c1bad27d493dbc2b2b181256033d1b2a9ec2aa272d8cea467a4403f6b4586047"),
            ("auth (no text)", Spec.auth(key: key, id: id, dev: dev, ts: ts, filesDigest: dPlain),
             "1a8208d2d36cb0a7bba5a7d93980341d48b292e3cc4d81f639fe848ec3fc2817"),
            ("resumeKey (a.txt, with mtime)", Spec.resumeKey(dev: dev, name: "a.txt", size: 5, mtime: mtime),
             "48d8e971ef69f06580dff1e2fb96e783e485fcd99df3b336180e3b587bea35d7"),
            ("resumeKey (a.txt, no mtime)", Spec.resumeKey(dev: dev, name: "a.txt", size: 5, mtime: nil),
             "a732c2de87498963c7aa10ead1d2d91824263f1e5b0da3255c09abd8424cc4a2"),
            ("crc32(\"fastdrop\")", String(Spec.crc32(Data("fastdrop".utf8))), "2396181082"),
            ("merge [[0,3],[2,5],[7,8]]", "\(Spec.mergeRanges([[0, 3], [2, 5], [7, 8]]))", "[[0, 5], [7, 8]]"),
        ]
    }

    /// Returns a description of every mismatch (empty when all pass).
    public static func failures() -> [String] {
        var out = cases().filter { $0.1 != $0.2 }.map { "\($0.0): got \($0.1), expected \($0.2)" }
        let k = Spec.hex(key)
        if k != "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" { out.append("key bytes: \(k)") }
        let dText = Spec.filesDigest(files: files, text: "hello")
        let a = Spec.auth(key: key, id: id, dev: dev, ts: ts, filesDigest: dText)
        if !Spec.verifyAuth(key: key, auth: a, id: id, dev: dev, ts: ts, filesDigest: dText) { out.append("verifyAuth rejected a good MAC") }
        if Spec.verifyAuth(key: key, auth: a, id: id, dev: dev, ts: ts + 1, filesDigest: dText) { out.append("verifyAuth accepted a bad ts") }
        if Spec.verifyAuth(key: key, auth: String(a.dropLast()) + "0", id: id, dev: dev, ts: ts, filesDigest: dText) && !a.hasSuffix("0") {
            out.append("verifyAuth accepted a bad MAC")
        }
        if Spec.ranges(of: [0, 1, 2, 4]) != [[0, 3], [4, 5]] { out.append("ranges(of:)") }
        if Spec.indices(of: [[0, 2], [5, 9]], limit: 7) != [0, 1, 5, 6] { out.append("indices(of:)") }
        if Spec.mergeRanges([[3, 5], [0, 3]]) != [[0, 5]] { out.append("merge touching") }
        if Spec.chunkCount(size: 9_000_000) != 2 || Spec.chunkLength(index: 1, size: 9_000_000) != 611_392 { out.append("chunk math") }
        if Spec.unhex("zz") != nil || Spec.unhex("abc") != nil { out.append("unhex validation") }
        // streaming CRC equals one-shot CRC
        let d = Data("fastdrop".utf8)
        let part = d.withUnsafeBytes { p -> UInt32 in
            let b = p.bindMemory(to: UInt8.self).baseAddress!
            return Spec.crc32(Spec.crc32(0, b, 3), b + 3, 5)
        }
        if part != 2396181082 { out.append("incremental crc32") }
        if AcceptMode.migrated(legacyAutoAccept: true) != .everyone || AcceptMode.migrated(legacyAutoAccept: false) != .trusted {
            out.append("autoAccept migration")
        }
        return out
    }
}
