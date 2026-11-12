import Foundation
import Testing
@testable import FastDropCore

// The protocol-2 test vectors (docs/PROTOCOL.md). Expected values were computed with python3 hashlib/hmac/zlib.
@Suite struct SpecTests {
    @Test func confirmationCode() { #expect(Spec.code(id: SelfTest.id) == "8249") }

    @Test func filesDigest() {
        #expect(Spec.filesDigest(files: SelfTest.files, text: nil) == "1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184")
        #expect(Spec.filesDigest(files: SelfTest.files, text: "hello") == "0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837")
    }

    @Test func authAndCanonical() {
        let d = Spec.filesDigest(files: SelfTest.files, text: "hello")
        #expect(Spec.canonical(id: SelfTest.id, dev: SelfTest.dev, ts: SelfTest.ts, filesDigest: d)
                == "fastdrop-auth-v1\n\(SelfTest.id)\n\(SelfTest.dev)\n\(SelfTest.ts)\n\(d)")
        let a = Spec.auth(key: SelfTest.key, id: SelfTest.id, dev: SelfTest.dev, ts: SelfTest.ts, filesDigest: d)
        #expect(a == "c1bad27d493dbc2b2b181256033d1b2a9ec2aa272d8cea467a4403f6b4586047")
        #expect(Spec.verifyAuth(key: SelfTest.key, auth: a, id: SelfTest.id, dev: SelfTest.dev, ts: SelfTest.ts, filesDigest: d))
        #expect(!Spec.verifyAuth(key: SelfTest.key, auth: a, id: SelfTest.id, dev: "other", ts: SelfTest.ts, filesDigest: d))
    }

    @Test func resumeKey() {
        #expect(Spec.resumeKey(dev: SelfTest.dev, name: "a.txt", size: 5, mtime: SelfTest.mtime)
                == "48d8e971ef69f06580dff1e2fb96e783e485fcd99df3b336180e3b587bea35d7")
        #expect(Spec.resumeKey(dev: SelfTest.dev, name: "a.txt", size: 5, mtime: nil)
                == "a732c2de87498963c7aa10ead1d2d91824263f1e5b0da3255c09abd8424cc4a2")
    }

    @Test func crcAndRanges() {
        #expect(Spec.crc32(Data("fastdrop".utf8)) == 2396181082)
        #expect(Spec.mergeRanges([[0, 3], [2, 5], [7, 8]]) == [[0, 5], [7, 8]])
    }

    @Test func everythingInSelfTest() { #expect(SelfTest.failures().isEmpty) }
}
