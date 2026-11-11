# Pro firmware agenta — jak připravit OTA ZIP pro Core 2 flasher 0.4.0+

Flasher od 0.4.0 posílá balíček **přesně tak, jak je** — nic v ZIPu ani v binárkách
nepřepisuje, nevylhává verzi, nepřidává padding. Oficiální balíček od Suunta projde
beze změny. Modifikovaný balíček proto musí být správně postavený už z tvé strany.

## Jak hodinky balíček dostanou (stejně jako oficiální aplikace)

1. Telefon pošle `Update/Transfer/Nordic/Version/Info`: pro každou položku
   `firmwares` z `firmwareDescription.json` čtveřici `type`, `version`, `size`, `crc32`
   — v pořadí z JSON a s hodnotami 1:1 z JSON.
2. Hodinky si samy řeknou o bloky: `READ type/offset/size`. Telefon odpoví bajty
   z rozbaleného souboru + `blockCrc` (CRC32 bloku). Velikost bloků, pořadí komponent
   i to, které komponenty hodinky přeskočí, určují **hodinky**.
3. `DFU` → telefon nahraje Bluetooth balíček (type 3) do rádia přes mcumgr/SMP.
4. `PROGRESS` (0–100) — 100 = hotovo. `FAIL_REASON` = hodinky balíček odmítly;
   surová hodnota je v comm logu.

Z toho plyne: cokoli, co hodinky z balíčku čtou, musí sedět samo o sobě.

## Tvrdá pravidla (flasher jinak balíček odmítne ještě před připojením)

1. **ZIP:** jen soubory v kořeni — žádné složky, `..`, zpětná lomítka ani duplicitní
   názvy. Komprese STORED nebo DEFLATE.
2. **`firmwareDescription.json`** v kořeni, UTF-8 JSON. `firmwares` obsahuje každý
   `type` 0, 1, 2, 3 **právě jednou**; každý `filename` je jiný a v ZIPu existuje.
3. **Každá komponenta:** `size` = délka souboru, `crc32` = CRC32 celého souboru
   (bez znaménka).
4. **Epson image (type 0 BL1, 1 BL2, 2 Application):**
   - magic `0xDA11C10C` na `+0x1C` i `+0x28`,
   - `+0x20` base: BL1 `0x2000`, BL2 `0xA000`, Application `0x12000`,
   - `+0x24` = base + délka zapečetěné části − 4,
   - poslední 4 B zapečetěné části = LE CRC32 všeho před nimi. CRC32 té části je vždy
     `0x2144DF1C` = **558161692**.
   - Za trailerem smí být přenosový suffix (`--after-trailer`). JSON `size` a `crc32`
     pak popisují **celý** soubor včetně suffixu. Bajty vložené dovnitř image, před
     trailer, hodinky odmítají (24.03). Starý `Explorer-fw_1.13.45-date.zip` padá,
     protože `+0x24` nesedí na zapečetěnou část.
5. **Bluetooth (type 3):** vnořený ZIP s `manifest.json` a MCUboot image
   (magic `0x96F3B83D`). Kontroluje se i to, že vnořený ZIP nemá duplicity.

## Doporučení

- **BL1, BL2 a Bluetooth nech byte-identické** s oficiálním balíčkem, ze kterého
  vycházíš (stejný název souboru, `version`, `size`, `crc32`).
- **Neměň verzi uvnitř binárky.** Verzní slova jsou závislá na buildu:
  - 1.13.44: `+0x34`, `+0xADD0`,
  - 1.14.2: `+0x34`, `+0xC330`, `+0x3003A` — a `0xADD0` je v 1.14.2 **kód**
    (`0x88404668`).
  Staré skripty, které patchovaly `+0xADD0` a About `@0x45860`, by 1.14.2 rozbily.
- Délku Application ideálně ponech. Když se změní, musí se upravit `+0x24` (skript to
  udělá).
- Pořadí záznamů jako oficiál (Application, Bluetooth, BL1, BL2, JSON) — povinné to
  není, ale `rebuild_application_zip.py` ho drží automaticky.
- Když se délka nezmění, `firmwareDescription.json` může zůstat **byte-identický**:
  po přepečetění je CRC32 opět 558161692 a `size` stejné.

## Verze: aby to nezablokovalo budoucí oficiální updaty

Hodinky po flashi hlásí verzi, kterou nese **binárka** (`+0x34`). Podle ní se
rozhoduje, jestli se nabídne další oficiální update. Proto binárku nech na původní
verzi. Pole `version` u type 2 v JSON je jen **nabídka**, kterou dostanou hodinky ve
Version/Info.

