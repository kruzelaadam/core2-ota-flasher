#!/usr/bin/env python3
"""Patch a local Suunto APK so device firmware API points at the inject server.

Uses APKEditor (vendor/APKEditor.jar) because apktool cannot rebuild this app's
Glance resources. Changes:

  1) BaseUrlConfiguration.deviceApiUrl → http://HOST:PORT/
  2) ssl_proxy_config.xml → base-config cleartextTrafficPermitted=true
  3) Force same-version OTA (smali): never treat firmware as current;
     always emit Select/Install Tag=true; map Suggest.CURRENT → RECOMMENDED
  4) Debug-keystore re-sign (Play Protect will warn)

You supply your own Suunto.apk; this repo does not redistribute it.
"""

from __future__ import annotations

import argparse
import logging
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

log = logging.getLogger("suunto-fw-patch")

STOCK_DEVICE_API_URL = "https://devices.suunto-operations.com/"
STOCK_DEVICE_API_URL_CN = "https://devices.suunto-operations.cn/"

# Relative to decode tree (APKEditor layout).
_FORCE_SMALI = {
    "is_current": (
        "smali/classes3/com/suunto/connectivity/repository/stateMachines/"
        "firmwareUpdateStateMachine/CheckNeedForFirmwareUpdateState$Companion.smali"
    ),
    "watch_fw_info": (
        "smali/classes3/com/suunto/connectivity/firmware/WatchFirmwareInfo.smali"
    ),
    "base_fw_info": (
        "smali/classes3/com/stt/android/domain/firmware/BaseWatchFirmwareInfo.smali"
    ),
    "check_need_map": (
        "smali/classes3/com/suunto/connectivity/repository/stateMachines/"
        "firmwareUpdateStateMachine/CheckNeedForFirmwareUpdateState$WhenMappings.smali"
    ),
    "companion_map": (
        "smali/classes3/com/suunto/connectivity/repository/stateMachines/"
        "firmwareUpdateStateMachine/"
        "CheckNeedForFirmwareUpdateState$Companion$WhenMappings.smali"
    ),
    "prepare_map": (
        "smali/classes3/com/suunto/connectivity/repository/stateMachines/"
        "firmwareUpdateStateMachine/"
        "PrepareForForcedFirmwareTransferState$WhenMappings.smali"
    ),
}


def vendor_jar(name: str) -> Path:
    root = Path(__file__).resolve().parents[1]
    p = root / "vendor" / name
    if not p.is_file():
        raise FileNotFoundError(
            f"missing {p} — run: bash scripts/fetch_vendor.sh"
        )
    return p


def run(cmd: list[str]) -> None:
    log.info("$ %s", " ".join(cmd))
    subprocess.run(cmd, check=True)


def decompile(apk: Path, out_dir: Path, apkeditor: Path) -> None:
    if out_dir.exists():
        shutil.rmtree(out_dir)
    run(
        [
            "java",
            "-jar",
            str(apkeditor),
            "d",
            "-i",
            str(apk),
            "-o",
            str(out_dir),
        ]
    )


def patch_device_api_url(work: Path, base_url: str, also_cn: bool) -> int:
    if not base_url.endswith("/"):
        base_url += "/"
    hits = 0
    for smali in (work / "smali").rglob("*.smali"):
        text = smali.read_text(encoding="utf-8", errors="ignore")
        orig = text
        if STOCK_DEVICE_API_URL in text:
            text = text.replace(STOCK_DEVICE_API_URL, base_url)
        if also_cn and STOCK_DEVICE_API_URL_CN in text:
            text = text.replace(STOCK_DEVICE_API_URL_CN, base_url)
        if text != orig:
            smali.write_text(text, encoding="utf-8")
            hits += 1
            log.info("patched %s", smali.relative_to(work))
    if hits == 0:
        raise RuntimeError(
            "stock devices.suunto-operations.com URL not found — wrong APK?"
        )
    return hits


