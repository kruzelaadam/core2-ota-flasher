# Suunto Core 2 / Explorer OTA protocol (as run by flasher 0.4.0)

Reconstructed from the Suunto Android app (`NordicOtaModel`, `FirmwareUpdateManager`,
mcumgr DFU) and checked against official packages 1.13.38, 1.13.44 and 1.14.2.

## Overview: the watch pulls

The phone does not push the package. It announces what it has, then answers the
watch's requests until the watch reports 100 %.

```
phone                                              watch
  SUBSCRIBE MDS/EventListener {"Uri":"<serial>/Update/Transfer/Nordic/Request"}
  PUT <serial>/Update/Transfer/Nordic/Version/Info  ───────────▶
                                      ◀── NOTIFY TransferReq (operation=…)
  READ  → PUT <serial>/Update/Transfer/Nordic/Data/Response
  DFU   → mcumgr upload of the Bluetooth image (SMP over BLE)
  PROGRESS n          (UI only; 100 = done)
  FAIL_REASON r       (watch rejected the update)
```

## Contracts

`Version/Info` — one entry per `firmwareDescription.json` component, in description
order, values unchanged:

```json
{"TransferVersionRes":{"versionList":[
  {"type":0,"version":"1.0.44","size":28542,"crc32":558161692}, …]}}
```

`TransferReq` (notification body): `operation`, `fileRequest {fileType, fileOffset,
fileSize}`, `progress`, `failReason`.

| operation | value | phone action |
|-----------|-------|--------------|
| INFO | 0 | send Version/Info again |
| READ | 1 | answer with the requested bytes |
| DFU | 2 | start the mcumgr upload of type 3 (fire-and-forget) |
| PROGRESS | 3 | show progress; 100 = completed |
| FAIL_REASON | 4 | failed, raw `failReason` |
| unknown | −1 | log only |

READ reply:

```json
{"TransferDataRes":{"result":0,
  "fileRequest":{"fileType":2,"fileOffset":0,"fileSize":512},
  "blockCrc":<CRC32(block) as signed int32>,
  "blockData":[0..255, …]}}
```

The stock app rejects (sends nothing back) when `offset < 0`, `size < 0` or
`offset + size > file length`; `size == 0` is allowed. The flasher does the same and
stops with an explanatory error instead of guessing. Block size, component order and
which components get read are the watch's choice; the flasher hardcodes none of them.

## Bluetooth radio DFU

On `DFU` the stock app runs `FirmwareUpgradeManager` (mcumgr) with the type-3 package:
mode TEST_AND_CONFIRM, estimated swap time 10 s, app settings kept. The image set comes
from the nested ZIP's `manifest.json` (`image_index`, `slot` or "mcuboot" sequential,
otherwise secondary slot), or from the raw file if it is a bare MCUboot image.
A `NOT_SUPPORTED` error in the CONFIRM step counts as success. The flasher uses
mcumgr-ble 2.2.2 with the same settings (`ota/BluetoothDfu.kt`).

Completion is still decided by the watch: `PROGRESS 100`.

## Why the MDS engine matters

The Movesense `libmds.so` bundled in `mdslib-3.33.7` answers the Nordic/Request
subscription with a local `404 Failed to get metadata`: its resource metadata does not
include the Suunto Update resources, so READ requests never reach the phone. That is
why every flasher before 0.4.0 could only push metadata and never serve the data.

The official Suunto app ships its own `libmds.so` with the same JNI contract as the
Movesense Java layer (`SDSInternalWrapper`, `Logger`, `com.suunto.komposti.NGBLEWrapper`
natives, the `SDSInternalDelegate`/`NGBLEDelegate` callbacks and the `BLEWrapper`
result classes are identical). The flasher copies that library out of the installed
Suunto app (`lib/<abi>/libmds.so`, from a split APK when native libs are not
extracted), loads it before MDS starts and keeps its own engine as
`libmds_movesense.so` for fallback (`mds/MdsEngine.kt`). Engine choice
Auto / Suunto / Movesense is in the app; Auto falls back to Movesense if the Suunto
engine crashed natively on the previous run.

The Suunto app must therefore stay installed, but must be force-stopped while flashing:
its own MDS service would otherwise hold the watch link and answer the watch itself.

## Link loss

Stock re-subscribes on every connect. The flasher only reconnects (within 4 minutes)
after the watch has started requesting data; it never restarts a flash by itself.
During the radio DFU the BLE link is expected to drop; the flasher waits for mcumgr.
Idle limits: 10 min until the first request, 3 min between requests, 6 min during DFU.

## Package format

`firmwareDescription.json` types (distinct from the operation codes above and from the
watch-internal IPC packet types):

| type | component | file |
|------|-----------|------|
| 0 | Bootloader1 | Epson image, base 0x2000 |
| 1 | Bootloader2 | Epson image, base 0xA000 |
| 2 | Application | Epson image, base 0x12000 |
| 3 | Bluetooth | nested ZIP: `manifest.json` + MCUboot image (magic 0x96F3B83D) |

Epson header: magic `0xDA11C10C` at `+0x1C` and `+0x28`, base at `+0x20`,
`+0x24 = base + len − 4` (address of the trailer), version word at `+0x34`
(`major<<24 | minor<<16 | patch`). The last 4 bytes are LE CRC32 of everything before.

Four separate CRC domains:

| | what | value |
|-|------|-------|
| A | ZIP entry CRC | CRC32 of the file (for Epson parts = 0x2144DF1C) |
| B | Epson trailer (last 4 B, LE) | CRC32(file[:-4]) |
| C | description `crc32` | CRC32 of the whole file — 558161692 (0x2144DF1C, the CRC residue) for every sealed Epson image; the nested ZIP's CRC for Bluetooth |
| D | READ `blockCrc` | CRC32 of each served block, signed int32 |

Packaging rules for modified firmware: [FW_AGENT_PACKAGING_RULES.md](FW_AGENT_PACKAGING_RULES.md).

## Version gate

Seen on device (flasher 0.4.0, watch on 1.14.2, official 1.14.2 offered): the watch
accepted the Version/Info PUT, showed "up to date", sent no request at all and
re-sent a Whiteboard HELLO about 3 s later, which drops the MDS link.

A newer offer (1.14.3 over installed 1.14.2, flasher 0.4.1) is accepted: the watch
starts the update and drops the link the same way, before any READ, while it reboots
into the updater. The phone has to reconnect and serve the data; giving up at that
drop makes the watch announce the offered version without ever writing the image.
The completion screen shows the offered version, not the version word inside the
binary, so About can still say 1.14.2 afterwards.

Earlier static analysis of Application 1.13.44 found the gate at VA `0x1CC05`:
Application is written only when the offered version (from Version/Info) is greater
than the version literal in the running image (VA `0x1CDD0` = file `+0xADD0` in 1.13.44;
the literal moves between builds — 1.14.2 has copies at `+0xC330` and `+0x3003A`).
The flasher only reports installed vs offered; it never changes either.

## Not used for Core 2

`MDS/Firmware/Transfer`, `Update/Suggest`, `Update/Force/Tag`, `Update/Select` and
`Update/InstallSelectedFirmware` belong to the push path of other Suunto watches. Core 2
(Nordic) uses only the pull flow above.

## Catalog

```
GET https://devices.suunto-operations.com/firmware/devices/Explorer/A/history?appkey=…
```

Entries carry `LatestFirmwareVersion` and `LatestFirmwareURI`; the app key is in
`BuildConfig`.

## Battery

Watch-side gate around ≥ 25 %.
