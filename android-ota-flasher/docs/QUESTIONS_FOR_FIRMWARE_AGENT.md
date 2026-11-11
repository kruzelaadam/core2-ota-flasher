# Open questions for the firmware agent (flasher 0.4.0)

The 0.4.0 comm log answers most of these: it records every request the watch makes
(`FIRST_REQUEST`, `READ type=… offset=… size=…`, `PROGRESS`, `FAIL_REASON`, `DFU_*`)
and ends with a `Served: …` summary per component.

1. **Version gate.** Confirmed on device that 1.14.2 declines a same-version offer
   ("up to date", no request at all). Open: is it compared against the literal at
   `+0xC330` / `+0x3003A`, and is only the Application version compared?
2. **Skipping.** Does the watch skip BL1/BL2/Bluetooth when their offered version
   (or size/crc32) equals what is installed? Which field decides?
3. **Where the version comes from after flashing.** Does About / `Update/Info` report
   the binary's `+0x34` or a stored copy of the offered version?
4. **FAIL_REASON values.** Mapping of the raw `failReason` numbers to the watch error
   screens (24.02, 24.03, …).
5. **Order.** In which order does the watch read the components, and when does it ask
   for the radio DFU relative to the Epson reads?
