import FastDropCore
import Foundation

// fastdrop-cli: headless use of FastDropCore, for protocol tests against phones and tools/fdtest.py.
//   fastdrop-cli hello <ip> [--port N]
//   fastdrop-cli send <ip> [<file|folder>...] [--text "…"] [--crc] [--stop-after-mb N] [--port N] [--streams N] [--name NAME]
//   fastdrop-cli recv [--dir DIR] [--port N] [--accept ask|trusted|everyone | --auto] [--crc] [--name NAME] [--no-discovery]
//   fastdrop-cli trust list | trust remove <id>
//   fastdrop-cli selftest
//   (all: --state-dir DIR keeps device id, trust keys and resume records in DIR)
//   fastdrop-cli scan
//   fastdrop-cli link [--name NAME]

setvbuf(stdout, nil, _IOLBF, 0)
var args = Array(CommandLine.arguments.dropFirst())

func option(_ name: String) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    let v = args[i + 1]; args.removeSubrange(i...(i + 1)); return v
}
func flag(_ name: String) -> Bool {
    guard let i = args.firstIndex(of: name) else { return false }
    args.remove(at: i); return true
}
func die(_ msg: String) -> Never { FileHandle.standardError.write(Data((msg + "\n").utf8)); exit(1) }

guard !args.isEmpty else { die("usage: fastdrop-cli hello|send|recv|scan …") }
let cmd = args.removeFirst()
let port = option("--port").flatMap(UInt16.init) ?? Proto.port
let stateDir = option("--state-dir").map { URL(fileURLWithPath: $0, isDirectory: true) }
    ?? Stores.defaultDir.deletingLastPathComponent().appendingPathComponent("FastDrop-CLI", isDirectory: true)
if cmd != "selftest" { Stores.shared = Stores(dir: stateDir) }
// A stable device id, so trust keys survive between runs.
let idFile = stateDir.appendingPathComponent("device-id")
let deviceID: String = {
    if let s = try? String(contentsOf: idFile, encoding: .utf8), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let id = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)).lowercased()
    try? FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)
    try? id.write(to: idFile, atomically: true, encoding: .utf8)
    return id
}()
let me = Identity(id: deviceID, name: option("--name") ?? "Mac CLI", port: port)
let gate = Gate()
var keepAlive: [AnyObject] = []

func watch(_ t: Transfer) {
    var last: Int64 = 0
    var lastAt = Date()
    while t.running {
        Thread.sleep(forTimeInterval: 0.5)
        let d = t.done, now = Date()
        let pct = t.total > 0 ? Double(d) * 100 / Double(t.total) : 100
        print(String(format: "\r  %5.1f%%  %@  (%@ / %@)   ", pct, Fmt.speed(Double(d - last) / now.timeIntervalSince(lastAt)),
                     Fmt.bytes(d), Fmt.bytes(t.total)), terminator: "")
        last = d; lastAt = now
    }
    let secs = Date().timeIntervalSince(t.startedAt)
    let moved = t.sessionDone
    print("\n\(t.state.rawValue.uppercased()): \(t.message) — \(Fmt.bytes(t.total)) total, \(Fmt.bytes(moved)) moved this session (\(moved) bytes) in \(String(format: "%.2f", secs))s, avg \(Fmt.speed(Double(moved) / max(secs, 0.001)))")
}

