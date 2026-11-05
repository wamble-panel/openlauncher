# Built-in AirPlay

MMI's AirPlay receiver: the iPhone's screen (shown only while parked) and its music,
over Wi-Fi. The iPhone sees the unit as **MMI**.

- Kotlin code: from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
  (package `io.github.jqssun.airplay`), without its own screens; MMI's full-screen view
  is `mmi/MirrorActivity.kt`, its link to MMI `MmiLink.kt`.
- Native libraries (UxPlay, FFmpeg, OpenSSL, libplist): built on this repository's
  `airplay` branch and published as `airplay-native.zip` on the `airplay-latest` release;
  CI unzips it into `src/main/jniLibs` before building MMI.
- Runs in its own process (`:airplay`).

**Licence.** This code is GPL-3.0 (see `LICENSE` here), so the MMI app that contains it is
distributed under GPL-3.0 as a whole. MMI's own source files keep their MIT licence.
