# MMI firmware — build handoff

Read `docs/UNIT-SPECS.md` first. This is the plan for turning the MMI launcher (the
`mbux` branch) into a full firmware OS for the Opel Astra J head unit.

## Where things stand
- **MMI the app is done and self-updating over Wi-Fi.** It is the home screen, with its
  own UI, radio, USB music, Bluetooth calls, navigation, car data, sound, and CarPlay/
  Android Auto via the hidden ZLink engine. The app needs nothing from this doc.
- **The firmware (removing the vendor system, baking MMI in, custom boot) is BLOCKED on
  one input: the stock firmware image.** Everything else is ready.

## The one blocker: get the stock firmware package
Need a copy of the unit's stock firmware to use as the base and the recovery safety net.
Three ways, any one unblocks the build:
1. **Pull it off the unit** — needs root or a working `adb` connection. Both currently
   unavailable (unit not rooted; USB port is host-only, laptop never enumerated it even
   with Force USB 1.1). If root or adb ever works, `RootOptimize.backupPartitions()` /
   `dd` of the by-name partitions gives a **partial raw capture** (boot, dtbo, vbmeta*,
   super, system_tw, misc, env, bootloader), not yet a proven, flashable stock image:
   record each partition's size and SHA-256 and check the set against the partition
   table, dynamic-partition metadata and vbmeta before relying on it.
2. **Seller** — owner cannot reach them.
3. **Forum** — exact-match firmware for this board (Allwinner A133 `ceres-b6`, TS10,
   Raise Opel Astra J) from a site like 4PDA. Must match the board exactly or it bricks.

**What "Send unit data" is not:** Developer → Send unit data uploads diagnostics (system
report, vendor APKs/libraries, logs, and ZLink data only on a rooted unit) with a file
manifest. It has no access to partitions and is **not** a firmware image.

## Build steps (once the stock package is in hand)
1. Unpack the stock image (Allwinner img; `imgRePacker`/`sunxi` tools, or the OTA zip).
2. In `super`/`system_tw`: remove the vendor launcher + the apps MMI replaces (see the
   "safe" list in `FlutterBridge.kt VendorApps` and `RootOptimize.SAFE_OFF`).
   **KEEP** (hidden): `com.tw.service(.xt)`, `com.tw.core`, `com.tw.coreservice`,
   `com.tw.bt`, `com.tw.keypad`, `com.tw.reverse`, `com.tw.carinfoservice`, `com.tw.car`,
   `com.tw.carchoose`, `com.tw.ac`, `com.tw.eq`, `com.zjinnova.zlink` (CarPlay engine).
3. Install MMI.apk as a **priv-app**, set as the only HOME, grant its permissions in the
   image (write_secure_settings etc. so it needs no USB setup).
4. Set `AIR_POPUP_WINDOW=0`, `SYSTEM_DOOR2=1` in the default settings (kills vendor popups).
5. Custom boot logo via the factory menu's "LOGO setting", or the `bootlogo` partition.
   Boot animation: `tools/setup/opel-bootanimation-1280x800.zip`.
6. Re-sign with **test-keys** (public — see UNIT-SPECS). Unverified: test-keys in the
   build properties don't prove the bootloader, verified boot (several vbmeta partitions)
   and the updater accept a repacked image. Confirm on the stock package before flashing.
7. Repack to the `twupdate` OTA format. Flash via **USB stick in the unit** (the host
   port reads sticks fine) through the unit's own update mechanism. Keep the stock
   package as the recovery image.

## Keep vs strip — reference
- Strip (MMI replaces): vendor home `com.dofun.variety`, CarMate, weather, video, radio,
  music, EQ UI — but keep the car-link + Bluetooth + CarPlay services above.
- Full per-app status: Settings > System tuning / Vendor apps in MMI, and `VendorApps` in
  `app/.../FlutterBridge.kt`.

## Protocol reference
- `scratchpad/carapps/PROTOCOL.md` (33 sections) — every vendor message MMI uses.
- Not in the repo (regenerate from the unit export if needed): the decompiled vendor apps.

## Risk
- Wrong image or bad repack = brick. Always flash with the stock package on hand to
  recover. Test on a spare unit first if possible.
- Safer order: (A) MMI as HOME on the stock system (today); (B) a reproducible image on
  the exact stock base, removing vendor apps only after MMI matches each one on the car;
  (C) deeper changes only once recovery is proven.
- The Developer PIN is a convenience lock (the same on every unit), not security: never
  rely on it to protect root actions, backups or uploads.
