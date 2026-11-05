# MMI AirPlay (native part)

MMI's built-in AirPlay (iPhone screen mirroring while parked, and music) uses the
receiver from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server),
built on [UxPlay](https://github.com/FDH2/UxPlay); GPL-3.0 like the original.

This branch builds only the native libraries (UxPlay, FFmpeg, OpenSSL, libplist) and
publishes them as `airplay-native.zip` on the `airplay-latest` release
(`.github/workflows/mmi-airplay.yml`, no secrets). MMI's own build downloads that zip into
its `airplay` module, which carries the receiver's Kotlin code; the app here is only
the original receiver and is not shipped.
