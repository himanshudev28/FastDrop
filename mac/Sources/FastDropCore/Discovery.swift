import Darwin
import Foundation

/// Finds other FastDrop devices the same three ways as Android (`Discovery.kt`), so it works on
/// routers that block one of them:
///  1. UDP multicast to 224.0.0.168 on every LAN interface,
///  2. UDP broadcast (each subnet's broadcast address + 255.255.255.255),
///  3. a TCP HELLO to one address (Add by IP) or every host in our /24s (Scan network).
public final class Discovery: @unchecked Sendable {
    public var onPeer: ((Peer) -> Void)?
    public var onBye: ((String) -> Void)?

    private let me: Identity
    private let port: UInt16
    private var fd: Int32 = -1
    private let sendLock = NSLock()
    private var running = false

    public init(identity: Identity, port: UInt16 = Proto.discoveryPort) {
        self.me = identity
        self.port = port
    }

    public func start() {
        guard !running else { return }
        running = true
        let rx = Thread { [weak self] in self?.rxLoop() }
        rx.name = "fd-disc-rx"; rx.qualityOfService = .utility; rx.start()
        let tx = Thread { [weak self] in self?.txLoop() }
        tx.name = "fd-disc-tx"; tx.qualityOfService = .utility; tx.start()
    }

    /// Tells everyone we're leaving (call on quit).
    public func bye() { announce("bye") }

    /// Announce right away, e.g. after the Mac joins a new network.
    public func nudge() {
        DispatchQueue.global(qos: .utility).async { [weak self] in self?.joinAll(); self?.announce("announce") }
    }

    // MARK: receive

