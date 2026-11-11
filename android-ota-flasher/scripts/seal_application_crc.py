#!/usr/bin/env python3
"""Seal a raw Core 2 Epson image (BL1, BL2 or Application) after editing it.

    python3 seal_application_crc.py image.bin [-o sealed.bin]

Sets +0x24 = base(+0x20) + len - 4 and the last 4 bytes to LE CRC32(image without
them), so CRC32(whole file) == 0x2144DF1C (558161692), the value every official
description lists. Leaves every other byte alone; refuses images whose header magic
or base does not match BL1 (0x2000), BL2 (0xA000) or Application (0x12000).

To build a flashable ZIP use rebuild_application_zip.py, which seals as well.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from rebuild_application_zip import seal
from validate_package import CRC32_RESIDUE, EPSON_BASE, EPSON_MAGIC, PackageError, check_epson, crc32, u32


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image", type=Path)
    ap.add_argument("-o", "--out", type=Path, help="default: overwrite the input")
    args = ap.parse_args()

    image = bytearray(args.image.read_bytes())
    if len(image) < 0x40 or u32(image, 0x1C) != EPSON_MAGIC or u32(image, 0x28) != EPSON_MAGIC:
        print(f"FAIL: {args.image.name}: no Epson header magic at +0x1C/+0x28")
        return 1
    base = u32(image, 0x20)
    type_ = next((t for t, b in EPSON_BASE.items() if b == base), None)
    if type_ is None:
        print(f"FAIL: {args.image.name}: header base 0x{base:X} is not BL1/BL2/Application")
        return 1

    changes = seal(image)
    try:
        check_epson(type_, bytes(image), args.image.name)
    except PackageError as e:
        print(f"FAIL: {e}")
        return 1
    assert crc32(bytes(image)) == CRC32_RESIDUE
    out = args.out or args.image
    out.write_bytes(image)
    print(f"{out}: {', '.join(changes) if changes else 'already sealed'}; size={len(image)} crc32={CRC32_RESIDUE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
