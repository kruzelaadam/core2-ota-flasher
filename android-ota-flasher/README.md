# Core2 OTA Flasher

Independent research tool. It is not affiliated with Suunto. You can brick the watch.
See the repository [README](../README.md) for the full disclaimer.

Android app that serves Suunto Core 2 (Explorer) firmware packages over BLE.
Official packages are downloaded on the device from Suunto's catalog. Modified
packages come from a ZIP you already have. This repository does not ship firmware.

**Author:** Adam Krůžela  
**Scope:** Suunto Core 2 only. Do not use on other models.

## How it works

The Core 2 pulls its update: the phone announces the package (`Version/Info`), then the
watch requests every block it wants (`READ`), asks for the Bluetooth radio update
(`DFU`, done with mcumgr like the Suunto app) and reports progress until 100 %.
The flasher answers those requests byte-for-byte from the package; it never edits a
ZIP, a binary or a version. Details: [docs/OTA_PROTOCOL.md](docs/OTA_PROTOCOL.md).

To receive the watch's requests the flasher borrows the MDS engine (`libmds.so`) of
the installed official Suunto app — the Movesense engine bundled in `mdslib` cannot
subscribe to them. Keep the Suunto app installed, but force-stop it before flashing.

## Features

- Lists bonded BLE devices named `Suunto Core 2 …`, remembers the last one
- **Original**: Explorer firmware history from Suunto's catalog, official ZIP download
- **Modified**: `Corium_*` packages from the Drive mirror, with `Version.txt` notes
- Read-only package validation before connecting (root names, types 0..3, CRC
  domains, Epson header, nested MCUboot); the reason is shown if a package is refused
- Progress for download, blocks read by the watch, radio DFU and the watch's own progress
- **Engine** selector Auto / Suunto / Movesense, with a shortcut to force-stop the
  Suunto app
- Comm log of every request, READ and result (Copy log), meant for the firmware agent
- **Secret menu**: `POST MDS/BypassRouter` then `PUT /Device/Ble/mode {"mode":10}`
  reboots into the factory test menu; Exit restores mode 5

## Requirements

- Android 12+ (`minSdk 31`) with Bluetooth LE
- Watch bonded in system Bluetooth, battery ≥ ~25 %
- Official Suunto app (`com.stt.android.suunto`) installed, force-stopped while flashing
- `app/libs/mdslib-*.aar` (run `bash scripts/fetch_mds_aar.sh` from the repo root if missing)

## Build

```bash
cd android-ota-flasher
# set sdk.dir in local.properties, or ANDROID_HOME
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # package validation against data/firmware/*.zip
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
(`applicationId` `cz.adamkruzela.core2ota.debug`). Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The unit tests skip packages that are not in `../data/firmware/` (official ZIPs are
not committed; download them from the catalog).

## Modified firmware

Rules and tooling for building a flashable modified package are in
[docs/FW_AGENT_PACKAGING_RULES.md](docs/FW_AGENT_PACKAGING_RULES.md) (Czech):

```bash
python3 scripts/rebuild_application_zip.py --base <official.zip> --app <modified.bin> --out <mod.zip>
python3 scripts/validate_package.py <mod.zip> --base <official.zip>
```

Modified mode reads `MODIFIED_MIRROR_FOLDER_ID` and `MODIFIED_INDEX_FILE_ID` from
`local.properties`. Point them at a Drive folder of `Corium_*` packages, each with
`Version.txt` and the ZIP. Leave them empty to hide Modified mode.

## Catalog

```
GET https://devices.suunto-operations.com/firmware/devices/Explorer/A/history?appkey=…
```

## Out of scope

- Play Store packaging
- Non–Core 2 devices
