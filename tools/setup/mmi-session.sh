#!/bin/sh
# MMI laptop session (Mac/Linux). Run from the folder that holds adb:  ./mmi-session.sh
# 1) full backup  2) MMI permissions, pre-compile, silent updates  3) vendor bloat OFF (undo: mmi-session-undo.sh)
PKG=com.openlauncher.app
OUT=mmi-backup-$(date +%Y%m%d)
cd "$(dirname "$0")"
command -v ./adb >/dev/null && ADB=./adb || ADB=adb
echo "[1/5] Connecting..."; $ADB kill-server >/dev/null 2>&1; $ADB devices
echo 'If the unit shows "unauthorized", tap Allow on the screen, then press Enter.'; read _
echo "[2/5] Root over ADB..."; $ADB root >/dev/null 2>&1; sleep 3
if $ADB shell id | grep -q uid=0; then ROOT=1; echo "  Root: YES"; else ROOT=0; echo "  Root: no - partial backup"; fi
echo "[3/5] Backup to $OUT"; mkdir -p "$OUT"
$ADB shell getprop > "$OUT/getprop.txt"; $ADB shell cat /proc/partitions > "$OUT/partitions.txt"
$ADB shell "ls -l /dev/block/by-name" > "$OUT/by-name.txt" 2>&1; $ADB shell pm list packages -f > "$OUT/packages.txt"
for t in system global secure; do $ADB shell "settings list $t" > "$OUT/settings-$t.txt"; done
$ADB pull /system/build.prop "$OUT/build.prop" >/dev/null; $ADB pull /system/etc "$OUT/system-etc" >/dev/null 2>&1; $ADB pull /vendor/etc "$OUT/vendor-etc" >/dev/null 2>&1
if [ "$ROOT" = 1 ]; then
  echo "  Dumping every partition (several GB, 10-30 minutes)..."
  for p in $($ADB shell "ls /dev/block/by-name" | tr -d '\r'); do echo "    $p"; $ADB exec-out "dd if=/dev/block/by-name/$p bs=4M 2>/dev/null" > "$OUT/$p.img"; done
else
  for d in /system/app /system/priv-app /system/framework /vendor/app; do $ADB pull "$d" "$OUT/$(echo $d | tr / -)" >/dev/null 2>&1; done
fi
echo "  Backup done. Copy $OUT somewhere safe."
echo "[4/5] MMI setup"
$ADB shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
$ADB shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
$ADB shell pm grant $PKG android.permission.READ_CONTACTS
$ADB shell pm grant $PKG android.permission.READ_CALL_LOG
$ADB shell pm grant $PKG android.permission.CALL_PHONE
$ADB shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
$ADB shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE
$ADB shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
$ADB shell appops set $PKG WRITE_SETTINGS allow
$ADB shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
$ADB shell cmd notification allow_listener $PKG/$PKG.service.MediaListenerService
$ADB shell cmd package set-home-activity $PKG/.MainActivity
$ADB shell dumpsys deviceidle whitelist +$PKG
$ADB shell settings put global window_animation_scale 0.5
$ADB shell settings put global transition_animation_scale 0.5
$ADB shell settings put global animator_duration_scale 0.5
$ADB shell settings put global wifi_scan_always_enabled 0
$ADB shell settings put global ble_scan_always_enabled 0
$ADB shell settings put system AIR_POPUP_WINDOW 0
$ADB shell settings put system SYSTEM_DOOR2 1
$ADB shell settings put system SYSTEM_FRADAR 1
$ADB shell cmd package compile -m speed -f $PKG
$ADB shell dpm set-device-owner $PKG/.AdminReceiver  || echo   Silent updates not enabled: remove accounts in Android Settings and rerun this step.
echo "[5/5] Vendor bloat OFF (undo: mmi-session-undo.sh)"
for a in com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic com.android.bookmarkprovider com.android.wallpapercropper com.huawei.dmsdpdevice com.huawei.hisight com.huawei.nearby com.android.theme.color.black com.android.theme.color.cinnamon com.android.theme.color.green com.android.theme.color.ocean com.android.theme.color.orchid com.android.theme.color.purple com.android.theme.color.space com.android.theme.font.notoserifsource com.android.theme.icon.roundedrect com.android.theme.icon.square com.android.theme.icon.squircle com.android.theme.icon.teardrop com.android.theme.icon_pack.circular.themepicker com.android.theme.icon_pack.circular.settings com.android.theme.icon_pack.circular.systemui com.android.theme.icon_pack.circular.android com.android.theme.icon_pack.filled.themepicker com.android.theme.icon_pack.filled.settings com.android.theme.icon_pack.filled.systemui com.android.theme.icon_pack.filled.android com.android.theme.icon_pack.rounded.settings com.android.theme.icon_pack.rounded.systemui com.android.theme.icon_pack.rounded.android; do $ADB shell pm disable-user --user 0 $a >/dev/null 2>&1 && echo "  off: $a"; done
echo "Done. Restart the unit. MMI starts as the home screen."
