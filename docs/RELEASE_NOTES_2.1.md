# FastDrop 2.1

A new look, and a lot more you can do with it: resume, trusted devices, text, multi-send, plus a native Mac app and a PC command-line client.

## Highlights

- 🎨 **New "Signal" look** with System / Light / Dark themes, on phones and TVs.
- 🔁 **Resume interrupted transfers.** Only the missing chunks are sent again.
- ✅ **Trusted devices.** "Always accept from…" for devices you know. New senders show a 4-digit confirmation code on both screens.
- 💬 **Send text, links and the clipboard**, not only files.
- 👥 **Send to several devices** at once.
- 🍎 **FastDrop for Mac**: a native app for Apple silicon and Intel.
- 💻 **PC command-line client** for Windows, Linux and macOS (`tools/fastdrop.py`, Python standard library only).

## Android

- Redesigned Home, Send, Receive, Settings and History screens, with a Quick send row (Apps, Photos, Videos, Music, Files, Text).
- Persistent **History**, with Copy and Open for received text.
- **Direct Share** shortcuts, a **Quick Settings tile** and a **home-screen widget**.
- Trusted devices list in Settings, and an Accept-from setting: Ask every time, Trusted devices (default) or Everyone.
- Optional **CRC32** per-chunk verification.
- Multi-device send, and a **Retry** action on failed sends.
- Hardening: safer resume and finalize handling, a persistent replay cache, and no overwriting of existing trust entries.
- Prepared for the Android 17 local-network permission.
- New Signal launcher icon, TV banner, notification icon, widget and tile.

## Mac

- Radar-style send screen, History, Settings and a menu bar item.
- **Finder Services:** right-click files, then Services, then **Send with FastDrop**. Text selections work too.
- **Drag files onto the menu-bar icon** to add them to the tray.
- Full protocol 2 support: resume, trusted devices, confirmation code, text, CRC32 and multi-device send.
- Universal build (Apple silicon and Intel), with the new Signal app icon.
- Download `FastDrop-mac.zip` from this release. Unzip, move to Applications, and the first time **right-click → Open**.

## Command-line client (Windows / Linux / macOS)

- `python3 tools/fastdrop.py scan | send | recv | hello | trust`.
- Sends files, folders, text (`--text`) and the clipboard (`--clipboard`). `--crc` turns on CRC32 checks.
- Resume, trusted devices and confirmation codes, with `recv --accept trusted|ask|everyone`.
- State is stored with private (0600) permissions. See [`tools/README.md`](tools/README.md).

## Compatibility

FastDrop 2.1 works with 2.0 devices for sending and receiving files. The new features (resume, trusted devices, text, CRC32 and the confirmation code) need **2.1 on both sides**. Protocol details are in [`PROTOCOL.md`](PROTOCOL.md).

## Known limitations

- The **TV layout hasn't been tested on real TV hardware yet.** Please report what you see.
- **No encryption**, by design, so weak TV processors keep up. Use FastDrop on networks you trust.
- The **Mac app isn't notarized.** Right-click → Open the first time, or run `xattr -dr com.apple.quarantine FastDrop.app`.
- 2.4 GHz Wi-Fi caps at about 11 MB/s. Use 5 GHz for full speed.
