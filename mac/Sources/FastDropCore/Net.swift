import Darwin
import Foundation

/// One IPv4 address on a usable LAN interface (Wi-Fi, Ethernet, Internet Sharing bridge…).
public struct LocalAddr: Hashable, Sendable {
    public let ip: String
    public let broadcast: String?
    public let prefix: Int
    public let iface: String
}

public enum Net {
    // VPN tunnels, Apple peer-to-peer links and loopback never reach other FastDrop devices.
    private static let skip = ["lo", "utun", "ipsec", "ppp", "gif", "stf", "awdl", "llw", "anpi"]

    public static func localAddrs() -> [LocalAddr] {
        var out: [LocalAddr] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return out }
        defer { freeifaddrs(head) }
        var cur: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cur {
            defer { cur = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                  let sa = p.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let name = String(cString: p.pointee.ifa_name)
            if skip.contains(where: { name.hasPrefix($0) }) { continue }
            guard let ip = ipString(sa), !ip.hasPrefix("169.254.") else { continue }
            var bcast: String?
            if flags & IFF_BROADCAST != 0, let b = p.pointee.ifa_dstaddr { bcast = ipString(b) }
            var prefix = 24
            if let m = p.pointee.ifa_netmask {
                let raw = m.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                prefix = UInt32(bigEndian: raw).nonzeroBitCount
            }
            out.append(LocalAddr(ip: ip, broadcast: bcast, prefix: prefix, iface: name))
        }
        // The address the system actually routes through goes first (an interface can carry stale aliases).
        let primary = routedIP()
        return out.sorted {
            let a = $0.ip == primary ? -1 : rank($0.iface), b = $1.ip == primary ? -1 : rank($1.iface)
            return a < b
        }
    }

    /// Source address the kernel picks for the default route. A UDP "connect" sends no packets.
    static func routedIP() -> String? {
        let s = socket(AF_INET, SOCK_DGRAM, 0)
        guard s >= 0 else { return nil }
        defer { close(s) }
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = UInt16(53).bigEndian
        inet_pton(AF_INET, "1.1.1.1", &to.sin_addr)
        let ok = withUnsafePointer(to: &to) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard ok == 0 else { return nil }
        var me = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let got = withUnsafeMutablePointer(to: &me) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(s, $0, &len) }
        }
        guard got == 0 else { return nil }
        return withUnsafePointer(to: &me) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { ipString($0) } }
    }

    /// en0 (built-in Wi-Fi / Ethernet) first, other en*, then Internet Sharing bridges, then anything else.
    private static func rank(_ n: String) -> Int {
        if n == "en0" { return 0 }
        if n.hasPrefix("en") { return 1 }
        if n.hasPrefix("bridge") { return 2 }
        return 3
    }

    public static func lanIPs() -> [String] {
        var seen = Set<String>()
        return localAddrs().map(\.ip).filter { seen.insert($0).inserted }
    }

    static func ipString(_ sa: UnsafePointer<sockaddr>) -> String? {
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let ok = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin -> Bool in
            var a = sin.pointee.sin_addr
            return inet_ntop(AF_INET, &a, &buf, socklen_t(INET_ADDRSTRLEN)) != nil
        }
        return ok ? String(cString: buf) : nil
    }

    public static func isIPv4(_ s: String) -> Bool {
        var a = in_addr()
        return inet_pton(AF_INET, s, &a) == 1
    }
}

/// Who this Mac is on the network. Thread-safe so receiver/discovery threads can read the live name.
public final class Identity: @unchecked Sendable {
    private let lock = NSLock()
    public let id: String
    private var _name: String
    public let type: String
    public let port: UInt16

    public init(id: String, name: String, type: String = "laptop", port: UInt16 = Proto.port) {
        self.id = id; self._name = name; self.type = type; self.port = port
    }

    public var name: String {
        get { lock.lock(); defer { lock.unlock() }; return _name }
        set { lock.lock(); _name = newValue; lock.unlock() }
    }

    /// The HELLO answer and the body of discovery packets (Android `Hub.info()`).
    public func info(kind: String? = nil) -> [String: Any] {
        var o: [String: Any] = ["app": "fastdrop", "v": 2, "caps": Proto.caps, "id": id, "name": name, "type": type,
                                "port": Int(port), "receivable": true]
        if let kind { o["kind"] = kind }
        return o
    }
}

