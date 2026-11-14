import Foundation
import Testing
@testable import FastDropCore

// Protocol section 7: control messages and skip / pause bookkeeping.
@Suite struct CtlTests {
    @Test func advertisesCap() { #expect(Proto.caps.contains("ctl")) }

    @Test func encoding() throws {
        func text(_ m: CtlMessage) throws -> String {
            String(decoding: try JSONSerialization.data(withJSONObject: m.json, options: [.sortedKeys]), as: UTF8.self)
        }
        #expect(try text(.pause) == #"{"t":"pause"}"#)
        #expect(try text(.resume) == #"{"t":"resume"}"#)
        #expect(try text(.skip(3)) == #"{"file":3,"t":"skip"}"#)
    }

    @Test func decoding() {
        #expect(CtlMessage.parse(["t": "pause"]) == .pause)
        #expect(CtlMessage.parse(["t": "resume"]) == .resume)
        #expect(CtlMessage.parse(["t": "skip", "file": 2]) == .skip(2))
        #expect(CtlMessage.parse(["t": "skip"]) == nil)            // no file index
        #expect(CtlMessage.parse(["t": "future-thing"]) == nil)    // unknown "t" is ignored
        #expect(CtlMessage.parse(["ok": true, "skipped": [1]]) == nil)   // the final message has no "t"
    }

    @Test func testPlanSyntax() {
        let p = CtlTestPlan.parse("4:resume,1:pause,2.5:skip=0")
        #expect(p?.map(\.at) == [1, 2.5, 4])
        #expect(p?.map(\.action) == [.pause, .skip(0), .resume])
        #expect(CtlTestPlan.parse("") == nil)
        #expect(CtlTestPlan.parse("1:stop") == nil)
        #expect(CtlTestPlan.parse("x:pause") == nil)
        #expect(CtlTestPlan.parse("1:skip=-1") == nil)
    }

    private func transfer(_ sizes: [Int64]) -> Transfer {
        Transfer(outgoing: true, peerName: "p", peerType: "phone", total: sizes.reduce(0, +),
                 names: sizes.indices.map { "f\($0)" }, sizes: sizes)
    }

    @Test func skipSubtractsRemainingBytes() {
        let t = transfer([100, 50, 0])
        t.add(30, file: 0)
        #expect(t.markSkipped(0))
        #expect(t.total == 80)                  // 100 - (100 - 30) removed
        #expect(t.skipped == [0])
        #expect(t.add(10, file: 0) == false)    // skipped files no longer count
        #expect(t.done == 30)
        #expect(!t.markSkipped(0))              // already skipped
        #expect(!t.markSkipped(7))              // out of range
        #expect(!t.markSkipped(2))              // empty file is already complete
        t.add(50, file: 1)
        #expect(t.isFileFinished(1))
        #expect(!t.markSkipped(1))              // finished files ignore skip
        #expect(t.total == 80 && t.done == 80)
    }

    @Test func pauseKeepsWatchdogQuiet() {
        let t = transfer([10])
        t.add(1, file: 0)
        Thread.sleep(forTimeInterval: 0.05)
        #expect(Date().timeIntervalSince(t.lastProgress) > 0.04)
        t.setPausedLocal(true)
        #expect(Date().timeIntervalSince(t.lastProgress) < 0.01)    // paused: "no progress" time doesn't accrue
        Thread.sleep(forTimeInterval: 0.05)
        t.setPausedLocal(false)
        #expect(Date().timeIntervalSince(t.lastProgress) < 0.01)    // restarts from the resume
        #expect(!t.paused)
    }

    @Test func remoteMessagesApply() {
        let t = transfer([100, 100])
        t.receive(.pause)
        #expect(t.paused)
        t.receive(.skip(1))
        #expect(t.isSkipped(1) && t.total == 100)
        t.receive(.resume)
        #expect(!t.paused)
    }

    @Test func receiverSessionCountsSkippedAsDone() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("fd-ctl-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let files = [OfferFile(name: "a.bin", size: 10, appLabel: nil), OfferFile(name: "b.bin", size: 5, appLabel: nil)]
        let t = Transfer(outgoing: false, peerName: "p", peerType: "phone", total: 15, names: ["a.bin", "b.bin"], sizes: [10, 5])
        let s = try RxSession(id: "sid", files: files, dir: dir, transfer: t, dev: nil, crc: false,
                              stores: Stores(dir: dir.appendingPathComponent("state"))) { _, _ in
            Issue.record("a skipped or unfinished file must not surface as received")
        }
        #expect(s.skip(0))
        #expect(!s.skip(0))
        #expect(t.total == 5)
        #expect(!s.complete)                    // b.bin still outstanding
        _ = try s.sink(1)                       // b.bin partial exists
        s.frameStart()                          // a frame of the skipped file is still being read
        t.add(5, file: 1)
        #expect(s.skip(1) == false)             // fully received: skip is ignored
        s.frameEnd()
        s.close()
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent("a.bin").path))
    }
}