- **Varianta A — stejná verze** (výchozí, bez `--offer-version`): JSON říká totéž co
  binárka. Funguje vždy, když hodinky běží na **starší** verzi než základ modu
  (např. hodinky 1.13.44, mod postavený na 1.14.2).
- **Varianta B — nabídka o patch výš** (`--offer-version 1.14.3`): použij, když
  hodinky už běží na stejné verzi jako základ modu. Hodinky Application zapíšou jen
  při `nabízená > nainstalovaná` (gate na VA `0x1CC05` v 1.13.44). Na zařízení
  ověřeno 10. 11. 2026: hodinky s 1.14.2 na nabídku 1.14.2 odpověděly „up to date“
  bez jediného READ. Binárka dál hlásí 1.14.2, takže oficiální 1.14.3+ se nabídne
  normálně a každý další mod může znovu nabízet 1.14.3. Stejně to udělal
  Corium_1.0.0 (nabídka 1.14.0 na binárce 1.13.44).
- **Nikdy** nezvedej verzi v binárce: hodinky by hlásily vyšší verzi a oficiální
  updaty do ní by se nenabídly.

Neověřeno: zda si hodinky nabízenou verzi někam neuloží (a pak ji nehlásí místo verze
binárky). Po prvním flashi ověř About a co hlásí oficiální aplikace.

## Postup

```bash
# 1) oficiální balíček (stažený beze změny) musí projít
python3 scripts/validate_package.py Explorer-fw_1.14.2-A-5174928.zip

# 2) modifikovaná Application → ZIP (zapečetí, BL/BT 1:1, JSON jen když je potřeba)
python3 scripts/rebuild_application_zip.py \
    --base Explorer-fw_1.14.2-A-5174928.zip \
    --app Application_modified.bin \
    --out Explorer-fw_1.14.2-corium.zip \
    --offer-version 1.14.3 --after-trailer 16 [--package-info "Corium 1.0.1: …"]

# 3) kontrola proti základu (exit 0 = flasher ho přijme)
python3 scripts/validate_package.py Explorer-fw_1.14.2-corium.zip \
    --base Explorer-fw_1.14.2-A-5174928.zip
```

`scripts/seal_application_crc.py image.bin` jen zapečetí samotnou binárku
(`+0x24` + trailer). Hodí se během úprav, ZIP to nestaví.

Do Drive mirroru patří složka `Corium_X.Y.Z/` s `Version.txt` a výsledným ZIPem.

## Typické chyby

- **Stejná délka + CRC 558161692 → hodinky nic nestáhnou.** Každý zapečetěný
  Epson image má CRC celého souboru 558161692. Když má Application i stejnou délku
  jako to, co v hodinkách běží, hodinky 3 s po Version/Info spojení shodí a
  nepošlou jediný READ — i když `version` v JSON je vyšší. Ověřeno 10. 11. 2026
  na Corium 1.0.1 (nabídka 1.14.3, délka 276749, CRC 558161692, hodinky běží 1.14.2).
  Oprava: `--after-trailer 16`. Připojí 16 B za CRC trailer, v JSON bude nová
  `size` a `crc32` celého souboru. `+0x24` pořád ukazuje na původní trailer, takže
  zapečetěný image se nemění. Bez toho vyšší verze v JSON jen přepíše nápis.
- **Stejná verze jako v hodinkách → „up to date“.** Hodinky s 1.14.2, kterým se
  nabídne Application 1.14.2, update odmítnou hned po Version/Info, nic nečtou a
  spojení spadne. Na hodinky s 1.14.2 musí jít mod s `--offer-version 1.14.3`
  (jen JSON, binárka zůstává 1.14.2). Totéž platí pro oficiální balíčky: flashnout
  jde jen novější verze, než je v hodinkách, ne stejná ani starší.
- **Obrazovka „aktualizováno na X“ ukazuje verzi z JSON, ne z binárky.** About
  pořád čte `+0x34`. Úspěch modu poznáš podle nové funkce a podle řádků
  `READ type=2` v comm logu, ne podle čísla verze v About.

## Co číst v comm logu po pokusu

- `part type=… crc32=0x…/unsigned/signed sha256=…` — co flasher poslal (porovnej
  s výstupem `validate_package.py`).
- `FIRST_REQUEST operation=…` — první věc, o kterou si hodinky řekly.
- `READ type=2 Application … #n offset=… size=… crc=…` — hodinky čtou Application.
  Žádné `READ type=2` = Application přeskočena (verze / stejný obsah).
- `DFU_START`, `DFU_STATE`, `DFU_UPLOAD`, `DFU_DONE`/`DFU_FAILED` — rádio.
- `PROGRESS progress=…`, `FAIL_REASON failReason=…` — výsledek od hodinek.
- Závěrečné `Served: …` — kolik bloků a bajtů každé komponenty hodinky přečetly.
