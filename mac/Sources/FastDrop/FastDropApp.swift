import AppKit
import FastDropCore
import SwiftUI

@main
struct FastDropApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel.shared

    var body: some Scene {
        Window("FastDrop", id: "main") {
            ContentView()
                .environment(model)
                .frame(minWidth: 760, minHeight: 580)
        }
        .defaultSize(width: 980, height: 700)
        .windowToolbarStyle(.unified)
        .commands { FastDropCommands(model: model) }

        Settings {
            SettingsView().environment(model)
        }

        // MenuBarExtra writes `isInserted` back on every scene update. A plain binding to an @Observable
        // property counts each write as a change, so the scene re-renders, writes again, and the main
        // thread spins forever (beach ball). Only write when the value really changes.
        MenuBarExtra("FastDrop", systemImage: "bolt.horizontal.circle.fill", isInserted: Binding(
            get: { model.showMenuBarIcon },
            set: { if model.showMenuBarIcon != $0 { model.showMenuBarIcon = $0 } }
        )) {
            MenuBarContent().environment(model)
        }
    }
}

struct FastDropCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("Add Files…") { model.showMainWindow(); model.chooseFiles() }
                .keyboardShortcut("o")
            Button("Clear Files") { model.clearPicked() }
                .keyboardShortcut(.delete, modifiers: [.command])
                .disabled(model.picked.isEmpty)
        }
        CommandMenu("Devices") {
            // These open popovers/sheets in the main window, so make sure it's there first.
            Button("Show My QR Code") { model.showMainWindow(); model.showQR.toggle() }
                .keyboardShortcut("k", modifiers: [.command, .shift])
                .disabled(model.offer != nil)
            Button("Scan Phone's QR Code…") { model.showMainWindow(); model.showScanner = true }
                .keyboardShortcut("j", modifiers: [.command, .shift])
                .disabled(model.offer != nil || model.joining != nil)
            Button("Add Device by IP…") { model.showMainWindow(); model.showAddDevice = true }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(model.offer != nil)
            Button("Scan Network") { model.scanNetwork() }
                .keyboardShortcut("r")
                .disabled(model.scanning)
            Divider()
            Button("Cancel Transfer") { model.cancelTransfer() }
                .keyboardShortcut(".")
                .disabled(!model.isBusy)
        }
        CommandGroup(after: .sidebar) {
            Button(model.showHistory ? "Hide History" : "Show History") { model.showHistory.toggle() }
                .keyboardShortcut("i", modifiers: [.command, .option])
            Button("Open Received Folder") { model.openSaveFolder() }
                .keyboardShortcut("d", modifiers: [.command, .shift])
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // `swift run` launches a bare binary: make it a regular Dock app with a menu bar.
        if NSApp.activationPolicy() != .regular { NSApp.setActivationPolicy(.regular) }
        MainActor.assumeIsolated { AppModel.shared.start() }
        NSApp.activate()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationWillTerminate(_ notification: Notification) {
        MainActor.assumeIsolated { AppModel.shared.shutdown() }
    }

    /// Files dropped on the Dock icon or opened with FastDrop land in the tray.
    func application(_ application: NSApplication, open urls: [URL]) {
        MainActor.assumeIsolated {
            AppModel.shared.addFiles(urls.filter(\.isFileURL))
            AppModel.shared.showMainWindow()
        }
    }
}
