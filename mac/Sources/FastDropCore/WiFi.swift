import Foundation

/// Joins a network a phone hosts for FastDrop: its Wi‑Fi Direct group ("DIRECT-…") or local-only
/// hotspot. macOS has no Wi‑Fi Direct API for apps, but it can join the group as a regular Wi‑Fi
/// client, which Android allows. Uses `networksetup`, which needs no Location permission
/// (unlike CoreWLAN scanning).
public enum WiFi {
    /// The Wi‑Fi interface name, e.g. "en0".
    public static func interfaceName() -> String? {
        guard let out = run(["-listallhardwareports"]) else { return nil }
        var isWiFi = false
        for line in out.split(separator: "\n") {
            if line.hasPrefix("Hardware Port:") { isWiFi = line.contains("Wi-Fi") || line.contains("AirPort") }
            if isWiFi, line.hasPrefix("Device:") {
                return line.replacingOccurrences(of: "Device:", with: "").trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    /// Switches the Mac's Wi‑Fi to `ssid`. Blocking (a few seconds); call off the main thread.
    public static func join(ssid: String, password: String?) throws {
        guard let dev = interfaceName() else { throw FDError.system("This Mac has no Wi‑Fi") }
        var args = ["-setairportnetwork", dev, ssid]
        if let password, !password.isEmpty { args.append(password) }
        guard let out = run(args) else { throw FDError.system("Couldn't run networksetup") }
        // networksetup exits 0 even on failure; any output means it didn't join.
        let msg = out.trimmingCharacters(in: .whitespacesAndNewlines)
        if !msg.isEmpty {
            if msg.localizedCaseInsensitiveContains("could not find") {
                throw FDError.system("Can't see “\(ssid)”. Keep the phone's QR screen open and move closer.")
            }
            throw FDError.system("Couldn't join “\(ssid)”: \(msg)")
        }
    }

    /// The Wi‑Fi network's router (DHCP option 3). On a phone's hotspot or Wi‑Fi Direct group that's the phone.
    public static func routerIP() -> String? {
        guard let dev = interfaceName() else { return nil }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/ipconfig")
        p.arguments = ["getoption", dev, "router"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = Pipe()
        do { try p.run() } catch { return nil }
        let out = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        p.waitUntilExit()
        let ip = out.trimmingCharacters(in: .whitespacesAndNewlines)
        return Net.isIPv4(ip) ? ip : nil
    }

    @discardableResult
    private static func run(_ args: [String]) -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return nil }
        // networksetup can hang when the Wi‑Fi driver is stuck; don't let the UI wait forever.
        let watchdog = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 25, execute: watchdog)
        defer { watchdog.cancel() }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }
}
