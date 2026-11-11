# Core 2 OTA Flasher

Independent open-source tool for aftermarket firmware and over-the-air updates of the **Suunto Core 2** (Explorer). The Android app serves a package over BLE the same way an official update does: the watch asks for each block, and the phone only answers. It can serve an official package downloaded from Suunto's catalog, or a ZIP you built locally.

## Disclaimer

This project is an independent open-source tool meant only for research and for developing aftermarket firmware. It is not affiliated with Suunto, and it does not claim any of Suunto's copyrights. All trademarks belong to their owners. You take the full risk of flashing firmware, including the chance of bricking the watch.

This repository does **not** ship Suunto firmware, memory dumps, or proprietary libraries. The app downloads an official package on your phone from Suunto's catalog only if you put your own catalog key in `local.properties`. A modified ZIP stays on your machine.

## What is in this repository

| Path | What it is |
|---|---|
| [`android-ota-flasher/`](android-ota-flasher/) | The Android app. Original mode downloads an official ZIP. Modified mode reads a ZIP from a Google Drive folder you configure, or flashes a file the app already has. |
| [`android-ota-flasher/scripts/`](android-ota-flasher/scripts/) | Check and repack a ZIP you edited yourself. These scripts do not download anything. |
| [`android-ota-flasher/docs/`](android-ota-flasher/docs/) | The pull protocol and the rules for a package you build. |
| [`suunto_fw_inject/`](suunto_fw_inject/) | Optional helper. It serves **your** ZIP on your computer and points **your** copy of the Suunto app at that server. The Suunto APK is not in git. |
| [`scripts/fetch_mds_aar.sh`](scripts/fetch_mds_aar.sh) | Downloads the Movesense MDS library the app needs in order to compile. It is not committed. |
| [`scripts/fetch_vendor.sh`](scripts/fetch_vendor.sh) | Downloads APKEditor for `suunto_fw_inject`. |

## Build the app

You need the Android SDK (compileSdk 35), JDK 17, and a phone running Android 12 or newer.

```bash
bash scripts/fetch_mds_aar.sh
cp android-ota-flasher/local.properties.example android-ota-flasher/local.properties
# set sdk.dir and SUUNTO_APPKEY
cd android-ota-flasher
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`SUUNTO_APPKEY` is the key the official app uses to read the catalog. Without it you can only flash a ZIP that is already on the phone. Leave `MODIFIED_MIRROR_FOLDER_ID` empty until you have your own folder of `Corium_*` packages.

Before flashing, keep the official Suunto app installed (the flasher borrows its MDS engine) and force-stop it. The watch must be bonded in system Bluetooth, awake, and charged to about 25% or more.

## Your own ZIP

```bash
python3 android-ota-flasher/scripts/validate_package.py your-package.zip --base official.zip
python3 android-ota-flasher/scripts/rebuild_application_zip.py \
  --base official.zip --app Application_modified.bin \
  --out result.zip --offer-version 1.14.3 --after-trailer 16
```

Details are in [`android-ota-flasher/docs/FW_AGENT_PACKAGING_RULES.md`](android-ota-flasher/docs/FW_AGENT_PACKAGING_RULES.md).

## Inject server

```bash
bash scripts/fetch_vendor.sh
python -m suunto_fw_inject server --zip your-package.zip --port 8765 --public-host <computer-ip>
python -m suunto_fw_inject patch --apk Suunto.apk --host <computer-ip> --port 8765
```

You supply `Suunto.apk` yourself. `patch` only changes the catalog address inside your copy.

## License

[MIT](LICENSE). Suunto and Movesense remain trademarks of their owners. This license does not cover their software.
