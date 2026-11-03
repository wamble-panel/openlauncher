# ZLink / TLink study notes

Reference notes on the phone-projection app (CarPlay / Android Auto receiver) shipped on this
head unit, written to help Open Launcher integrate with it.

> **Scope and confidence.** The zlink APK itself is **not** in this repo, so this is *not* a
> decompilation of zlink. Everything below comes from:
> 1. `mmi-packages.txt` and `mmi-carlink.txt` (on-device scans), and
> 2. string tables pulled from the DEX files of the head-unit apps that *talk to* zlink
>    (`com.tw.service`, `com.tw.bt`, `com.tw.music`, `com.tw.radio`, plus `framework.jar` /
>    `services.jar`) in `mmi-car-apps-1.zip` and `mmi-car-apps-4.zip`.
>
> Each item is tagged **[confirmed]** (the literal string exists on the device or in a binary) or
> **[inferred]** (my reading of what it's for, not yet checked against code).

---

## 1. ZLink and TLink are the same app

| Field | Value | Source |
|---|---|---|
| Package name | `com.zjinnova.zlink` | [confirmed] `mmi-packages.txt:178` |
| Launcher label | `Tlink5` | [confirmed] `mmi-packages.txt:178` |
| Vendor | Zjinnova (Zhejiang). "ZLink" is the engine/SDK name; "TLink" (here **TLink 5**) is the white-label brand flashed onto TW/Dofun units. | [inferred] |
| Internal code namespace | `com.zjinnova.android.zlink.*` | [confirmed] `com.tw.service` DEX |
| Background service | `com.zjinnova.android.zlink.features.service.DaemonService` | [confirmed] `com.tw.service` DEX |
| Legacy code | Some components live under `com.suding.*` (Suding was the earlier CarPlay vendor; the code was absorbed into ZLink). | [confirmed] the string `ComponentInfo{com.zjinnova.zlink/com.suding…` |

There's only one app to study. "TLink" is the name you see on the home screen, and "zlink" is the
package and code name.

**What it does [inferred, standard for this product]:** it receives wired and wireless Apple
CarPlay and Android Auto, and on some builds Huawei HiCar or phone mirroring. It shows the phone
UI full-screen and routes audio, mic, touch and steering-wheel keys between the phone and the
head unit.

---

## 2. How the head-unit firmware drives zlink

`com.tw.service` (the "TW" system service) owns zlink's lifecycle. Every item below is a literal
string in its DEX.

### 2.1 Broadcast actions sent to zlink

| Action | Meaning [inferred] |
|---|---|
| `com.zjinnova.zlink.action.POWER_ON` | ACC on / unit wakes up. zlink starts listening for phones. |
| `com.zjinnova.zlink.action.POWER_OFF` | ACC off / sleep. zlink tears down the session. |
| `com.zjinnova.zlink.action.OUT_DARK_START` | Screen goes "dark" (screen-off mode, or reverse camera taking over). zlink should stop rendering but keep the session. |
| `com.zjinnova.zlink.action.OUT_DARK_STOP` | Screen is back, so zlink may render again. |

### 2.2 Generic CarPlay show/hide broadcasts

| Action | Meaning [inferred] |
|---|---|
| `android.intent.action.caplay.ShowCarplay` | Bring the projection UI to the front. Note the vendor typo **`caplay`**: match it exactly. |
| `android.intent.action.caplay.HideCarplay` | Send the projection UI to the back. |

### 2.3 Component checks

`com.tw.service` compares the foreground activity against these prefixes to decide whether
"CarPlay is on screen":

- `ComponentInfo{com.zjinnova.zlink/com.zjinnova…`
- `ComponentInfo{com.zjinnova.zlink/com.suding…`

It also knows about other projection apps that can take zlink's place:

- `com.suding.speedplay` / `com.suding.speedplay.ui.MainActivity`: a standalone Suding CarPlay app
- `net.easyconn` (Carbit EasyConnection), which uses the `net.easyconn.a2dp.acquire` / `.release` broadcasts
- `com.chs.mt.hh_dbp810_carplay_usb`, `com.chs.mt.hh_dbs460_carplay`: USB CarPlay-dongle DSP integrations
- `/system_tw/app/CarPlay/CarPlay.apk`: path of a firmware-bundled CarPlay app

### 2.4 Steering-wheel and media key forwarding

`com.tw.bt`, `com.tw.music`, `com.tw.radio` and `com.tw.service` all contain the same helper,
with these log strings:

```
sendZlinkKeyCode: carPlayCn:
sendZlinkKeyCode: targetCn:
```

[inferred] When zlink (`carPlayCn`, the CarPlay component name) is the active audio or UI
source, these apps don't handle next/prev/play keys themselves. They forward the keycode to zlink
(`targetCn`), which passes it to the phone. Your scan backs this up: `mmi-carlink.txt` shows
`KEYCODE_MEDIA_NEXT/PREVIOUS` arriving from a `Virtual` input device (source 257), which is
injected by the TW keypad service.

### 2.5 Settings keys used by `com.tw.service`

`carplay_num`, `carplay_wired`, `carplay_wireless`, `ios_carplay`, plus the log tag
`carplayPhoneCall:`. [inferred] These are counters and flags for which link type is active, and
whether a CarPlay phone call is in progress (used for audio ducking and mic routing).

---

## 3. System properties

All of these appear in `mmi-carlink.txt`. Where they also appear in `com.tw.service`, that's
noted.

| Property | Notes |
|---|---|
| `persist.zlink.disable.features` | [confirmed, also in `com.tw.service`] Feature mask that turns zlink features off, for example wireless AA or HiCar. Survives reboot. |
| `rw.zlink.disable.features` | [confirmed] Runtime version of the same mask. |
| `rw.zlink.hu.acc` | [confirmed, also in `com.tw.service`] Head-unit ACC (ignition) state that TW publishes for zlink. |
| `persist.sys.zlink.cp.wired.disa` | [confirmed] "CarPlay wired disable" (the name is truncated to fit the property-name length limit). |
| `persist.sys.tw.link` | [confirmed, also in `com.tw.service`] Which phone-link app the firmware treats as the default. |
| `tw.carplay.type` | [confirmed] CarPlay implementation type, such as zlink vs. dongle vs. Suding. |
| `suding.carplay.reverse.model` | [confirmed] How the reverse camera interacts with the CarPlay surface. |
| `persist.cpbox.mic` | [confirmed] Mic source when an external CarPlay box is used. |
| `zj.zsender.version` | [confirmed] Version of Zjinnova's "zsender" component. |
| `persist.zj.agc.phone.spk` | [confirmed] Zjinnova AGC (auto gain control) setting for the phone-call speaker. |

`framework.jar` and `services.jar` both contain the literal `com.zjinnova.zlink`, and
`services.jar` also contains `com.zjinnova`. [inferred] The framework hard-codes the package into
an allow-list, most likely for background-start, audio-focus or "always foreground" exemptions,
so that Android 10's restrictions don't kill the projection app.

---

## 4. What this means for Open Launcher

Things a normal (non-system) launcher **can** do:

```kotlin
const val ZLINK_PKG = "com.zjinnova.zlink"

// Is it installed?
fun hasZlink(pm: PackageManager) =
    runCatching { pm.getPackageInfo(ZLINK_PKG, 0); true }.getOrDefault(false)

// Open it (this is what the existing CarPlay tile does when the user picks Tlink5)
fun launchZlink(ctx: Context) {
    ctx.packageManager.getLaunchIntentForPackage(ZLINK_PKG)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?.let(ctx::startActivity)
}

// Read-only system property (via reflection; may return "" on locked-down builds)
fun sysProp(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, key) as String
}.getOrDefault("")
```

Ideas:
- Auto-suggest `com.zjinnova.zlink` in the CarPlay picker when it's installed. It's labelled
  "Tlink5", so users may not recognise it.
- Show a "phone connected" badge on the CarPlay tile. This needs a signal from zlink. Candidates
  are its media session (check `MediaSessionManager` for an active session owned by
  `com.zjinnova.zlink`) or listening for the `caplay.ShowCarplay` broadcast. Neither has been
  checked on the device yet.

Things to **avoid** from a user app:
- Sending `POWER_ON/OFF` or `OUT_DARK_*` yourself. These belong to `com.tw.service`, and racing
  it can leave zlink in a bad state. They may also be permission-protected.
- Writing the `persist.*` / `rw.*` properties. That needs system/root and can disable CarPlay.

---

## 5. How to do the full decompile yourself

The APK isn't in any of the uploaded zips (`mmi-car-apps-2.zip` is missing from the repo, so it
may have been in there). To pull it from the head unit:

```sh
adb shell pm path com.zjinnova.zlink          # e.g. package:/system/app/Tlink5/Tlink5.apk
adb pull /system/app/Tlink5/Tlink5.apk zlink.apk
adb shell ls /system/app/Tlink5/lib/arm64     # native libs (the projection stack is mostly in .so)
```

Then on your own machine:

```sh
jadx -d zlink-src zlink.apk                   # Java/Kotlin + AndroidManifest.xml
# Look first at:
#   AndroidManifest.xml          → every exported receiver/service/activity and its intent filters
#   com.zjinnova.android.zlink.features.service.DaemonService
#   receivers handling POWER_ON / OUT_DARK_* / caplay.ShowCarplay
#   anything that reads persist.zlink.disable.features (decode the bit mask)
```

Most of the actual CarPlay/AA protocol (iAP2, the AA wire protocol, H.264 decoding, Apple's
authentication chip handling) is usually in native `.so` libraries. You would need Ghidra for
that part, and a launcher doesn't need it.

Keep any decompiled zlink source **local**. Don't commit it to this public MIT repo: it's
Zjinnova's proprietary code, and only these notes (facts about its interfaces) belong here.