def patch_network_security(work: Path) -> None:
    nsc = work / "resources" / "package_1" / "res" / "xml" / "ssl_proxy_config.xml"
    if not nsc.is_file():
        # Fall back: any network-security-config under resources
        candidates = list(work.glob("resources/**/ssl_proxy_config.xml"))
        if not candidates:
            raise FileNotFoundError("ssl_proxy_config.xml not found in decode tree")
        nsc = candidates[0]

    text = nsc.read_text(encoding="utf-8")
    if "<base-config" not in text:
        text = text.replace(
            "<network-security-config>",
            '<network-security-config>\n  <base-config cleartextTrafficPermitted="true" />',
            1,
        )
        nsc.write_text(text, encoding="utf-8")
        log.info("enabled cleartext base-config in %s", nsc)
    else:
        # Ensure cleartext is actually permitted even if base-config exists.
        if 'cleartextTrafficPermitted="true"' not in text:
            text2 = re.sub(
                r"<base-config([^>]*)>",
                r'<base-config\1 cleartextTrafficPermitted="true">',
                text,
                count=1,
            )
            if text2 == text:
                text2 = text.replace(
                    "<network-security-config>",
                    '<network-security-config>\n  <base-config cleartextTrafficPermitted="true" />',
                    1,
                )
            nsc.write_text(text2, encoding="utf-8")
            log.info("forced cleartextTrafficPermitted=true in %s", nsc)
        else:
            log.info("cleartext already permitted in %s", nsc)


def _replace_method_body(smali: str, method_sig: str, new_body: str) -> str:
    """Replace everything between .method ... and .end method for method_sig."""
    pattern = re.compile(
        rf"(\.method[^\n]*{re.escape(method_sig)}[^\n]*\n)(.*?)(\.end method)",
        re.DOTALL,
    )
    m = pattern.search(smali)
    if not m:
        raise RuntimeError(f"method not found: {method_sig}")
    return smali[: m.start()] + m.group(1) + new_body + m.group(3) + smali[m.end() :]


def _remap_current_suggest(smali: str, recommended_const: str) -> str:
    """In a WhenMappings clinit, set Suggest.CURRENT mapping to recommended_const.

    recommended_const is e.g. '0x3' (same value RECOMMENDED already uses).
    """
    marker = (
        "sget-object v1, Lcom/suunto/connectivity/watch/firmwareTransfer/"
        "Suggest;->CURRENT:"
    )
    idx = smali.find(marker)
    if idx < 0:
        raise RuntimeError("Suggest.CURRENT mapping block not found")
    # Replace the first const/4 v2, 0xN after CURRENT sget within ~20 lines.
    tail = smali[idx:]
    m = re.search(r"const/4 v2, 0x[0-9a-fA-F]+", tail)
    if not m:
        raise RuntimeError("const/4 for CURRENT mapping not found")
    new_tail = tail[: m.start()] + f"const/4 v2, {recommended_const}" + tail[m.end() :]
    return smali[:idx] + new_tail


