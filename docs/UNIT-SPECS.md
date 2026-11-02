# Opel Astra J head unit — full specifications

Pulled from the unit's own system report, the Allwinner A133 datasheets, and the
vendor-app study (`scratchpad/carapps/PROTOCOL.md`). Facts, not guesses.

## Platform
- **SoC:** Allwinner **A133** (board `ceres-b6` / `ceres_b6`), 28 nm
- **Unit type:** TS10 Android head unit, **7-inch** screen (LVDS/MIPI, panel supports up to 1920×1080)
- **Android:** 10 (API 29), build `QP1A.191105.004`, built 2025-05-13
- **Signing:** **test-keys** (public AOSP keys) — custom firmware can be re-signed and accepted
- **Kernel:** Linux 4.9.170, 32-bit, SMP PREEMPT

## CPU
- 4× ARM **Cortex-A53** up to **1.6 GHz** (ARMv8 silicon, **runs 32-bit only** — abilist `armeabi-v7a, armeabi`, no /system/lib64)
- NEON, AES, SHA1/2, CRC32; 32 KB L1 I + 32 KB L1 D per core, 512 KB shared L2
- 3 thermal sensors (CPU/GPU/DDR). Junction max 110 °C, ambient max 70 °C. Allwinner `aw_thermal` can offline cores when hot.

## GPU
- Imagination **PowerVR GE8300**
- OpenGL ES 1.1/2.0/**3.2**, **Vulkan 1.1**, OpenCL 1.2 (`ro.opengles.version=196610`)

## Memory & storage
- **RAM:** 2 GB DDR3 (`MemTotal 2022712 kB`), 32-bit bus
- **zram swap:** ~1.5 GB (`1517028 kB`)
- **Storage:** ~32 GB eMMC (`mmcblk0`, 30.5 GB)
- Verified boot (vbmeta A/B: `vbmeta`, `vbmeta_system`, `vbmeta_vendor`), dynamic partitions (`super`)

### Partitions (by-name, from the report)
`boot, dtbo, recovery(?), bootloader, misc, env, cache, super (1 GB), system_tw (768 MB),
twdataconfig, twupdate, private, media_data, metadata, frp, UDISK, vbmeta, vbmeta_system, vbmeta_vendor`
- Firmware updates land via **`twupdate`** (the unit's own OTA path — the safe flash route).
- `system_tw` + `twdataconfig` hold the vendor's TS10 layer.

## Video / audio
- Decode: H.265 4K@30, H.264 4K@30, VP9 720p, MJPEG 1080p
- Encode: H.264 1080p@60
- Display Engine: 4 overlay layers + independent scaler; Graphic 2D (G2D) up to 2048×2048

## Car integration (vendor-app study)
- **CAN box:** Raise, **Opel Astra J 2011–2017** (`Raise-Opel-11-17 Opel Astra J-Public`)
- **Dash:** CD300
- **Car data path:** TWUtil (`android.tw.john.TWUtil`) + `com.tw.carinfoservice` AIDL (open to apps)
  - Doors on CAN frame **0x14** (b0: bit7 FL, bit6 FR, bit5 RL, bit4 RR, bit3 trunk)
  - Speed/rpm/lights/handbrake/steering via CarInfoService; odometer/range/fuel/TPMS per CarServiceTheme
- **Amp:** MCU message 257 — balance idx 43 / fader idx 42 (Rohm) or 7/6 (others), 0..18, 9=centre
- **Radio:** TWUtil 1025/1026/1028/1029; `com.tw.radio` replaceable
- **CarPlay / Android Auto:** ZLink/Tlink5 (`com.zjinnova.zlink`, zjinnova build) — licensed engine, keep hidden
- **Reverse camera:** analogue, Camera API id **4**, TP2850 decoder (vendor app holds it)

## Root
- **Not rooted.**
