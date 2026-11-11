#!/usr/bin/env python3
"""Local fake Suunto device-firmware catalog that serves a user-supplied ZIP.

The patched Suunto app (deviceApiUrl redirected here) calls:
  GET /devices/{variant}/{hw}?appkey=...&currentVersion=...&serialnumber=...
and then downloads LatestFirmwareURI (absolute URL we control).

Also accepts the /firmware/devices/... path used by some clients/tools.
"""

from __future__ import annotations

import argparse
import json
import logging
import mimetypes
import re
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

log = logging.getLogger("suunto-fw-inject")

# DEX patch pads the Retrofit base URL to 38 chars with /x…/ so requests look like
#   /xxxxxxxxxxxxxxx/devices/Explorer/A
CATALOG_PATH_RE = re.compile(
    r"^(?:/x+)*/(?:firmware/)?devices/(?P<variant>[^/]+)/(?P<hw>[^/]+)/?$",
    re.IGNORECASE,
)


class InjectState:
    def __init__(
        self,
        zip_path: Path,
        public_base: str,
        bump_version: str,
        force_update: bool,
        device_name: str,
        hw_version: str,
    ) -> None:
        self.zip_path = zip_path.resolve()
        self.public_base = public_base.rstrip("/") + "/"
        self.bump_version = bump_version
        self.force_update = force_update
        self.device_name = device_name
        self.hw_version = hw_version
        self.zip_name = self.zip_path.name
        self.lock = threading.Lock()
        self.hits: list[dict[str, Any]] = []

    def zip_url(self) -> str:
        return f"{self.public_base}firmwares/{self.zip_name}"

    def catalog(self, variant: str, hw: str) -> dict[str, Any]:
        # Match production JSON field names (PascalCase) from RemoteWatchFirmwareInfo.
        return {
            "DeviceName": variant.lower() if variant else self.device_name,
            "Version": hw.lower() if hw else self.hw_version,
            "LatestFirmwareVersion": self.bump_version,
            "LatestFirmwareURI": self.zip_url(),
            "FirmwareUploadDate": datetime.now(timezone.utc).strftime(
                "%Y-%m-%dT%H:%M:%SZZZ"
            ),
            "ReleaseType": "production",
            "ForceUpdate": self.force_update,
            "FirmwareId": 9000000000000000001,
        }

    def record(self, kind: str, path: str, peer: str) -> None:
        with self.lock:
            self.hits.append(
                {
                    "ts": datetime.now(timezone.utc).isoformat(),
                    "kind": kind,
                    "path": path,
                    "peer": peer,
                }
            )
            self.hits = self.hits[-50:]


def _html_status(state: InjectState) -> bytes:
    with state.lock:
        hits = list(reversed(state.hits[-15:]))
    rows = "".join(
        f"<tr><td>{h['ts']}</td><td>{h['kind']}</td><td>{h['path']}</td>"
        f"<td>{h['peer']}</td></tr>"
        for h in hits
    ) or "<tr><td colspan=4>no requests yet</td></tr>"
    body = f"""<!doctype html>
<html><head><meta charset=utf-8><title>Suunto FW inject</title>
<style>
body{{font-family:ui-sans-serif,system-ui;margin:2rem;background:#0f1419;color:#e7ecf1}}
code,td{{font-family:ui-monospace,monospace}}
a{{color:#7dd3fc}} table{{border-collapse:collapse;width:100%}}
td,th{{border-bottom:1px solid #243041;padding:.4rem .6rem;text-align:left}}
.card{{background:#172033;padding:1.2rem 1.4rem;border-radius:12px;max-width:52rem}}
</style></head><body>
<div class=card>
<h1>Suunto firmware inject</h1>
<p>ZIP: <code>{state.zip_path}</code> ({state.zip_path.stat().st_size} bytes)</p>
<p>Catalog version offered: <code>{state.bump_version}</code>
   ForceUpdate={state.force_update}</p>
<p>ZIP URL: <a href="{state.zip_url()}">{state.zip_url()}</a></p>
<p>Probe catalog:
 <a href="/devices/Explorer/A">/devices/Explorer/A</a>
 · <a href="/firmware/devices/Explorer/A">/firmware/devices/Explorer/A</a></p>
<h2>Recent hits</h2>
<table><thead><tr><th>time</th><th>kind</th><th>path</th><th>peer</th></tr></thead>
<tbody>{rows}</tbody></table>
</div></body></html>"""
    return body.encode("utf-8")


