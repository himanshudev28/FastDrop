import Darwin
import Foundation

/// Wire protocol, identical to the Android app (`Io.kt` / `Proto`):
///
///  Every TCP connection starts with a 4-byte big-endian magic.
///  HELLO: client sends magic, server answers `[int len][json device info]`.
///  CTRL : sender sends magic + `[len][offer json]`, receiver answers `[len][{"accept":bool,"reason":…}]`
///         and, once every byte has arrived, `[len][{"ok":true}]`.
///  DATA : sender opens N of these in parallel: magic + `[int idLen][id bytes]`, then frames
///         `[int fileIndex][long offset][int length][raw bytes…]`, ended by fileIndex = -1.
public enum Proto {
    public static let port: UInt16 = 53530
    public static let discoveryPort: UInt16 = 53531
    public static let multicastGroup = "224.0.0.168"

    public static let magicCtrl: Int32 = 0x4644_4331   // "FDC1"
    public static let magicData: Int32 = 0x4644_4431   // "FDD1"
    public static let magicHello: Int32 = 0x4644_4831  // "FDH1"

    public static let chunk = 8 << 20          // 8 MB work units shared by the parallel streams
    public static let socketBuffer = 4 << 20   // big TCP windows for fast Wi-Fi
    public static let ioBuffer = 1 << 20
    public static let maxJSON = 32 << 20
    public static let maxFileSize: Int64 = 1 << 46
}

public enum FDError: Error, LocalizedError, Equatable {
    case closed
    case timeout
    case cancelled
    case system(String)
    case protocolError(String)

    public var errorDescription: String? {
        switch self {
        case .closed: return "Connection closed"
        case .timeout: return "Timed out"
        case .cancelled: return "Cancelled"
        case .system(let s): return s
        case .protocolError(let s): return s
        }
    }

    static func errno(_ what: String) -> FDError {
        .system("\(what): \(String(cString: strerror(Darwin.errno)))")
    }
}

/// A blocking BSD TCP socket. One thread owns reads/writes; any thread may call `shutdown()`
/// to wake a blocked read (macOS `close()` alone doesn't). The owner closes it.
public final class Sock: @unchecked Sendable {
    public let fd: Int32
    private let lock = NSLock()
    private var isClosed = false
    public private(set) var remoteIP = ""

    init(fd: Int32) {
        self.fd = fd
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
    }

    deinit { close() }