switch cmd {
case "hello":
    guard let ip = args.first else { die("hello <ip>") }
    guard let p = Discovery.hello(ip: ip, port: port, myId: me.id) else { die("no FastDrop device at \(ip):\(port)") }
    print("\(p.name)  type=\(p.type)  id=\(p.id)  \(p.ip):\(p.port)")

case "send":
    let streams = option("--streams").flatMap(Int.init) ?? 4
    let text = option("--text")
    let useCRC = flag("--crc")
    let stopMB = option("--stop-after-mb").flatMap(Int64.init)
    guard args.count >= (text == nil ? 2 : 1) else { die("send <ip> <file>... | --text \"…\"") }
    let ip = args.removeFirst()
    let items = SendItem.expand(args.map { URL(fileURLWithPath: $0) })
    guard text != nil || !items.isEmpty else { die("nothing to send") }
    let peer = Discovery.hello(ip: ip, port: port, myId: me.id)
        ?? Peer(id: "manual", name: ip, type: "phone", ip: ip, port: port, manual: true)
    let job = SendJob(peer: peer, items: items, text: text, identity: me, gate: gate, streams: streams)
    job.useCRC = useCRC
    job.stores = Stores.shared
    job.stopAfterBytes = stopMB.map { $0 << 20 }
    job.onStatus = { print($0) }
    job.onCode = { print("Code \($0)") }
    var watcher: Thread?
    job.onStart = { t in
        let resumed = t.baseline > 0 ? ", resuming at \(Fmt.bytes(t.baseline))" : ""
        print("ACCEPTED — \(text != nil ? "text" : "\(items.count) file(s)"), \(Fmt.bytes(t.total)), \(streams) streams\(resumed)\(t.crcOn ? ", CRC32 on" : "")")
        watcher = Thread { watch(t) }; watcher?.start()
    }
    if let err = job.run() { die(err) }
    while watcher?.isFinished == false { Thread.sleep(forTimeInterval: 0.1) }
    exit(gate.isBusy ? 1 : 0)

case "recv":
    let auto = flag("--auto")
    let modeArg = option("--accept")
    let crcOK = flag("--crc")
    guard let mode = modeArg.map({ AcceptMode(rawValue: $0) }) ?? (auto ? .everyone : .ask) else { die("--accept ask|trusted|everyone") }
    let noDisc = flag("--no-discovery")
    let dir = URL(fileURLWithPath: option("--dir") ?? "./fd_received")
    let rx = Receiver(identity: me, gate: gate, port: port, stores: Stores.shared)
    rx.saveDir = { dir }
    rx.acceptMode = { mode }
    rx.agreeCRC = { crcOK }
    rx.onText = { text, from in print("\n  TEXT from \(from): \(text)") }
    rx.onListenError = { if let e = $0 { print("listen error: \(e)") } }
    rx.present = { offer in
        let what = offer.text.map { "text (\($0.count) chars)" } ?? "\(offer.files.count) file(s), \(Fmt.bytes(offer.total))"
        let tag = offer.trusted ? " [trusted]" : ""
        print("\nOffer from \(offer.from)\(tag), code \(offer.code): \(what). Accept? [y/N/a=always] ", terminator: "")
        DispatchQueue.global().async {
            let a = readLine()?.lowercased() ?? ""
            offer.respond(a.hasPrefix("y") || a.hasPrefix("a"), trust: a.hasPrefix("a"))
        }
    }
    rx.onSaved = { url, _, size in print("\n  saved \(url.path) (\(Fmt.bytes(size)))") }
    rx.onStart = { t in
        let resumed = t.baseline > 0 ? ", resuming at \(Fmt.bytes(t.baseline))" : ""
        print("\n  receiving \(Fmt.bytes(t.total))\(resumed)\(t.crcOn ? ", CRC32 on" : "")")
        Thread { watch(t) }.start()
    }
    rx.start()
    if !noDisc {
        let d = Discovery(identity: me)
        keepAlive.append(d)   // Discovery threads hold it weakly
        var seen = Set<String>()
        d.onPeer = { p in if seen.insert(p.id).inserted { print("[discovery] saw \(p.name) (\(p.type)) at \(p.ip)") } }
        d.start()
        signal(SIGINT) { _ in exit(0) }
    }
    print("Receiving as '\(me.name)' (id \(me.id)) on TCP \(port), accept=\(mode.rawValue), crc=\(crcOK); saving to \(dir.path)")
    dispatchMain()

case "trust":
    let sub = args.first ?? "list"
    let ts = Stores.shared.trust
    switch sub {
    case "list":
        let l = ts.list()
        if l.isEmpty { print("no trusted devices") }
        for d in l { print("\(d.id)  \(d.name)  added \(d.added.formatted(.iso8601))") }
    case "remove":
        guard args.count >= 2 else { die("trust remove <id>") }
        ts.remove(args[1]); print("removed \(args[1])")
    default: die("trust list | trust remove <id>")
    }

case "selftest":
    for (label, got, _) in SelfTest.cases() { print("\(label): \(got.replacingOccurrences(of: "\n", with: "\\n"))") }
    let bad = SelfTest.failures()
    if bad.isEmpty { print("selftest OK") } else { bad.forEach { print("FAIL \($0)") }; exit(1) }

case "scan":
    let sem = DispatchSemaphore(value: 0)
    Task {
        let n = await Discovery.scan(myId: me.id) { p in print("  \(p.name) (\(p.type)) at \(p.ip)") }
        print("found \(n)")
        sem.signal()
    }
    sem.wait()

case "link":
    // Prints this Mac's QR link and parses it back: fastdrop-cli link [--name NAME]
    let url = JoinLink(id: me.id, name: me.name, type: "laptop", port: port, lanIPs: Net.lanIPs()).url
    print(url)
    guard let back = JoinLink.parse(url) else { die("parse failed") }
    print("parsed: name=\(back.name) lan=\(back.lanIPs) port=\(back.port)")

default:
    die("unknown command \(cmd)")
}
