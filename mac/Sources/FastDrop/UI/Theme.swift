import AppKit
import SwiftUI
import UniformTypeIdentifiers

// Design tokens shared by every view, mirroring the Android app's Theme.kt:
// violet/blue = send, teal/cyan = receive, amber/rose = web/accent. Views never hard-code colors.

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

enum Palette {
    static let violet = Color(hex: 0x7C6CFF)
    static let blue = Color(hex: 0x4FA3FF)
    static let sendA = Color(hex: 0x8B5CF6)
    static let sendB = Color(hex: 0x3B82F6)
    static let recvA = Color(hex: 0x10D9A0)
    static let recvB = Color(hex: 0x0EA5E9)
    static let webA = Color(hex: 0xF59E0B)
    static let webB = Color(hex: 0xEF4466)
    static let success = Color(hex: 0x34D399)
    static let danger = Color(hex: 0xFF5C7A)
    static let warning = Color(hex: 0xFFB547)

    static let brand = LinearGradient(colors: [violet, blue], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let send = LinearGradient(colors: [sendA, sendB], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let receive = LinearGradient(colors: [recvA, recvB], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let web = LinearGradient(colors: [webA, webB], startPoint: .topLeading, endPoint: .bottomTrailing)

    /// Avatar colors per device type (TVs use the warm gradient, like Android).
    static func colors(forDeviceType t: String) -> [Color] {
        switch t {
        case "tv": return [webA, webB]
        case "laptop", "pc", "computer", "desktop": return [recvA, recvB]
        default: return [sendA, sendB]
        }
    }

    static func gradient(forDeviceType t: String) -> LinearGradient {
        LinearGradient(colors: colors(forDeviceType: t), startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// What the app is doing right now; tints the radar and ambient background.
enum Mood: Equatable {
    case idle, sending, receiving

    var colors: [Color] {
        switch self {
        case .idle: return [Palette.violet, Palette.blue]
        case .sending: return [Palette.sendA, Palette.sendB]
        case .receiving: return [Palette.recvA, Palette.recvB]
        }
    }
}

/// The app's small motion vocabulary. Every spring is interruptible and retargetable.
/// Pass `reduceMotion` from `@Environment(\.accessibilityReduceMotion)` where a view animates itself.
enum Motion {
    /// Things appearing, leaving, or changing size: cards, bubbles, chips, toasts.
    static let pop = Animation.spring(response: 0.46, dampingFraction: 0.68)
    /// Hover and press feedback.
    static let snappy = Animation.spring(response: 0.28, dampingFraction: 0.78)
    /// Big, calm moves: device ring re-layout, progress.
    static let gentle = Animation.spring(response: 0.7, dampingFraction: 0.86)
    /// Celebration (success checkmark, drop-target swell).
    static let bouncy = Animation.spring(response: 0.42, dampingFraction: 0.52)

    static func pop(_ reduce: Bool) -> Animation { reduce ? .easeOut(duration: 0.16) : pop }
    static func snappy(_ reduce: Bool) -> Animation { reduce ? .easeOut(duration: 0.12) : snappy }
    static func gentle(_ reduce: Bool) -> Animation { reduce ? .easeOut(duration: 0.2) : gentle }
    static func bouncy(_ reduce: Bool) -> Animation { reduce ? .easeOut(duration: 0.16) : bouncy }
}

enum DeviceGlyph {
    static func symbol(forType t: String) -> String {
        switch t {
        case "tv": return "tv"
        case "laptop": return "laptopcomputer"
        case "pc", "computer", "desktop": return "desktopcomputer"
        default: return "iphone"
        }
    }

    static func label(forType t: String) -> String {
        switch t {
        case "tv": return "TV"
        case "laptop", "pc", "computer", "desktop": return "Computer"
        default: return "Phone"
        }
    }
}

enum FileIcon {
    /// Finder's icon for a real file or folder.
    static func image(for url: URL) -> NSImage { NSWorkspace.shared.icon(forFile: url.path) }

    /// Finder's icon for a file name we only know by name (incoming offers, history).
    static func image(forName name: String) -> NSImage {
        let ext = (name as NSString).pathExtension
        let type = ext.isEmpty ? UTType.data : (UTType(filenameExtension: ext) ?? .data)
        return NSWorkspace.shared.icon(for: type)
    }
}
