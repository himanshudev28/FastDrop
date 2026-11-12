import AppKit
import AVFoundation
import SwiftUI
import UserNotifications

/// Live status of the macOS permissions FastDrop uses, with one-tap ways to grant them.
/// Local Network has no query API; it counts as working once a nearby device has been seen.
@Observable @MainActor
final class Permissions {
    static let shared = Permissions()

    enum State { case allowed, denied, notAsked }

    private(set) var camera: State = .notAsked
    private(set) var notifications: State = .notAsked

    private var canUseNotifications: Bool { Bundle.main.bundleIdentifier != nil }

    func refresh() {
        camera = switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .allowed
        case .notDetermined: .notAsked
        default: .denied
        }
        guard canUseNotifications else { return }
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let st: State = switch s.authorizationStatus {
            case .authorized, .provisional: .allowed
            case .notDetermined: .notAsked
            default: .denied
            }
            Task { @MainActor in Permissions.shared.notifications = st }
        }
    }

    /// Asks macOS (shows the system prompt) when never asked; otherwise opens the Settings pane.
    func requestCamera() {
        guard camera == .notAsked else { Self.open("Privacy_Camera"); return }
        AVCaptureDevice.requestAccess(for: .video) { _ in Task { @MainActor in Permissions.shared.refresh() } }
    }

    func requestNotifications() {
        guard canUseNotifications else { return }
        guard notifications == .notAsked else {
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.notifications?id=com.fastdrop.mac")!)
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            Task { @MainActor in Permissions.shared.refresh() }
        }
    }

    static func open(_ privacyAnchor: String) {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?\(privacyAnchor)")!)
    }
}

/// Settings → Permissions.
struct PermissionsSection: View {
    @Environment(AppModel.self) private var model
    private let perms = Permissions.shared

    var body: some View {
        Section {
            row("Local Network", symbol: "network",
                detail: "Finds phones and TVs and sends files to them.",
                state: model.devices.isEmpty ? nil : .allowed,
                pending: "No device seen yet",
                action: ("Open Settings", { Permissions.open("Privacy_LocalNetwork") }))
            row("Camera", symbol: "camera",
                detail: "Scans the phone's QR code for the direct Wi‑Fi link.",
                state: perms.camera,
                action: (perms.camera == .notAsked ? "Allow…" : "Open Settings", { perms.requestCamera() }))
            row("Notifications", symbol: "bell.badge",
                detail: "Tells you about incoming files while FastDrop is in the background.",
                state: perms.notifications,
                action: (perms.notifications == .notAsked ? "Allow…" : "Open Settings", { perms.requestNotifications() }))
        } header: {
            Text("Permissions")
        } footer: {
            Text("Signed with the “FastDrop Dev” certificate, so macOS remembers these across rebuilds.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .onAppear { perms.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            perms.refresh()   // picks up changes made in System Settings
        }
    }

    @ViewBuilder
    private func row(_ title: String, symbol: String, detail: String, state: Permissions.State?,
                     pending: String = "Not allowed yet", action: (String, () -> Void)) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(Palette.violet)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if state == .allowed {
                Label("Allowed", systemImage: "checkmark.circle.fill")
                    .font(.callout).foregroundStyle(Palette.success)
                    .labelStyle(.titleAndIcon)
            } else {
                VStack(alignment: .trailing, spacing: 4) {
                    Text(state == .denied ? "Turned off" : pending)
                        .font(.caption).foregroundStyle(state == .denied ? Palette.danger : .secondary)
                    Button(action.0, action: action.1).controlSize(.small)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(title) permission")
    }
}
