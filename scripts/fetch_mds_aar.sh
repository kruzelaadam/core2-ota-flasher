#!/usr/bin/env bash
# Download Movesense MDS AAR (includes libmds.so) for the Core2 OTA flasher.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android-ota-flasher/app/libs"
VER="${1:-3.33.7}"
URL="https://bitbucket.org/movesense/movesense-mobile-lib/raw/master/android/Movesense/mdslib-${VER}-release.aar"
mkdir -p "$OUT"
DEST="$OUT/mdslib-${VER}-release.aar"
echo "Fetching $URL"
curl -fL --retry 3 -o "$DEST" "$URL"
ls -lh "$DEST"
echo "OK — wire as implementation(files(\"libs/mdslib-${VER}-release.aar\"))"
