# MMI AirPlay

The AirPlay receiver for the MMI car head unit: iPhone screen mirroring (while parked)
and music. A fork of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server),
which is built on [UxPlay](https://github.com/FDH2/UxPlay); GPL-3.0 like the original.

It is a separate app (`com.openlauncher.airplay`); MMI starts it and shows it on its
Phone Link page. The iPhone sees it as **MMI**.

Built by `.github/workflows/mmi-airplay.yml`: compiled unsigned without secrets, then
signed with the MMI key in a separate job that runs none of this code. The APK is
`MMI-AirPlay.apk` on the `airplay-latest` release.
