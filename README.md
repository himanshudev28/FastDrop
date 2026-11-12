<div align="center">

# ⚡ FastDrop

**Fast, offline file sharing between Android phones, Android TVs and any device with a browser.**

No internet. No accounts. No cloud. Just open it on both devices and tap.

[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B%20%7C%20Android%20TV-3DDC84?logo=android&logoColor=white)](#-install)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Target SDK](https://img.shields.io/badge/targetSdk-35-informational)](#-notes)
[![Dependencies](https://img.shields.io/badge/dependencies-1%20(ZXing)-success)](#-tech-stack)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/himanshudev28/FastDrop?logo=github)](https://github.com/himanshudev28/FastDrop/releases/latest)
[![Build](https://github.com/himanshudev28/FastDrop/actions/workflows/release.yml/badge.svg)](https://github.com/himanshudev28/FastDrop/actions/workflows/release.yml)

<img src="docs/screenshot.png" alt="FastDrop home screen on a Galaxy S21 FE" width="300">

</div>

---

## ✨ Why FastDrop?

| | |
|---|---|
| 🚀 **Fast** | ~**46 MB/s** phone → Mac and ~**35 MB/s** Mac → phone over a 5 GHz hotspot (measured on a Galaxy S21 FE). |
| 📡 **Works offline** | Same Wi‑Fi? Devices find each other automatically. No Wi‑Fi at all? FastDrop builds its own direct link with Wi‑Fi Direct or a local hotspot. |
| 📺 **TV‑ready** | Runs on Android TV with full D‑pad navigation, a built‑in file browser, and a "Show my QR" flow for TVs without cameras. |
| 🌐 **Any device** | iPhone, PC or a phone without the app? **Share with any device** turns your phone into a tiny web server they can open in a browser. |
| 🪶 **Tiny** | ~300 KB APK. No AndroidX. One library (ZXing for QR codes). Runs back to Android 5.0. |
| 🔒 **Private** | Files go straight from one device to the other on your local network. Nothing is uploaded anywhere. |

---

## 📦 Install

1. **[⬇️ Download the latest APK](https://github.com/himanshudev28/FastDrop/releases/latest)** from Releases, or [build it yourself](#-build-from-source).
2. Copy `FastDrop.apk` to the phone or TV and open it, or run:
   ```bash
   adb install FastDrop.apk
   ```
3. **On a TV:** put the APK on a USB stick and install it with a file manager, or use `adb` over the network.

> 💡 **Share the app without internet:** open **Settings → Share FastDrop app** and let your friend scan the codes. Their phone downloads the APK straight from yours.

---

## 🚀 How to use

### 📤 Send
1. Tap **Send** and pick **Apps, Photos, Videos, Music or Files**, or use **Share → FastDrop** from any app.
2. Devices on the same Wi‑Fi appear at the top automatically. Devices nearby over Wi‑Fi Direct are listed below them.
3. Tap a device, or tap **Scan QR** and scan the code on the receiver's screen.

### 📥 Receive
- FastDrop receives whenever it's open, and senders on the same Wi‑Fi see you automatically.
- Tap **Receive** to also show a QR code and become visible over Wi‑Fi Direct, for senders that aren't on your network.
- Accept the incoming files. They're saved to `Download/FastDrop`, and you can install received apps from **Recent**.

### 📺 TV without a camera
On the TV, go to **Send → Show my QR**. On the phone, go to **Receive → Scan sender's QR**. The phone joins the TV's network and the TV sends to it.

### 🌐 Share with a device that doesn't have FastDrop
Tap **Share with any device**. The other device joins the Wi‑Fi in the first QR code, then opens the web page in the second QR code to download or upload files in its browser.

> 🔎 **Device not showing up?** Try **Find on network**, **Enter IP**, or use the QR code.

---

## 🧭 How it connects

```mermaid
flowchart LR
    A[📱 Sender] -->|Same Wi‑Fi?| B{Discovery}
    B -->|Yes| C[Multicast / broadcast / subnet scan]
    B -->|No network| D[Wi‑Fi Direct group<br/>or local hotspot]
    D -->|Pair via QR or nearby list| E
    C --> E[⚡ Parallel TCP streams]
    E --> F[📺 Receiver]
    A -.->|No app on other side| G[🌐 Built‑in web page]
    G -.-> H[💻 iPhone / PC browser]
```

## ⚡ What makes it fast

- **Plain TCP, no TLS:** weak TV processors don't become the bottleneck.
- **Parallel streams:** 4 by default (1–8 in Settings), with large socket buffers and zero‑copy sending.
- **A smart offline link:** a Wi‑Fi Direct group on 5 GHz where possible, matched to the receiver's Wi‑Fi channel so the radio doesn't switch between two channels. On Android 10+ the sender joins from the QR code with no system pop‑up.
- **The radio stays at full speed:** Wi‑Fi low‑latency/high‑performance locks, a wake lock, and a foreground service keep it there during transfers.
- **Many ways to find devices:** multicast, broadcast, a subnet scan, manual IP, Wi‑Fi Direct service discovery, and QR codes.

---

## 🛠 Build from source

**Requirements:** JDK 17 and the Android SDK (compileSdk 36).

```bash
git clone https://github.com/himanshudev28/FastDrop.git
cd FastDrop
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # point to your SDK
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

> ⚠️ Local release builds are signed with your **debug key** so they install directly. Use your own signing config before you publish to a store.

### 🚢 Releasing (GitHub Actions)

[`.github/workflows/release.yml`](.github/workflows/release.yml) builds the APK and publishes it to **Releases** with a SHA‑256 checksum and auto‑generated notes.

```bash
# 1. bump versionCode / versionName in app/build.gradle.kts (e.g. 2.1)
git commit -am "Release 2.1"
git tag v2.1            # tag must match versionName
git push && git push --tags
```

Signing comes from repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Every release must use the same key, otherwise it won't install as an update. You can also start the workflow by hand from the **Actions** tab. A manual run attaches the APK to the run as an artifact and doesn't create a release.

---

## 🧪 Test from a Mac or PC

[`tools/fdtest.py`](tools/fdtest.py) speaks the same protocol. It uses only the Python standard library.

```bash
python3 tools/fdtest.py hello <phone-ip>                         # handshake
python3 tools/fdtest.py send  <phone-ip> file1 file2 --streams 4 # send files
python3 tools/fdtest.py recv  --auto                             # your computer shows up as a device in the app
```

---

## 🗂 Project structure

```
app/src/main/java/com/fastdrop/app/
├── MainActivity.kt        # entry point, screen navigation
├── HomeScreen.kt          # home, radar, device list
├── SendScreen.kt          # pickers & target selection
├── Server.kt / Sender.kt  # TCP transfer protocol (parallel streams)
├── Discovery.kt           # multicast / broadcast / subnet discovery
├── P2pLink.kt             # Wi‑Fi Direct groups & service discovery
├── HotspotLink.kt         # local‑only hotspot fallback
├── WebShareScreen.kt      # browser share for devices without the app
├── TransferService.kt     # foreground service + Wi‑Fi/wake locks
├── Qr*.kt                 # QR generate & scan (ZXing)
└── ...                    # theme, animations, sheets, storage
tools/fdtest.py            # desktop protocol test client
```

## 🧰 Tech stack

- **Kotlin** with Android framework views. No AndroidX and no Jetpack, which keeps the APK tiny and old TVs supported.
- **[ZXing core](https://github.com/zxing/zxing)** for encoding and decoding QR codes.
- **minSdk 21** (Android 5.0) · **targetSdk 35** · **compileSdk 36**

---

## 📝 Notes

- **Permissions are asked only when needed:**
  - Nearby Wi‑Fi devices (or Location on Android 12 and older) for Wi‑Fi Direct.
  - Camera for scanning QR codes.
  - Photos/Videos/Music for the pickers.
  - "Install unknown apps" for installing received APKs.
- **Targets Android 15 (API 35).** Before targeting Android 17, add the new local‑network permission request.
- **Status:** same‑network discovery and transfers have been verified on a real device, with matching file hashes. Wi‑Fi Direct between two phones and D‑pad use on a physical TV still need real‑device testing. Bug reports are welcome!

## 🤝 Contributing

Issues and pull requests are welcome. If you test FastDrop on a device combination that isn't covered yet (two phones over Wi‑Fi Direct, a real Android TV), please open an issue with your results.

## 📄 License

Released under the [MIT License](LICENSE). You're free to use, modify and share it.

<div align="center">

Made with ⚡ by [@himanshudev28](https://github.com/himanshudev28)

</div>
