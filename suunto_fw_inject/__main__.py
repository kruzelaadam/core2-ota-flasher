"""python -m suunto_fw_inject <server|patch> ..."""

from __future__ import annotations

import sys


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(
            "Usage:\n"
            "  python -m suunto_fw_inject server --zip FILE [--port 8765] [--public-host IP]\n"
            "  python -m suunto_fw_inject patch  --apk FILE --host IP [--port 8765]\n"
        )
        return 0
    cmd = sys.argv[1]
    rest = sys.argv[2:]
    if cmd == "server":
        from .server import main as server_main

        return server_main(rest)
    if cmd == "patch":
        from .patch_apk import main as patch_main

        return patch_main(rest)
    print(f"unknown command: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
