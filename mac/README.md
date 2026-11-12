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
- **Protocol 2 (FastDrop 2.1),** still compatible with 2.0 phones and TVs:
  - Send a text, link or the clipboard to a device (right-click it, or the *Send Text* toolbar button). Received text lands in History with Copy and Open.
  - A 4-digit confirmation code on both screens for first-time senders.
  - **Trusted devices:** tick *Always accept from …* once. Auto-accept is *Ask every time*, *Trusted devices* (default) or *Everyone*, and the trusted list is in Settings.
  - **Resume:** an interrupted transfer continues where it stopped when you press Retry or send again. Partials are hidden `.name.….fdpart` files, pruned after 7 days.
  - Optional **Verify data (CRC32)** per chunk (off by default).
  - **Several devices:** ⌘-click (or right-click → *Select for Sending to Several*) to tick devices, then *Send to N devices*. They are sent to one after another.
- **Spring motion everywhere,** with the animation reduced when *Reduce Motion* is on.

## Build

You need only the Command Line Tools (Swift 6, macOS 14+ SDK). Xcode isn't needed.

```bash
cd mac
scripts/build-app.sh             # -> build/FastDrop.app
scripts/build-app.sh --install   # also copy to /Applications and launch
```

On first launch, macOS asks for **Local Network** access. Allow it, or the Mac can't see your phones. If the firewall asks about incoming connections, allow those too.

## Send from Finder and the menu bar

- **Services menu:** select files in Finder, then right-click → *Services* → **Send with FastDrop** (or select text anywhere → *Services* → **Send Text with FastDrop**). The files land in the tray and, if the device you last used is online, a small prompt offers a one-click send.
  - The first time, macOS may not list it yet. Enable it under *System Settings → Keyboard → Keyboard Shortcuts → Services* (Files and Folders / Text), or log out and back in. Running the built app once from `build/` or `/Applications` registers it.
- **Drop on the menu bar icon:** drag files onto the FastDrop icon in the menu bar to add them to the tray.

## Downloaded builds (Gatekeeper)

Release builds (`FastDrop-mac.zip`) are signed ad-hoc and **not notarized**. After unzipping, right-click `FastDrop.app` → **Open** (then *Open* again) the first time. Or run `xattr -dr com.apple.quarantine FastDrop.app`. Verify the download with the `.sha256` file (`shasum -a 256 -c`).

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
swift run fastdrop-cli send <ip> file1 folder2 --streams 4 [--crc]
swift run fastdrop-cli send <ip> --text "hello"
swift run fastdrop-cli recv --dir ~/Downloads/FastDrop --accept ask|trusted|everyone   # --auto = everyone; --crc agrees to CRC32
swift run fastdrop-cli trust list | trust remove <id>
swift run fastdrop-cli scan
swift run fastdrop-cli selftest        # protocol-2 test vectors; exits 1 on mismatch
```

Test helpers: `--state-dir DIR` keeps the device id, trust keys and resume records in DIR, `--port N` uses another port,
and `send --stop-after-mb N` aborts mid-transfer to test resume. `swift test` runs the same vectors with swift-testing.

## Layout

```
Sources/FastDropCore/   protocol (Proto, Sock), Spec (protocol-2 math), Stores (trust, resume), Discovery, Receiver, Sender, Transfer — no UI
Tests/FastDropCoreTests spec test vectors (swift-testing)
Sources/FastDrop/       SwiftUI app: AppModel (single source of truth) + UI/
Sources/FastDropCLI/    headless client
scripts/                build-app.sh, make-icon.swift
```
