# ⚡ FastDrop for Mac

A native SwiftUI Mac app that sends and receives files with **FastDrop for Android** (phones and TVs) over the same Wi‑Fi or phone hotspot. It speaks the same protocol, so no internet, accounts or cloud are involved.

- **AirDrop-style radar:** nearby phones and TVs pop onto an orbit around your Mac.
  - Drag files onto a device to send them.
  - Or drop them in the tray, then click a device.
- **Fast:** plain TCP with 4 parallel streams and zero-copy `sendfile()` (1–8 streams in Settings).
- **Receive anywhere:**
  - An accept card with a 60‑second countdown, plus a notification if the app is in the background.
  - Files are saved to `~/Downloads/FastDrop`, keeping any sub-folders.
  - Half-received files are never left behind.
- **Mac-native:**
  - A toolbar, a History inspector, a Settings window (⌘,) and a menu bar extra.
  - Drop files on the Dock icon to add them.
  - Keyboard shortcuts: ⌘O to add files, ⌘R to scan the network, ⇧⌘K to show the QR code, ⌘. to cancel.
- **Spring motion everywhere,** with the animation reduced when *Reduce Motion* is on.

## Build

You need only the Command Line Tools (Swift 6, macOS 14+ SDK). Xcode isn't needed.

```bash
cd mac
scripts/build-app.sh             # -> build/FastDrop.app
scripts/build-app.sh --install   # also copy to /Applications and launch
```

On first launch, macOS asks for **Local Network** access. Allow it, or the Mac can't see your phones. If the firewall asks about incoming connections, allow those too.

## Connect a phone

1. Put both devices on the same Wi‑Fi, or join the phone's hotspot from the Mac.
2. Open FastDrop on the phone. Each device shows up on the other's screen within a few seconds.
3. Not showing up?
   - Use **Scan Network** (⌘R) or **Add Device** with the phone's IP address or a `fastdrop://` link.
   - Or scan the Mac's **QR code** (⇧⌘K) from the phone's *Send → Scan QR*.

Macs don't support Wi‑Fi Direct. Use the same Wi‑Fi or the phone's hotspot instead.

## Command-line client

`fastdrop-cli` uses the same core code with no UI. It's handy for tests:

```bash
swift run fastdrop-cli hello <ip>
swift run fastdrop-cli send <ip> file1 folder2 --streams 4
swift run fastdrop-cli recv --dir ~/Downloads/FastDrop --auto
swift run fastdrop-cli scan
```

## Layout

```
Sources/FastDropCore/   protocol (Proto, Sock), Discovery, Receiver, Sender, Transfer — no UI
Sources/FastDrop/       SwiftUI app: AppModel (single source of truth) + UI/
Sources/FastDropCLI/    headless client
scripts/                build-app.sh, make-icon.swift
```
