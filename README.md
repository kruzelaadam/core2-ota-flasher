# Core 2 OTA Flasher

Nezávislý open-source nástroj pro aftermarket firmware a OTA aktualizace hodinek **Suunto Core 2** (katalogová řada Explorer). Android aplikace pošle balíček po BLE stejným tahem, jaký používá oficiální aktualizace: hodinky si samy říkají o bloky, telefon je jen obsluhuje. Umí oficiální balíček stažený z katalogu Suunto a vlastní ZIP, který si připravíte lokálně.

## Právní prohlášení

Tento projekt je nezávislý open-source nástroj určený výhradně pro výzkumné účely a vývoj aftermarket firmwaru. Není spojen se společností Suunto, ani jím neporušuje její autorská práva. Všechny ochranné známky patří jejich vlastníkům. Uživatel nese veškeré riziko spojené s přehráváním firmwaru, včetně možnosti znefunkčnění hodinek.

Repozitář **nedistribuuje** firmware Suunto, dumpy paměti ani proprietární knihovny. Oficiální balíček si aplikace stáhne až na vašem telefonu z katalogu Suunto, pokud do `local.properties` doplníte vlastní katalogový klíč. Vlastní ZIP zůstává u vás.

## Co je v repozitáři

| Část | K čemu je |
|---|---|
| [`android-ota-flasher/`](android-ota-flasher/) | Android aplikace. Original stáhne oficiální ZIP, Modified bere ZIP z vámi nastavené Drive složky, nebo lze flashnout soubor, který aplikace už má stažený. |
| [`android-ota-flasher/scripts/`](android-ota-flasher/scripts/) | Kontrola a přebalení ZIP, který jste si sami upravili. Nic nestahuje. |
| [`android-ota-flasher/docs/`](android-ota-flasher/docs/) | Popis pull protokolu a pravidla pro vlastní balíček. |
| [`suunto_fw_inject/`](suunto_fw_inject/) | Volitelný pomocník: na vašem počítači naservíruje **váš** ZIP a přepne katalog v **vaší** kopii aplikace Suunto. APK Suunto se do gitu nedává. |
| [`scripts/fetch_mds_aar.sh`](scripts/fetch_mds_aar.sh) | Stáhne MDS knihovnu Movesense, kterou aplikace potřebuje ke kompilaci. Není součástí gitu. |
| [`scripts/fetch_vendor.sh`](scripts/fetch_vendor.sh) | Stáhne APKEditor pro `suunto_fw_inject`. |

## Sestavení aplikace

Potřebujete Android SDK (compileSdk 35), JDK 17 a zařízení s Androidem 12 nebo novějším.

```bash
bash scripts/fetch_mds_aar.sh
cp android-ota-flasher/local.properties.example android-ota-flasher/local.properties
# doplňte sdk.dir a SUUNTO_APPKEY
cd android-ota-flasher
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`SUUNTO_APPKEY` je klíč, kterým oficiální aplikace čte katalog. Bez něj jde flashovat jen ZIP, který už v telefonu je. `MODIFIED_MIRROR_FOLDER_ID` nechte prázdné, dokud nemáte vlastní složku s balíčky `Corium_*`.

Před flashem nechte oficiální aplikaci Suunto nainstalovanou (flasher si z ní půjčuje MDS engine) a vynuceně ji zastavte. Hodinky musí být spárované v systému, probuzené a s baterií zhruba nad 25 %.

## Vlastní ZIP

```bash
python3 android-ota-flasher/scripts/validate_package.py vas-balicek.zip --base oficialni.zip
python3 android-ota-flasher/scripts/rebuild_application_zip.py \
  --base oficialni.zip --app Application_upravena.bin \
  --out vysledek.zip --offer-version 1.14.3 --after-trailer 16
```

Podrobnosti jsou v [`android-ota-flasher/docs/FW_AGENT_PACKAGING_RULES.md`](android-ota-flasher/docs/FW_AGENT_PACKAGING_RULES.md).

## Inject server

```bash
bash scripts/fetch_vendor.sh
python -m suunto_fw_inject server --zip vas-balicek.zip --port 8765 --public-host <ip-pocitace>
python -m suunto_fw_inject patch --apk Suunto.apk --host <ip-pocitace> --port 8765
```

`Suunto.apk` si opatříte sami. Příkaz `patch` přepíše jen adresu katalogu ve vaší kopii.

## Licence

[MIT](LICENSE). Suunto a Movesense zůstávají ochrannými známkami svých vlastníků a jejich software tato licence nepokrývá.