/// A FastDrop device we can send to.
public struct Peer: Hashable, Sendable, Identifiable {
    public let id: String
    public var name: String
    public var type: String
    public var ip: String
    public var port: UInt16
    public var manual: Bool
    /// Protocol version the device announced in HELLO / discovery (1 when it sent none).
    public var v: Int = 1
    public var caps: [String] = []

    public init(id: String, name: String, type: String, ip: String, port: UInt16, manual: Bool = false, v: Int = 1, caps: [String] = []) {
        self.id = id; self.name = name; self.type = type; self.ip = ip; self.port = port; self.manual = manual
        self.v = v; self.caps = caps
    }

    init?(json o: [String: Any], ip: String, manual: Bool) {
        guard o["app"] as? String == "fastdrop", let id = o["id"] as? String, !id.isEmpty else { return nil }
        let port = (o["port"] as? NSNumber)?.intValue ?? Int(Proto.port)
        let type = (o["type"] as? String) ?? "phone"
        // Some TVs report an empty device name: fall back to something readable.
        let raw = ((o["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = type == "tv" ? "Android TV" : type == "phone" ? "Android phone" : ip
        self.init(id: String(id.prefix(64)),
                  name: String((raw.isEmpty ? fallback : raw).prefix(60)),
                  type: type,
                  ip: ip,
                  port: (1...65535).contains(port) ? UInt16(port) : Proto.port,
                  manual: manual,
                  v: (o["v"] as? NSNumber)?.intValue ?? 1,
                  caps: (o["caps"] as? [String]) ?? [])
    }
}

/// `fastdrop://join?…` links shown as QR codes (Android `JoinInfo`). The Mac only hosts LAN
/// addresses (no Wi-Fi Direct on macOS) but understands every field the phones put in theirs.
public struct JoinLink: Sendable {
    public var id: String
    public var name: String
    public var type: String
    public var port: UInt16 = Proto.port
    public var lanIPs: [String] = []
    public var mode: String?      // "p2p" | "hs" when the other device hosts a network
    public var ssid: String?
    public var pass: String?
    public var hostIP: String?

    public init(id: String, name: String, type: String, port: UInt16 = Proto.port, lanIPs: [String]) {
        self.id = id; self.name = name; self.type = type; self.port = port; self.lanIPs = lanIPs
    }

    public var url: String {
        var c = URLComponents()
        c.scheme = "fastdrop"
        c.host = "join"
        var q = [URLQueryItem(name: "v", value: "2"), URLQueryItem(name: "i", value: id),
                 URLQueryItem(name: "n", value: name), URLQueryItem(name: "t", value: type)]
        if port != Proto.port { q.append(URLQueryItem(name: "p", value: String(port))) }
        if !lanIPs.isEmpty { q.append(URLQueryItem(name: "l", value: lanIPs.joined(separator: ","))) }
        q.append(URLQueryItem(name: "r", value: "r"))   // role: shown by a receiver (Android MainActivity.codeRole)
        // Strict encoding: URLComponents leaves "+" raw, which Android's Uri decodes as a space.
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=#?")
        c.percentEncodedQueryItems = q.map {
            URLQueryItem(name: $0.name, value: $0.value?.addingPercentEncoding(withAllowedCharacters: allowed))
        }
        return c.string ?? "fastdrop://join"
    }

    /// Every address worth trying, the hosted network's address first.
    public var candidates: [String] {
        var seen = Set<String>()
        return ([hostIP].compactMap { $0 } + lanIPs).filter { seen.insert($0).inserted }
    }

    public static func parse(_ text: String) -> JoinLink? {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme?.lowercased() == "fastdrop", c.host?.lowercased() == "join" else { return nil }
        func q(_ k: String) -> String? { c.queryItems?.first { $0.name == k }?.value }
        guard let id = q("i")?.trimmingCharacters(in: .whitespaces), !id.isEmpty else { return nil }
        let port = Int(q("p") ?? "").flatMap { (1...65535).contains($0) ? UInt16($0) : nil } ?? Proto.port
        let lan = (q("l") ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter(Net.isIPv4).prefix(8)
        var j = JoinLink(id: String(id.prefix(64)), name: String((q("n") ?? "Device").prefix(40)),
                         type: q("t") == "tv" ? "tv" : "phone", port: port, lanIPs: Array(lan))
        if let s = q("s"), !s.isEmpty, let m = q("m"), m == "p2p" || m == "hs" {
            j.mode = m; j.ssid = s
            j.pass = q("k").flatMap { (8...63).contains($0.count) ? $0 : nil }
        }
        j.hostIP = q("h").flatMap { Net.isIPv4($0) ? $0 : nil }
        return j
    }
}