def make_handler(state: InjectState):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt: str, *args: Any) -> None:
            log.info("%s - " + fmt, self.address_string(), *args)

        def _peer(self) -> str:
            return self.client_address[0]

        def _send(
            self,
            code: int,
            body: bytes,
            content_type: str,
            extra: dict[str, str] | None = None,
        ) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-store")
            if extra:
                for k, v in extra.items():
                    self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, code: int, payload: Any) -> None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self._send(code, body, "application/json; charset=utf-8")

        def do_OPTIONS(self) -> None:  # noqa: N802
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "*")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_HEAD(self) -> None:  # noqa: N802
            self.do_GET()

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            path = parsed.path or "/"
            qs = parse_qs(parsed.query)

            if path in ("/", "/status", "/index.html"):
                state.record("status", path, self._peer())
                self._send(200, _html_status(state), "text/html; charset=utf-8")
                return

            m = CATALOG_PATH_RE.match(path)
            if m:
                variant = m.group("variant")
                hw = m.group("hw")
                state.record("catalog", path, self._peer())
                log.info(
                    "catalog variant=%s hw=%s currentVersion=%s serial=%s",
                    variant,
                    hw,
                    qs.get("currentVersion", [""])[0],
                    qs.get("serialnumber", qs.get("serialNumber", [""]))[0],
                )
                self._json(200, state.catalog(variant, hw))
                return

            # ZIP download — accept /firmwares/<name> and padded path prefixes.
            if path.endswith(".zip") or "/firmwares/" in path:
                state.record("zip", path, self._peer())
                data = state.zip_path.read_bytes()
                ctype = mimetypes.guess_type(state.zip_name)[0] or "application/zip"
                self._send(
                    200,
                    data,
                    ctype,
                    extra={
                        "Content-Disposition": f'attachment; filename="{state.zip_name}"'
                    },
                )
                return

            self._json(404, {"error": "not found", "path": path})

    return Handler


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Fake Suunto firmware catalog + ZIP server")
    p.add_argument("--zip", required=True, type=Path, help="Explorer firmware ZIP to serve")
    p.add_argument("--host", default="0.0.0.0", help="Bind address")
    p.add_argument("--port", type=int, default=8765, help="Bind port")
    p.add_argument(
        "--public-host",
        default=None,
        help="Host/IP the phone uses to reach this server (default: --host if not 0.0.0.0)",
    )
    p.add_argument(
        "--version",
        default="1.13.99",
        help="LatestFirmwareVersion advertised (must be newer than watch)",
    )
    p.add_argument(
        "--force-update",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Set ForceUpdate in catalog JSON (default: true)",
    )
    p.add_argument("--device-name", default="explorer")
    p.add_argument("--hw", default="a")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    if not args.zip.is_file():
        p.error(f"ZIP not found: {args.zip}")

    public_host = args.public_host or (
        "127.0.0.1" if args.host in ("0.0.0.0", "::") else args.host
    )
    public_base = f"http://{public_host}:{args.port}/"

    state = InjectState(
        zip_path=args.zip,
        public_base=public_base,
        bump_version=args.version,
        force_update=args.force_update,
        device_name=args.device_name,
        hw_version=args.hw,
    )

    httpd = ThreadingHTTPServer((args.host, args.port), make_handler(state))
    log.info("serving ZIP %s", state.zip_path)
    log.info("listen %s:%s", args.host, args.port)
    log.info("catalog JSON version=%s ForceUpdate=%s", args.version, args.force_update)
    log.info("ZIP URL: %s", state.zip_url())
    log.info(
        "patch with: python -m suunto_fw_inject patch --apk Suunto.apk --host %s --port %s",
        public_host,
        args.port,
    )
    log.info("status UI: %s", public_base)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")
        httpd.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
