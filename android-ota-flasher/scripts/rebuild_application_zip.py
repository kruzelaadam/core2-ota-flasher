#!/usr/bin/env python3
"""Put a modified Application binary into an official Core 2 OTA package.

    python3 rebuild_application_zip.py --base Explorer-fw_1.14.2-A-5174928.zip \\
        --app Application_modified.bin --out Explorer-fw_1.14.2-mod.zip \\
        [--offer-version 1.14.3] [--after-trailer 16] [--package-info "what changed"]

What it does, and nothing more:
  - seals the Application: +0x24 = base + len - 4, last 4 bytes = LE CRC32(rest)
    (use --no-seal to require that the binary is already sealed)
  - copies BL1, BL2 and Bluetooth byte-for-byte, keeps entry names, order,
    compression and timestamps of the base package
  - firmwareDescription.json: Application size and crc32 follow the file that is
    offered; version/package_version change only with --offer-version.
  - --after-trailer N appends N bytes after the CRC trailer. A sealed image always
    CRCs to 558161692, so an image of the same length as the one installed is
    invisible to the watch. The suffix changes the offered size and crc; the
    header still delimits the real image.
  - validates the result with validate_package.py rules plus a comparison with --base

The binary's own version words (+0x34 and any build-specific copies) are never touched.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

from validate_package import (
    CRC32_RESIDUE,
    DESCRIPTION,
    PackageError,
    check_epson,
    compare_with_base,
    crc32,
    load_package,
    u32,
)


def seal(image: bytearray) -> list[str]:
    changes = []
    base = u32(image, 0x20)
    end = base + len(image) - 4
    if u32(image, 0x24) != end:
        changes.append(f"+0x24 0x{u32(image, 0x24):X} -> 0x{end:X}")
        struct.pack_into("<I", image, 0x24, end)
    trailer = crc32(bytes(image[:-4]))
    if u32(image, len(image) - 4) != trailer:
        changes.append(f"trailer 0x{u32(image, len(image) - 4):08X} -> 0x{trailer:08X}")
        struct.pack_into("<I", image, len(image) - 4, trailer)
    return changes


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, type=Path, help="official package (unchanged download)")
    ap.add_argument("--app", required=True, type=Path, help="modified Application .bin")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument(
        "--offer-version",
        help="Application version written to the description only (X.Y.Z); the binary keeps its own",
    )
    ap.add_argument("--package-info", help="replaces meta.package_info")
    ap.add_argument("--no-seal", action="store_true", help="fail instead of sealing an unsealed binary")
    ap.add_argument(
        "--after-trailer",
        type=int,
        default=0,
        metavar="N",
        help="append N bytes (1..256) after the CRC trailer so the offered size and crc change",
    )
    args = ap.parse_args()

    if args.offer_version and not re.fullmatch(r"\d+\.\d+\.\d+", args.offer_version):
        print(f"FAIL: --offer-version must be X.Y.Z, got {args.offer_version}")
        return 1

    try:
        base = load_package(args.base)
    except PackageError as e:
        print(f"FAIL: base package: {e}")
        return 1
    base_app = base.part(2)

    image = bytearray(args.app.read_bytes())
    try:
        if args.no_seal:
            check_epson(2, bytes(image), args.app.name)
        else:
            if len(image) < 0x40:
                raise PackageError(f"{args.app.name}: too short")
            for c in seal(image):
                print(f"sealed {args.app.name}: {c}")
            check_epson(2, bytes(image), args.app.name)
    except PackageError as e:
        print(f"FAIL: {e}")
        return 1
    assert crc32(bytes(image)) == CRC32_RESIDUE
    if args.after_trailer:
        if not 1 <= args.after_trailer <= 256:
            print("FAIL: --after-trailer must be 1..256")
            return 1
        marker = bytearray((b"C2XF" + struct.pack("<I", len(image)) + bytes(args.after_trailer))[: args.after_trailer])
        sealed = bytes(image)
        # Official descriptions only use crc32 values that fit a positive int32.
        while crc32(sealed + bytes(marker)) & 0x80000000:
            marker[-1] = (marker[-1] + 1) & 0xFF
        image = bytearray(sealed + bytes(marker))
        print(f"after trailer: {args.after_trailer} B, offered crc32={crc32(bytes(image))}")

    with zipfile.ZipFile(args.base) as zin:
        infos = zin.infolist()
        original_desc = zin.read(DESCRIPTION)
        blobs = {i.filename: zin.read(i.filename) for i in infos}

    desc = json.loads(original_desc)
    meta = desc.setdefault("meta", {})
    app_entry = next(fw for fw in desc["firmwares"] if fw["type"] == 2)
    app_entry["size"] = len(image)
    app_entry["crc32"] = crc32(bytes(image))
    if args.offer_version:
        app_entry["version"] = args.offer_version
        if "package_version" in meta:
            meta["package_version"] = args.offer_version
    if args.package_info is not None:
        meta["package_info"] = args.package_info
    if desc == json.loads(original_desc):
        new_desc = original_desc
    else:
        new_desc = json.dumps(desc, indent=2).encode("utf-8")

    blobs[base_app.filename] = bytes(image)
    blobs[DESCRIPTION] = new_desc
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w") as zout:
        for info in infos:
            out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out_info.compress_type = info.compress_type
            out_info.external_attr = info.external_attr
            out_info.create_system = info.create_system
            zout.writestr(out_info, blobs[info.filename])

    try:
        pkg = load_package(args.out)
    except PackageError as e:
        print(f"FAIL: rebuilt package does not validate: {e}")
        return 1
    errors = compare_with_base(pkg, base)
    for n in pkg.notes:
        print(f"note: {n}")
    if errors:
        for e in errors:
            print(f"FAIL: {e}")
        return 1
    app = pkg.part(2)
    print(
        f"\nwrote {args.out}\n  Application {app.filename} size={app.size} crc32={app.crc32} "
        f"offered={app.version} description {'unchanged' if new_desc == original_desc else 'updated'}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
