import AppKit
import FastDropCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var nameDraft = ""

    var body: some View {
        @Bindable var model = model
        Form {
            Section("This Mac") {
                TextField("Device name", text: $nameDraft)
                    .onSubmit(commitName)
                    .onAppear { nameDraft = model.deviceName }
                Text("Phones and TVs see this name when you connect.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Receiving") {
                LabeledContent("Save files to") {
                    HStack(spacing: 8) {
                        Label(model.saveFolder.path.abbreviatingHome, systemImage: "folder")
                            .lineLimit(1).truncationMode(.middle)
                            .foregroundStyle(.secondary)
                        Button("Choose…", action: chooseFolder).disabled(model.isBusy)
                    }
                }
                Toggle("Accept files automatically", isOn: $model.autoAccept)
                Text("Only on networks you trust.").font(.caption).foregroundStyle(.secondary)
                Toggle("Show in Finder when done", isOn: $model.revealWhenDone)
            }
            Section("Sending") {
                Picker("Parallel streams", selection: $model.streams) {
                    ForEach(1...8, id: \.self) { Text("\($0)").tag($0) }
                }
                Text("4 is fastest on most Wi‑Fi. Use fewer on older TVs.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("General") {
                Toggle("Show in menu bar", isOn: $model.showMenuBarIcon)
            }
            PermissionsSection()
            Section("About") {
                LabeledContent("Version", value: version)
                LabeledContent("Ports", value: "TCP 53530 · UDP 53531")
                Text("Works with FastDrop for Android on the same Wi‑Fi or hotspot. No internet needed.")
                    .font(.caption).foregroundStyle(.secondary)
                if let err = model.listenError {
                    Text(err).font(.caption).foregroundStyle(.red)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .onDisappear(perform: commitName)
    }

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    private func commitName() {
        let t = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { nameDraft = model.deviceName } else {
            if t != model.deviceName { model.deviceName = t }
            nameDraft = t
        }
    }

    private func chooseFolder() {
        let p = NSOpenPanel()
        p.canChooseDirectories = true
        p.canChooseFiles = false
        p.canCreateDirectories = true
        p.allowsMultipleSelection = false
        p.prompt = "Choose"
        p.directoryURL = model.saveFolder
        if p.runModal() == .OK, let u = p.url { model.saveFolder = u }
    }
}

private extension String {
    var abbreviatingHome: String { (self as NSString).abbreviatingWithTildeInPath }
}

struct MenuBarContent: View {
    @Environment(AppModel.self) private var model

    private var status: String {
        if let o = model.offer { return "\(o.from) wants to send you files" }
        if let p = model.pending { return p.status }
        if let ssid = model.joining { return "Joining “\(ssid)”…" }
        if let l = model.live, l.state == .running {
            let pct = Int((l.fraction * 100).rounded())
            return (model.activeDirectionOutgoing ?? true) ? "Sending \(pct)%" : "Receiving \(pct)%"
        }
        let n = model.devices.count
        return "Ready · \(n) \(n == 1 ? "device" : "devices") nearby"
    }

    var body: some View {
        Text(status)
        Divider()
        Button("Open FastDrop") { model.showMainWindow() }
        Button("Send Files…") {
            model.showMainWindow()   // the tray lives in the main window
            model.chooseFiles()
        }
        Button("Open Received Folder") { model.openSaveFolder() }
        Divider()
        // SettingsLink from a menu-bar menu can open Settings behind other apps; activate first.
        Button("Settings…") {
            NSApp.activate()
            NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
        }
        Button("Quit FastDrop") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }
}
