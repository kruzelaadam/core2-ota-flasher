#!/usr/bin/env python3
"""Validate a Core 2 (Explorer A) OTA ZIP with the same rules as the flasher app.

    python3 validate_package.py package.zip [--base official.zip]

Checks (any failure exits 1, the flasher would refuse the package too):
  - ZIP entries are plain root-level names: no folders, "..", backslashes or duplicates
  - firmwareDescription.json lists each type 0,1,2,3 exactly once, unique filenames
  - per component: ZIP CRC ok, description size == file size,
    description crc32 (as unsigned) == CRC32(file)
  - BL1/BL2/Application (Epson): magic 0xDA11C10C at +0x1C and +0x28,
    base +0x20 = 0x2000 / 0xA000 / 0x12000, end +0x24 == base + len - 4,
    last 4 bytes (LE) == CRC32(file without them)
  - Bluetooth: nested ZIP with manifest.json and its MCUboot image (magic 0x96F3B83D)

--base compares against the official package the modification started from and
reports which components changed. BL1, BL2 and Bluetooth must stay byte-identical;
the Application header version word (+0x34) is expected to stay unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import sys
import zipfile
import zlib
from dataclasses import dataclass, field
from pathlib import Path

DESCRIPTION = "firmwareDescription.json"
CRC32_RESIDUE = 0x2144DF1C  # CRC32 of any buffer ending in its own LE CRC32 (= 558161692)
EPSON_MAGIC = 0xDA11C10C
MCUBOOT_MAGIC = 0x96F3B83D
COMPONENTS = {0: "BL1", 1: "BL2", 2: "Application", 3: "Bluetooth"}
EPSON_BASE = {0: 0x2000, 1: 0xA000, 2: 0x12000}


class PackageError(Exception):
    pass


def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def signed32(v: int) -> int:
    return v - (1 << 32) if v & 0x80000000 else v


def crc_text(v: int) -> str:
    return f"0x{v:08X} / {v} / {signed32(v)}"


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise PackageError(msg)


def u32(data: bytes, off: int) -> int:
    return struct.unpack_from("<I", data, off)[0]


def version_word_text(word: int) -> str:
    return f"{word >> 24}.{(word >> 16) & 0xFF}.{word & 0xFFFF}"


@dataclass
class EpsonHeader:
    base: int
    end: int
    trailer: int
    version_word: int
    suffix_bytes: int = 0

    @property
    def binary_version(self) -> str:
        return version_word_text(self.version_word)


def check_epson(type_: int, data: bytes, name: str) -> EpsonHeader:
    expected_base = EPSON_BASE[type_]
    require(len(data) >= 0x40, f"{name}: {len(data)} B is shorter than the Epson header")
    m1, m2 = u32(data, 0x1C), u32(data, 0x28)
    require(
        m1 == EPSON_MAGIC and m2 == EPSON_MAGIC,
        f"{name}: header magic 0x{m1:08X}/0x{m2:08X} != 0x{EPSON_MAGIC:08X}",
    )
    base = u32(data, 0x20)
    require(
        base == expected_base,
        f"{name}: header base 0x{base:X} != 0x{expected_base:X} for {COMPONENTS[type_]}",
    )
    end = u32(data, 0x24)
    expected_end = base + len(data) - 4
    if end == expected_end:
        sealed_len = len(data)
    else:
        sealed_len = end - base + 4
        if not (0x40 <= sealed_len < len(data) and crc32(data[:sealed_len]) == CRC32_RESIDUE):
            raise PackageError(
                f"{name}: header end 0x{end:X} != base+len-4 = 0x{expected_end:X} "
                "(length changed without updating +0x24)"
            )
    trailer = u32(data, sealed_len - 4)
    body = crc32(data[: sealed_len - 4])
    require(
        trailer == body,
        f"{name}: trailer 0x{trailer:08X} != CRC32(sealed image without last 4 B) 0x{body:08X}",
    )
    return EpsonHeader(base, end, trailer, u32(data, 0x34), len(data) - sealed_len)


def check_bluetooth(data: bytes, name: str) -> str:
    try:
        inner = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:
        raise PackageError(f"{name}: not a ZIP ({e})")
    names = [i.filename for i in inner.infolist() if not i.is_dir()]
    require(len(names) == len(set(names)), f"{name}: duplicate inner entries {names}")
    require("manifest.json" in names, f"{name}: inner manifest.json missing")
    files = json.loads(inner.read("manifest.json")).get("files") or []
    require(len(files) >= 1, f"{name}: manifest lists no files")
    image_name = files[0]["file"]
    require(image_name in names, f"{name}: manifest file {image_name} missing")
    image = inner.read(image_name)
    require(len(image) >= 32, f"{name}: {image_name} too short for an MCUboot header")
    magic = u32(image, 0)
    require(
        magic == MCUBOOT_MAGIC,
        f"{name}: {image_name} magic 0x{magic:08X} != MCUboot 0x{MCUBOOT_MAGIC:08X}",
    )
    hdr = struct.unpack_from("<H", image, 8)[0]
    return (
        f"inner={image_name} {len(image)} B image_index={files[0].get('image_index', '0')} "
        f"mcuboot_hdr=0x{hdr:X}"
    )


def check_names(names: list[str]) -> None:
    seen: set[str] = set()
    for n in names:
        require(bool(n.strip()), "ZIP contains an entry with an empty name")
        require("/" not in n and "\\" not in n, f'ZIP entry "{n}" is not at the root (folders are not allowed)')
        require(n not in (".", ".."), f'ZIP entry "{n}" is a path component')
        require(n not in seen, f'ZIP contains "{n}" twice')
        seen.add(n)


@dataclass
class Part:
    type: int
    version: str
    filename: str
    size: int
    crc32: int
    data: bytes
    detail: str
    header: EpsonHeader | None = None

    @property
    def label(self) -> str:
        return f"{COMPONENTS[self.type]} {self.version}"


@dataclass
class Package:
    path: Path
    description: dict
    parts: list[Part]
    notes: list[str] = field(default_factory=list)

    def part(self, type_: int) -> Part:
        return next(p for p in self.parts if p.type == type_)


def load_package(path: Path) -> Package:
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise PackageError(f"{path.name}: not a ZIP ({e})")
    with zf:
        infos = zf.infolist()
        check_names([i.filename for i in infos])
        by_name = {i.filename: i for i in infos}
        notes: list[str] = []
        for i in infos:
            if i.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                notes.append(f"{i.filename}: compression method {i.compress_type} (use STORED or DEFLATE)")
        require(DESCRIPTION in by_name, f"{DESCRIPTION} missing at the ZIP root")
        try:
            desc = json.loads(zf.read(DESCRIPTION).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            raise PackageError(f"{DESCRIPTION}: not UTF-8 JSON ({e})")
        firmwares = desc.get("firmwares") or []
        types = [fw.get("type") for fw in firmwares]
        require(sorted(types) == [0, 1, 2, 3], f"{DESCRIPTION} must list each type 0,1,2,3 exactly once (found {types})")
        file_count = (desc.get("meta") or {}).get("file_count")
        if isinstance(file_count, int) and file_count != len(firmwares):
            notes.append(f"meta.file_count={file_count} but {len(firmwares)} firmwares are listed")

        described: set[str] = set()
        parts: list[Part] = []
        for fw in firmwares:
            type_ = fw["type"]
            name = fw["filename"]
            require(name not in described, f"{DESCRIPTION} uses {name} for two components")
            described.add(name)
            require(name in by_name, f"{COMPONENTS[type_]}: {name} listed but not in the ZIP")
            try:
                data = zf.read(name)
            except zipfile.BadZipFile as e:
                raise PackageError(f"{name}: corrupt ZIP entry ({e})")
            actual = crc32(data)
            require(fw["size"] == len(data), f"{name}: description size {fw['size']} != file {len(data)} B")
            require(
                (fw["crc32"] & 0xFFFFFFFF) == actual,
                f"{name}: description crc32 {fw['crc32']} != CRC32(file) {actual}",
            )
            version = str(fw.get("version", ""))
            header = None
            if type_ == 3:
                detail = check_bluetooth(data, name)
            else:
                header = check_epson(type_, data, name)
                if header.binary_version != version:
                    notes.append(
                        f"{COMPONENTS[type_]}: description version {version}, header +0x34 says {header.binary_version}"
                    )
                detail = (
                    f"base=0x{header.base:X} end=0x{header.end:X} trailer=0x{header.trailer:08X} "
                    f"+0x34={header.binary_version}"
                    + (f" suffix={header.suffix_bytes}B" if header.suffix_bytes else "")
                )
                if header.suffix_bytes:
                    notes.append(
                        f"{COMPONENTS[type_]}: {header.suffix_bytes} B after the CRC trailer "
                        "(offered size and crc differ from the sealed image)"
                    )
            parts.append(Part(type_, version, name, len(data), fw["crc32"], data, detail, header))

        extra = sorted(set(by_name) - described - {DESCRIPTION})
        if extra:
            notes.append(f"ZIP has entries not in {DESCRIPTION}: {extra}")
        return Package(path, desc, parts, notes)


def changed_ranges(a: bytes, b: bytes, limit: int = 12) -> list[str]:
    out: list[str] = []
    n = min(len(a), len(b))
    i = 0
    while i < n and len(out) < limit:
        if a[i] != b[i]:
            j = i
            while j < n and a[j] != b[j]:
                j += 1
            out.append(f"0x{i:X}..0x{j - 1:X} ({j - i} B)")
            i = j
        else:
            i += 1
    return out


def compare_with_base(pkg: Package, base: Package) -> list[str]:
    """Returns hard errors; prints the comparison."""
    errors: list[str] = []
    print(f"\nCompared with base {base.path.name}:")
    for p in pkg.parts:
        b = base.part(p.type)
        same = p.data == b.data
        print(f"  {COMPONENTS[p.type]:<11} {'identical' if same else 'CHANGED'}  {p.filename}  (base {b.filename})")
        if p.type != 2 and not same:
            errors.append(f"{COMPONENTS[p.type]} differs from the base package; keep BL1/BL2/Bluetooth byte-identical")
        if p.type != 2 and p.version != b.version:
            errors.append(f"{COMPONENTS[p.type]} description version {p.version} != base {b.version}")
    app, base_app = pkg.part(2), base.part(2)
    if app.data != base_app.data:
        ranges = changed_ranges(app.data, base_app.data)
        print(f"    length {len(app.data)} (base {len(base_app.data)}), first differing ranges: {', '.join(ranges) or '-'}")
    if app.header and base_app.header and app.header.version_word != base_app.header.version_word:
        print(
            f"  WARNING: Application +0x34 version word {app.header.binary_version} != base "
            f"{base_app.header.binary_version}. The watch will report the new version; official "
            "updates are only offered when they are newer than what the watch reports."
        )
    print(
        f"  Offered Application version (description): {app.version}; "
        f"binary reports {app.header.binary_version if app.header else '?'}"
    )
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("package", type=Path)
    ap.add_argument("--base", type=Path, help="official package the modification started from")
    args = ap.parse_args()

    try:
        pkg = load_package(args.package)
        base = load_package(args.base) if args.base else None
    except PackageError as e:
        print(f"FAIL: {e}")
        return 1

    raw = args.package.read_bytes()
    print(f"{args.package.name}: OK  package_version={(pkg.description.get('meta') or {}).get('package_version')}")
    print(f"  zip sha256 {sha256(raw)}")
    for p in pkg.parts:
        print(f"  type={p.type} {p.label:<20} {p.filename}  size={p.size}  crc={crc_text(crc32(p.data))}")
        print(f"      {p.detail}")
        print(f"      sha256 {sha256(p.data)}")
    for n in pkg.notes:
        print(f"  note: {n}")

    if base:
        errors = compare_with_base(pkg, base)
        if errors:
            for e in errors:
                print(f"FAIL: {e}")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