    /// Connects with a timeout. `sendBuffer` must be set before connecting to get a large TCP window.
    public static func connect(host: String, port: UInt16, timeout: TimeInterval, sendBuffer: Int? = nil) throws -> Sock {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { throw FDError.system("Bad address \(host)") }

        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw FDError.errno("socket") }
        let s = Sock(fd: fd)
        s.remoteIP = host
        if let b = sendBuffer { s.setInt(SOL_SOCKET, SO_SNDBUF, Int32(b)) }

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if r != 0 {
            guard errno == EINPROGRESS else { throw FDError.errno("Can't reach \(host)") }
            var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            let n = poll(&p, 1, Int32(timeout * 1000))
            if n == 0 { throw FDError.timeout }
            if n < 0 { throw FDError.errno("connect") }
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
            if err != 0 { throw FDError.system("Can't reach \(host): \(String(cString: strerror(err)))") }
        }
        _ = fcntl(fd, F_SETFL, flags & ~O_NONBLOCK)
        return s
    }

    func setInt(_ level: Int32, _ name: Int32, _ value: Int32) {
        var v = value
        setsockopt(fd, level, name, &v, socklen_t(MemoryLayout<Int32>.size))
    }

    public func setNoDelay() { setInt(Int32(IPPROTO_TCP), TCP_NODELAY, 1) }
    public func setKeepAlive() { setInt(SOL_SOCKET, SO_KEEPALIVE, 1) }

    /// Read/write timeout in seconds; 0 = block forever.
    public func setTimeout(_ seconds: TimeInterval) {
        var tv = timeval(tv_sec: Int(seconds), tv_usec: Int32((seconds - floor(seconds)) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    }

    // MARK: reading

    /// Reads up to `count` bytes. Returns 0 only at end of stream.
    public func readSome(_ ptr: UnsafeMutableRawPointer, _ count: Int) throws -> Int {
        while true {
            let n = recv(fd, ptr, count, 0)
            if n > 0 { return n }
            if n == 0 { return 0 }
            switch errno {
            case EINTR: continue
            case EAGAIN: throw FDError.timeout
            default: throw FDError.errno("recv")
            }
        }
    }

    public func readExact(_ ptr: UnsafeMutableRawPointer, _ count: Int) throws {
        var got = 0
        while got < count {
            let n = try readSome(ptr + got, count - got)
            if n == 0 { throw FDError.closed }
            got += n
        }
    }

    public func readData(_ count: Int) throws -> Data {
        var d = Data(count: count)
        if count == 0 { return d }
        try d.withUnsafeMutableBytes { try readExact($0.baseAddress!, count) }
        return d
    }

    public func readInt32() throws -> Int32 {
        var v: Int32 = 0
        try withUnsafeMutableBytes(of: &v) { try readExact($0.baseAddress!, 4) }
        return Int32(bigEndian: v)
    }

    public func readJSON(max: Int = Proto.maxJSON) throws -> [String: Any] {
        let n = Int(try readInt32())
        guard n >= 0, n <= max else { throw FDError.protocolError("Bad message") }
        let d = try readData(n)
        guard let o = try JSONSerialization.jsonObject(with: d) as? [String: Any] else {
            throw FDError.protocolError("Bad message")
        }
        return o
    }

    // MARK: writing

    public func writeAll(_ ptr: UnsafeRawPointer, _ count: Int) throws {
        var sent = 0
        while sent < count {
            let n = send(fd, ptr + sent, count - sent, 0)
            if n > 0 { sent += n; continue }
            if n < 0 && errno == EINTR { continue }
            if n < 0 && errno == EAGAIN { throw FDError.timeout }
            throw FDError.errno("send")
        }
    }

    public func write(_ d: Data) throws {
        if d.isEmpty { return }
        try d.withUnsafeBytes { try writeAll($0.baseAddress!, d.count) }
    }

    public func writeJSON(_ o: [String: Any]) throws {
        let body = try JSONSerialization.data(withJSONObject: o)
        var out = Data()
        out.appendBE(Int32(body.count))
        out.append(body)
        try write(out)
    }

    /// True when the peer has closed its side (checked without consuming data).
    public func peerClosed() -> Bool {
        var p = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        guard poll(&p, 1, 0) > 0 else { return false }
        if p.revents & Int16(POLLHUP | POLLERR | POLLNVAL) != 0 { return true }
        var b: UInt8 = 0
        let n = recv(fd, &b, 1, MSG_PEEK | MSG_DONTWAIT)
        return n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)
    }

    /// Wakes any thread blocked on this socket. Safe from any thread.
    public func shutdown() {
        lock.lock(); defer { lock.unlock() }
        if !isClosed { Darwin.shutdown(fd, SHUT_RDWR) }
    }

    public func close() {
        lock.lock(); defer { lock.unlock() }
        if isClosed { return }
        isClosed = true
        Darwin.close(fd)
    }
}

extension Data {
    mutating func appendBE(_ v: Int32) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
    mutating func appendBE(_ v: Int64) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
}

/// Human-readable sizes, speeds and durations shared by the app and CLI.
public enum Fmt {
    public static func bytes(_ n: Int64) -> String {
        let units = ["B", "KB", "MB", "GB", "TB"]
        var v = Double(n), i = 0
        while v >= 1024 && i < units.count - 1 { v /= 1024; i += 1 }
        if i == 0 { return "\(n) B" }
        return String(format: v >= 100 ? "%.0f %@" : "%.1f %@", v, units[i])
    }

    public static func speed(_ bytesPerSecond: Double) -> String {
        let mb = bytesPerSecond / 1_048_576
        return mb >= 1 ? String(format: "%.1f MB/s", mb) : String(format: "%.0f KB/s", bytesPerSecond / 1024)
    }

    public static func duration(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--" }
        let s = Int(seconds.rounded())
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}