    private func openSocket() -> Int32? {
        let s = socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else { return nil }
        var one: Int32 = 1
        let sz = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sz)
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &one, sz)
        setsockopt(s, SOL_SOCKET, SO_BROADCAST, &one, sz)
        var ttl: UInt8 = 1
        setsockopt(s, Int32(IPPROTO_IP), IP_MULTICAST_TTL, &ttl, socklen_t(1))
        var tv = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if r != 0 { Darwin.close(s); return nil }
        return s
    }

    private func rxLoop() {
        while running {
            guard let s = openSocket() else { Thread.sleep(forTimeInterval: 2); continue }
            sendLock.lock(); fd = s; sendLock.unlock()
            joinAll()
            var buf = [UInt8](repeating: 0, count: 8192)
            while running {
                var from = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(s, &buf, buf.count, 0, $0, &len) }
                }
                if n < 0 {
                    if errno == EAGAIN || errno == EINTR { continue }
                    break
                }
                guard let o = (try? JSONSerialization.jsonObject(with: Data(buf[0..<n]))) as? [String: Any],
                      let ip = withUnsafePointer(to: &from, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Net.ipString($0) } })
                else { continue }
                handle(o, from: ip)
            }
            sendLock.lock(); fd = -1; sendLock.unlock()
            Darwin.close(s)
            Thread.sleep(forTimeInterval: 2)
        }
    }

    private func handle(_ o: [String: Any], from ip: String) {
        guard o["app"] as? String == "fastdrop", let id = o["id"] as? String, !id.isEmpty, id != me.id else { return }
        let kind = o["kind"] as? String
        if kind == "bye" { onBye?(id); return }
        if (o["receivable"] as? Bool) == false { return }
        guard let peer = Peer(json: o, ip: ip, manual: false) else { return }
        onPeer?(peer)
        if kind == "announce" { sendTo(packet("reply"), ip) }   // answer directly so we appear instantly
    }

    /// Wi-Fi reconnects silently drop the membership, so this runs every few seconds.
    private func joinAll() {
        sendLock.lock(); defer { sendLock.unlock() }
        guard fd >= 0 else { return }
        for a in Net.localAddrs() {
            var m = ip_mreq()
            inet_pton(AF_INET, Proto.multicastGroup, &m.imr_multiaddr)
            inet_pton(AF_INET, a.ip, &m.imr_interface)
            setsockopt(fd, Int32(IPPROTO_IP), IP_ADD_MEMBERSHIP, &m, socklen_t(MemoryLayout<ip_mreq>.size))
        }
    }

    // MARK: send

    private func packet(_ kind: String) -> Data {
        (try? JSONSerialization.data(withJSONObject: me.info(kind: kind))) ?? Data()
    }

    private func sendTo(_ data: Data, _ ip: String) {
        sendLock.lock(); defer { sendLock.unlock() }
        sendLocked(data, ip)
    }

    private func sendLocked(_ data: Data, _ ip: String) {
        guard fd >= 0 else { return }
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = port.bigEndian
        guard inet_pton(AF_INET, ip, &to.sin_addr) == 1 else { return }
        data.withUnsafeBytes { b in
            withUnsafePointer(to: &to) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(fd, b.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func announce(_ kind: String) {
        let data = packet(kind)
        sendLock.lock(); defer { sendLock.unlock() }
        guard fd >= 0 else { return }
        var bcast: Set<String> = ["255.255.255.255"]
        for a in Net.localAddrs() {
            var ifaddr = in_addr()
            inet_pton(AF_INET, a.ip, &ifaddr)
            setsockopt(fd, Int32(IPPROTO_IP), IP_MULTICAST_IF, &ifaddr, socklen_t(MemoryLayout<in_addr>.size))
            sendLocked(data, Proto.multicastGroup)
            if let b = a.broadcast { bcast.insert(b) }
        }
        for b in bcast { sendLocked(data, b) }
    }

    private func txLoop() {
        var tick = 0
        while running {
            if tick % 2 == 0 { joinAll() }
            announce("announce")
            tick += 1
            Thread.sleep(forTimeInterval: 2.5)
        }
    }

    // MARK: direct probes

    /// Asks one address directly. Returns the device if it runs FastDrop (and isn't us).
    public static func hello(ip: String, port: UInt16 = Proto.port, timeout: TimeInterval = 2,
                             myId: String, manual: Bool = true) -> Peer? {
        guard let s = try? Sock.connect(host: ip, port: port, timeout: timeout) else { return nil }
        defer { s.close() }
        s.setTimeout(2)
        guard (try? s.write(withUnsafeBytes(of: Proto.magicHello.bigEndian) { Data($0) })) != nil,
              let o = try? s.readJSON(max: 1 << 16),
              let peer = Peer(json: o, ip: ip, manual: manual), peer.id != myId else { return nil }
        return peer
    }

    /// HELLOs every host in each of our /24 networks, 48 at a time. `found` fires per device.
    public static func scan(myId: String, found: @escaping (Peer) -> Void) async -> Int {
        let addrs = Net.localAddrs()
        let mine = Set(addrs.map(\.ip))
        var nets: [String] = []
        for a in addrs {
            let p = a.ip.split(separator: ".")
            guard p.count == 4 else { continue }
            let net = p[0...2].joined(separator: ".")
            if !nets.contains(net) { nets.append(net) }
        }
        let targets = nets.flatMap { net in (1...254).map { "\(net).\($0)" } }.filter { !mine.contains($0) }
        return await withTaskGroup(of: Bool.self) { group in
            var it = targets.makeIterator()
            var count = 0
            func next() -> Bool {
                guard let ip = it.next() else { return false }
                group.addTask {
                    await withCheckedContinuation { c in
                        DispatchQueue.global(qos: .utility).async {
                            let p = hello(ip: ip, timeout: 0.6, myId: myId, manual: false)
                            if let p { found(p) }
                            c.resume(returning: p != nil)
                        }
                    }
                }
                return true
            }
            for _ in 0..<48 { if !next() { break } }
            for await ok in group {
                if ok { count += 1 }
                _ = next()
            }
            return count
        }
    }
}
