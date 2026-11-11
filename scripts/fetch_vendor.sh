#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/vendor"
cd "$ROOT/vendor"

download() {
  local url=$1 out=$2
  if [[ -f "$out" ]]; then
    echo "have $out"
    return
  fi
  echo "fetch $out"
  curl -fsSL -L -o "$out" "$url"
}

download \
  "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar" \
  APKEditor.jar

# Optional / legacy
download \
  "https://github.com/iBotPeaches/Apktool/releases/download/v2.12.0/apktool_2.12.0.jar" \
  apktool.jar || true

ls -lh
echo "vendor OK"
