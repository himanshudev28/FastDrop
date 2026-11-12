#!/bin/bash
# Builds FastDrop.app into ./build/ .
#   scripts/build-app.sh            release build
#   scripts/build-app.sh --install  also copy to /Applications and relaunch
set -euo pipefail
cd "$(dirname "$0")/.."

APP=build/FastDrop.app
IDENTITY="${FASTDROP_SIGN_IDENTITY:-FastDrop Dev}"

echo "▸ Compiling (release)…"
swift build -c release --product FastDrop
BIN=$(swift build -c release --show-bin-path)

echo "▸ Assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/FastDrop" "$APP/Contents/MacOS/"
cp Resources/Info.plist "$APP/Contents/Info.plist"

if [[ ! -f build/AppIcon.icns || scripts/make-icon.swift -nt build/AppIcon.icns ]]; then
    rm -rf build/AppIcon.iconset
    echo "▸ Rendering icon"
    swift scripts/make-icon.swift build/AppIcon.iconset >/dev/null
    iconutil -c icns build/AppIcon.iconset -o build/AppIcon.icns
fi
cp build/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

# A stable identity keeps macOS's Local Network / Camera permissions across rebuilds; ad-hoc
# signatures change every build, so macOS forgets them. Create one with scripts/create-signing-identity.sh.
if security find-identity -p codesigning 2>/dev/null | grep -q "\"$IDENTITY\""; then
    echo "▸ Signing with \"$IDENTITY\""
    codesign --force --sign "$IDENTITY" --identifier com.fastdrop.mac "$APP"
else
    echo "▸ Signing ad-hoc (no \"$IDENTITY\" identity found)"
    echo "  Tip: run scripts/create-signing-identity.sh once so macOS remembers FastDrop's permissions."
    codesign --force --sign - --identifier com.fastdrop.mac "$APP"
fi

echo "✓ Built $PWD/$APP"

if [[ "${1:-}" == "--install" ]]; then
    # Quit politely so FastDrop says "bye" to nearby devices; force only if it doesn't exit.
    osascript -e 'tell application id "com.fastdrop.mac" to quit' 2>/dev/null || true
    for _ in 1 2 3 4 5; do pgrep -x FastDrop >/dev/null || break; sleep 1; done
    pkill -x FastDrop 2>/dev/null || true
    rm -rf /Applications/FastDrop.app
    cp -R "$APP" /Applications/
    open /Applications/FastDrop.app
    echo "✓ Installed to /Applications and launched"
fi
