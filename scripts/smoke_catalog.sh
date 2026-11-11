#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ZIP="${1:-$ROOT/data/firmware/Explorer-fw_1.13.44-A-58ee0fb.zip}"
PORT="${PORT:-8765}"
export PYTHONPATH="$ROOT"

python3 -m suunto_fw_inject server --zip "$ZIP" --public-host 127.0.0.1 --port "$PORT" &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 0.6
curl -fsS "http://127.0.0.1:$PORT/devices/Explorer/A" | python3 -m json.tool
curl -fsS -o /dev/null -w "zip %{http_code} %{size_download}\n" \
  "http://127.0.0.1:$PORT/firmwares/$(basename "$ZIP")"
echo OK
