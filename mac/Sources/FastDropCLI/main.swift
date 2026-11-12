import FastDropCore
import Foundation

// fastdrop-cli: headless use of FastDropCore, for protocol tests against phones and tools/fdtest.py.
//   fastdrop-cli hello <ip> [--port N]
//   fastdrop-cli send <ip> <file|folder>... [--port N] [--streams N] [--name NAME]
//   fastdrop-cli recv [--dir DIR] [--port N] [--auto] [--name NAME] [--no-discovery]
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
let me = Identity(id: UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16).lowercased(),
                  name: option("--name") ?? "Mac CLI", port: port)
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
    print("\n\(t.state.rawValue.uppercased()): \(t.message) — \(Fmt.bytes(t.total)) in \(String(format: "%.2f", secs))s, avg \(Fmt.speed(Double(t.total) / max(secs, 0.001)))")
}

switch cmd {
case "hello":
    guard let ip = args.first else { die("hello <ip>") }
    guard let p = Discovery.hello(ip: ip, port: port, myId: me.id) else { die("no FastDrop device at \(ip):\(port)") }
    print("\(p.name)  type=\(p.type)  id=\(p.id)  \(p.ip):\(p.port)")

case "send":
    let streams = option("--streams").flatMap(Int.init) ?? 4
    guard args.count >= 2 else { die("send <ip> <file>...") }
    let ip = args.removeFirst()
    let items = SendItem.expand(args.map { URL(fileURLWithPath: $0) })
    guard !items.isEmpty else { die("nothing to send") }
    let peer = Discovery.hello(ip: ip, port: port, myId: me.id)
        ?? Peer(id: "manual", name: ip, type: "phone", ip: ip, port: port, manual: true)
    let job = SendJob(peer: peer, items: items, identity: me, gate: gate, streams: streams)
    job.onStatus = { print($0) }
    var watcher: Thread?
    job.onStart = { t in
        print("ACCEPTED — \(items.count) file(s), \(Fmt.bytes(t.total)), \(streams) streams")
        watcher = Thread { watch(t) }; watcher?.start()
    }
    if let err = job.run() { die(err) }
    while watcher?.isFinished == false { Thread.sleep(forTimeInterval: 0.1) }
    exit(gate.isBusy ? 1 : 0)

case "recv":
    let auto = flag("--auto")
    let noDisc = flag("--no-discovery")
    let dir = URL(fileURLWithPath: option("--dir") ?? "./fd_received")
    let rx = Receiver(identity: me, gate: gate, port: port)
    rx.saveDir = { dir }
    rx.autoAccept = { auto }
    rx.onListenError = { if let e = $0 { print("listen error: \(e)") } }
    rx.present = { offer in
        print("\nOffer from \(offer.from): \(offer.files.count) file(s), \(Fmt.bytes(offer.total)). Accept? [y/N] ", terminator: "")
        DispatchQueue.global().async { offer.respond(readLine()?.lowercased().hasPrefix("y") == true) }
    }
    rx.onSaved = { url, _, size in print("\n  saved \(url.path) (\(Fmt.bytes(size)))") }
    rx.onStart = { t in Thread { watch(t) }.start() }
    rx.start()
    if !noDisc {
        let d = Discovery(identity: me)
        keepAlive.append(d)   // Discovery threads hold it weakly
        var seen = Set<String>()
        d.onPeer = { p in if seen.insert(p.id).inserted { print("[discovery] saw \(p.name) (\(p.type)) at \(p.ip)") } }
        d.start()
        signal(SIGINT) { _ in exit(0) }
    }
    print("Receiving as '\(me.name)' on TCP \(port); saving to \(dir.path)")
    dispatchMain()

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