def patch_force_same_version(work: Path) -> None:
    """Make the app proceed with OTA even when Suggest is CURRENT / versions match."""

    # 1) isCurrentFirmware → always false
    p = work / _FORCE_SMALI["is_current"]
    text = p.read_text(encoding="utf-8")
    text = _replace_method_body(
        text,
        "isCurrentFirmware(Lcom/suunto/connectivity/sdsmanager/model/MdsDeviceInfo;"
        "Lcom/suunto/connectivity/firmware/WatchFirmwareInfo;"
        "Lcom/suunto/connectivity/watch/firmwareTransfer/FirmwareInfoResult;)Z",
        "    .locals 0\n\n"
        "    const/4 p0, 0x0\n\n"
        "    return p0\n",
    )
    p.write_text(text, encoding="utf-8")
    log.info("patched isCurrentFirmware → always false")

    # 2) WatchFirmwareInfo.getForceUpdate → always true
    p = work / _FORCE_SMALI["watch_fw_info"]
    text = p.read_text(encoding="utf-8")
    text = _replace_method_body(
        text,
        "getForceUpdate()Z",
        "    .locals 1\n\n"
        "    const/4 v0, 0x1\n\n"
        "    return v0\n",
    )
    # 3) forceUpdateToContract always Tag=true
    text = text.replace(
        "    iget-boolean p0, p0, Lcom/suunto/connectivity/firmware/"
        "WatchFirmwareInfo;->forceUpdate:Z\n\n"
        "    .line 9\n"
        "    invoke-virtual {v0, v1, p0}, Lorg/json/JSONObject;->"
        "put(Ljava/lang/String;Z)Lorg/json/JSONObject;",
        "    const/4 p0, 0x1\n\n"
        "    .line 9\n"
        "    invoke-virtual {v0, v1, p0}, Lorg/json/JSONObject;->"
        "put(Ljava/lang/String;Z)Lorg/json/JSONObject;",
        1,
    )
    p.write_text(text, encoding="utf-8")
    log.info("patched WatchFirmwareInfo forceUpdate/Tag → always true")

    # 4) BaseWatchFirmwareInfo.a() Tag=true (UI-layer contract)
    p = work / _FORCE_SMALI["base_fw_info"]
    text = p.read_text(encoding="utf-8")
    text = text.replace(
        "    iget-boolean p0, p0, Lcom/stt/android/domain/firmware/"
        "BaseWatchFirmwareInfo;->g:Z\n\n"
        "    .line 9\n"
        "    invoke-virtual {v0, v1, p0}, Lorg/json/JSONObject;->"
        "put(Ljava/lang/String;Z)Lorg/json/JSONObject;",
        "    const/4 p0, 0x1\n\n"
        "    .line 9\n"
        "    invoke-virtual {v0, v1, p0}, Lorg/json/JSONObject;->"
        "put(Ljava/lang/String;Z)Lorg/json/JSONObject;",
        1,
    )
    p.write_text(text, encoding="utf-8")
    log.info("patched BaseWatchFirmwareInfo Tag → always true")

    # 5) Map Suggest.CURRENT onto the RECOMMENDED branch in WhenMappings
    # CheckNeed handleSuggest: RECOMMENDED=0x3 → transfer
    p = work / _FORCE_SMALI["check_need_map"]
    p.write_text(_remap_current_suggest(p.read_text(encoding="utf-8"), "0x3"), encoding="utf-8")
    log.info("mapped CURRENT→RECOMMENDED in CheckNeed WhenMappings")

    # Companion checkNewFirmware: RECOMMENDED=0x2 → updateAvailable
    p = work / _FORCE_SMALI["companion_map"]
    p.write_text(_remap_current_suggest(p.read_text(encoding="utf-8"), "0x2"), encoding="utf-8")
    log.info("mapped CURRENT→RECOMMENDED in Companion WhenMappings")

    # PrepareForForced: RECOMMENDED=0x1 → download/copy path
    p = work / _FORCE_SMALI["prepare_map"]
    p.write_text(_remap_current_suggest(p.read_text(encoding="utf-8"), "0x1"), encoding="utf-8")
    log.info("mapped CURRENT→RECOMMENDED in PrepareForForced WhenMappings")


def build(work: Path, out_apk: Path, apkeditor: Path) -> None:
    if out_apk.exists():
        out_apk.unlink()
    run(
        [
            "java",
            "-jar",
            str(apkeditor),
            "b",
            "-i",
            str(work),
            "-o",
            str(out_apk),
        ]
    )


def ensure_keystore(path: Path, alias: str, password: str) -> None:
    if path.is_file():
        return
    keytool = shutil.which("keytool")
    if not keytool:
        raise RuntimeError("keytool not found (need JDK)")
    path.parent.mkdir(parents=True, exist_ok=True)
    run(
        [
            keytool,
            "-genkeypair",
            "-keystore",
            str(path),
            "-alias",
            alias,
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-storepass",
            password,
            "-keypass",
            password,
            "-dname",
            "CN=SuuntoFWInject, OU=Local, O=Local, L=Local, S=Local, C=CZ",
        ]
    )


