#!/bin/sh
# MMI laptop session (Mac/Linux). Put this file in the platform-tools folder
# (next to adb), then in Terminal:  sh mmi-session.sh
# It asks before every change.
#   1) Backup of the unit's system (read-only, nothing on the unit changes)
#   2) MMI: permissions, full pre-compile, speed settings
#   3) Optional: silent updates, Opel boot animation
#   4) Optional: vendor bloat switched OFF (disabled, not deleted)
# Car apps (radio, Bluetooth, steering keys, camera, CAN box) are never touched.
# Undo for step 4: sh mmi-session-undo.sh
PKG=com.openlauncher.app
cd "$(dirname "$0")" || exit 1
if [ -x ./adb ]; then ADB=./adb; elif command -v adb >/dev/null; then ADB=adb; else
  echo "adb not found. Put this file in the platform-tools folder and run it again."; exit 1
fi
ask() { printf "%s [y/n] " "$1"; read -r a; [ "$a" = y ] || [ "$a" = Y ]; }
OUT=mmi-backup; [ -e "$OUT" ] && OUT=mmi-backup-2; [ -e "$OUT" ] && OUT=mmi-backup-3

echo "[1/6] Connecting to the unit..."
$ADB kill-server >/dev/null 2>&1; $ADB start-server >/dev/null 2>&1; $ADB wait-for-device; $ADB devices
printf 'If the unit shows "unauthorized", tap Allow on its screen first, then press Enter. '; read -r _

echo "[2/6] Checking for root access..."
$ADB root >/dev/null 2>&1; sleep 4; $ADB wait-for-device
if $ADB shell id | grep -q 'uid=0'; then ROOT=1; echo "  Root: YES - full system backup"
else ROOT=0; echo "  Root: no - apps and settings backup only. Everything else still works."; fi

echo "[3/6] Backup to $(pwd)/$OUT (read-only: nothing on the unit changes). Needs about 5 GB free."
mkdir -p "$OUT"
$ADB shell getprop > "$OUT/getprop.txt"
$ADB shell cat /proc/partitions > "$OUT/partitions.txt"
$ADB shell "ls -l /dev/block/by-name" > "$OUT/by-name.txt" 2>&1
$ADB shell pm list packages -f > "$OUT/packages.txt"
for t in system global secure; do $ADB shell "settings list $t" > "$OUT/settings-$t.txt"; done
$ADB pull /system/build.prop "$OUT/build.prop" >/dev/null 2>&1
if [ "$ROOT" = 1 ]; then
  echo "  Copying each system partition (10-30 minutes, do not unplug)..."
  for p in $($ADB exec-out "ls /dev/block/by-name" | tr -d '\r'); do
    # Personal data and storage partitions are skipped: large, not needed to restore the system.
    case "$p" in userdata|UDISK|cache|media|sdcard|data) echo "    skip $p (personal data)"; continue ;; esac
    echo "    $p"
    $ADB exec-out "dd if=/dev/block/by-name/$p bs=4M 2>/dev/null" > "$OUT/$p.img"
  done
  boot=$(ls "$OUT"/boot*.img 2>/dev/null | head -1)
  if [ -z "$boot" ] || [ "$(wc -c < "$boot")" -lt 1000000 ]; then
    echo "  The partition backup looks incomplete. Stopping here so nothing is changed."
    echo "  Send a photo of this window to Claude."; exit 1
  fi
else
  echo "  Saving system apps, framework and settings..."
  for d in /system/app /system/priv-app /system/framework /vendor/app; do
    $ADB pull "$d" "$OUT/$(echo "$d" | tr / -)" >/dev/null 2>&1
  done
fi
echo "  Backup done. Copy the $OUT folder to a cloud drive or USB disk and keep it."
ask "Continue with the MMI setup (all reversible)?" || { echo "Stopped after the backup. Nothing on the unit was changed."; exit 0; }

echo "[4/6] MMI: permissions, pre-compile, speed settings"
for g in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CONTACTS READ_CALL_LOG CALL_PHONE READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE WRITE_SECURE_SETTINGS; do
  $ADB shell pm grant $PKG android.permission.$g >/dev/null 2>&1
done
$ADB shell appops set $PKG WRITE_SETTINGS allow
$ADB shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
$ADB shell cmd notification allow_listener $PKG/$PKG.service.MediaListenerService
$ADB shell cmd package set-home-activity $PKG/.MainActivity
$ADB shell dumpsys deviceidle whitelist +$PKG >/dev/null
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 0.5; done
$ADB shell settings put global wifi_scan_always_enabled 0
$ADB shell settings put global ble_scan_always_enabled 0
echo "  Pre-compiling MMI (1-2 minutes)..."
$ADB shell cmd package compile -m speed -f $PKG

echo "[5/6] Optional extras"
if ask "Silent MMI updates (MMI updates itself while parked; turn off later in MMI Settings)?"; then
  $ADB shell dpm set-device-owner $PKG/.AdminReceiver || echo "  Not enabled: a Google account is signed in on the unit. Remove it in Android Settings > Accounts and run this again."
fi
if [ "$ROOT" = 1 ] && [ -f opel-bootanimation-1280x800.zip ] && ask "Opel boot animation at start-up (the current one is kept as a backup)?"; then
  $ADB shell "cp -n /data/local/bootanimation.zip /data/local/bootanimation.zip.bak" >/dev/null 2>&1
  $ADB push opel-bootanimation-1280x800.zip /data/local/bootanimation.zip
  $ADB shell "chmod 644 /data/local/bootanimation.zip"
fi

echo "[6/6] Vendor bloat"
if ask "Switch OFF the apps a car doesn't need (store, weather, Facebook, YouTube, Huawei, theme packs - undo anytime)?"; then
  for a in com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube \
    yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic \
    com.android.wallpapercropper com.huawei.dmsdpdevice com.huawei.hisight com.huawei.nearby \
    com.android.theme.color.black com.android.theme.color.cinnamon com.android.theme.color.green \
    com.android.theme.color.ocean com.android.theme.color.orchid com.android.theme.color.purple \
    com.android.theme.color.space com.android.theme.font.notoserifsource \
    com.android.theme.icon.roundedrect com.android.theme.icon.square com.android.theme.icon.squircle com.android.theme.icon.teardrop \
    com.android.theme.icon_pack.circular.themepicker com.android.theme.icon_pack.circular.settings \
    com.android.theme.icon_pack.circular.systemui com.android.theme.icon_pack.circular.android \
    com.android.theme.icon_pack.filled.themepicker com.android.theme.icon_pack.filled.settings \
    com.android.theme.icon_pack.filled.systemui com.android.theme.icon_pack.filled.android \
    com.android.theme.icon_pack.rounded.settings com.android.theme.icon_pack.rounded.systemui com.android.theme.icon_pack.rounded.android; do
    $ADB shell pm disable-user --user 0 $a >/dev/null 2>&1 && echo "  off: $a"
  done
fi

echo "Done. Turn the ignition off and on. MMI starts as the home screen."
echo "Kept on purpose: every car app (com.tw.*), CarSetting, the vendor home, Chrome, Maps, Play Store."
