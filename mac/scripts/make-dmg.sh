#!/bin/bash
# Packs FastDrop.app into a drag-to-install disk image (FastDrop.app + an Applications shortcut).
#   scripts/make-dmg.sh [path/to/FastDrop.app] [out.dmg]
# Defaults: build/FastDrop.app -> build/FastDrop-mac.dmg. Uses only hdiutil (ships with macOS).
set -euo pipefail
cd "$(dirname "$0")/.."

APP="${1:-build/FastDrop.app}"
OUT="${2:-build/FastDrop-mac.dmg}"
[[ -d "$APP" ]] || { echo "No app at $APP (run scripts/build-app.sh first)" >&2; exit 1; }

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
ditto "$APP" "$STAGE/FastDrop.app"          # ditto keeps the code signature and extended attributes intact
ln -s /Applications "$STAGE/Applications"

rm -f "$OUT"
hdiutil create -volname "FastDrop" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$OUT" >/dev/null
hdiutil verify "$OUT" >/dev/null
echo "✓ Built $OUT ($(du -h "$OUT" | cut -f1))"