def sign_apk(apk: Path, keystore: Path, alias: str, password: str) -> Path:
    signed = apk.with_name(apk.stem.replace("-unsigned", "") + "-signed.apk")
    if signed == apk:
        signed = apk.with_name(apk.stem + "-signed.apk")
    shutil.copy2(apk, signed)

    apksigner = shutil.which("apksigner")
    if apksigner:
        run(
            [
                apksigner,
                "sign",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                alias,
                "--ks-pass",
                f"pass:{password}",
                "--key-pass",
                f"pass:{password}",
                str(signed),
            ]
        )
        return signed

    jarsigner = shutil.which("jarsigner")
    if not jarsigner:
        raise RuntimeError("neither apksigner nor jarsigner found")
    run(
        [
            jarsigner,
            "-sigalg",
            "SHA256withRSA",
            "-digestalg",
            "SHA-256",
            "-keystore",
            str(keystore),
            "-storepass",
            password,
            "-keypass",
            password,
            str(signed),
            alias,
        ]
    )
    return signed


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Patch Suunto APK to use local FW inject server"
    )
    p.add_argument("--apk", required=True, type=Path, help="Original Suunto.apk")
    p.add_argument(
        "--host",
        required=True,
        help="Inject server host/IP reachable from the phone",
    )
    p.add_argument("--port", type=int, default=8765)
    p.add_argument(
        "--out",
        type=Path,
        default=Path("dist/Suunto-fw-inject-signed.apk"),
    )
    p.add_argument(
        "--also-cn",
        action="store_true",
        help="Also rewrite devices.suunto-operations.cn",
    )
    p.add_argument("--workdir", type=Path, default=None, help="Keep decode tree here")
    p.add_argument(
        "--reuse-workdir",
        action="store_true",
        help="Skip decompile; patch an existing --workdir decode tree",
    )
    p.add_argument(
        "--force-same-version",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Smali patches so CURRENT / same version still OTAs (default: on)",
    )
    p.add_argument("--keystore", type=Path, default=Path("dist/inject.keystore"))
    p.add_argument("--ks-pass", default="injectinject")
    p.add_argument("--ks-alias", default="inject")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    if not args.apk.is_file() and not args.reuse_workdir:
        p.error(f"APK not found: {args.apk}")
    if args.reuse_workdir and args.workdir is None:
        p.error("--reuse-workdir requires --workdir")

    base_url = f"http://{args.host.strip()}:{int(args.port)}/"
    log.info("deviceApiUrl -> %s", base_url)

    apkeditor = vendor_jar("APKEditor.jar")
    args.out.parent.mkdir(parents=True, exist_ok=True)

    tmp_ctx = (
        tempfile.TemporaryDirectory(prefix="suunto-patch-")
        if args.workdir is None
        else None
    )
    work = Path(args.workdir) if args.workdir else Path(tmp_ctx.name)  # type: ignore[union-attr]

    try:
        if args.reuse_workdir:
            if not (work / "smali").is_dir():
                p.error(f"--workdir does not look like a decode tree: {work}")
            log.info("reusing decode tree %s", work)
        else:
            decompile(args.apk, work, apkeditor)
        patch_device_api_url(work, base_url, also_cn=args.also_cn)
        patch_network_security(work)
        if args.force_same_version:
            patch_force_same_version(work)

        unsigned = args.out.with_name(args.out.stem + "-unsigned.apk")
        build(work, unsigned, apkeditor)
        ensure_keystore(args.keystore, args.ks_alias, args.ks_pass)
        signed = sign_apk(unsigned, args.keystore, args.ks_alias, args.ks_pass)
        if signed.resolve() != args.out.resolve():
            shutil.copy2(signed, args.out)
    finally:
        if tmp_ctx is not None:
            tmp_ctx.cleanup()

    log.info("wrote %s", args.out.resolve())
    log.info("Uninstall Play Suunto first, then: adb install -r %s", args.out)
    log.info(
        "Server: python -m suunto_fw_inject server --zip YOUR.zip "
        "--public-host %s --port %s --version 1.14.0 --force-update",
        args.host,
        args.port,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
