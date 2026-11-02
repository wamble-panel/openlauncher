#!/bin/sh
# MMI setup for the TS10 (Allwinner A133) over USB debugging. Everything here can be
# undone with mmi-undo.sh; nothing is deleted from the unit.
#   1. Backup of the vendor apps and settings to ./mmi-backup (do this before removing anything)
#   2. MMI compiled fully to machine code (the A133's small cores run it faster from the first start)
#   3. MMI as home, its permissions, kept awake
#   4. Lighter system for the A133: shorter animations, no idle Wi-Fi/Bluetooth scanning
#   5. Window mode for CarPlay / Android Auto inside MMI's frame
#   6. (asks) Disable the vendor apps MMI already replaces
set -e
MMI=com.openlauncher.app
command -v adb >/dev/null || { echo "adb not found: install Android platform-tools first."; exit 1; }
adb wait-for-device
echo "Unit: $(adb shell getprop ro.product.model) / Android $(adb shell getprop ro.build.version.release)"

echo "== 1. Backup"
B=mmi-backup/$(date +%Y%m%d-%H%M%S); mkdir -p "$B/apps"
adb shell getprop > "$B/props.txt"
for t in global system secure; do adb shell settings list $t > "$B/settings-$t.txt"; done
adb shell pm list packages -f > "$B/packages.txt"
for p in $(adb shell pm list packages | tr -d '\r' | sed 's/package://' | grep -E '^(com\.tw\.|com\.dofun\.|com\.zjinnova\.|android\.tw\.)'); do
  for path in $(adb shell pm path "$p" | tr -d '\r' | sed 's/package://'); do
    adb pull "$path" "$B/apps/$p-$(basename "$path")" >/dev/null 2>&1 && echo "  saved $p"
  done
done
for t in global system secure; do adb shell settings list $t | tr -d '\r' > "$B/before-$t.txt"; done

echo "== 2. Compile MMI fully (takes a minute)"
adb shell cmd package compile -m speed -f $MMI

echo "== 3. Home, permissions, kept awake"
adb shell cmd package set-home-activity $MMI/.MainActivity || true
for perm in WRITE_SECURE_SETTINGS READ_CALL_LOG READ_CONTACTS CALL_PHONE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO READ_EXTERNAL_STORAGE; do
  adb shell pm grant $MMI android.permission.$perm 2>/dev/null || true
done
adb shell appops set $MMI WRITE_SETTINGS allow
adb shell appops set $MMI SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +$MMI >/dev/null
adb shell cmd notification allow_listener $MMI/com.openlauncher.app.service.MediaListenerService 2>/dev/null || true

echo "== 4. Lighter system for the A133"
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0

echo "== 5. Window mode for CarPlay / Android Auto inside MMI"
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1

echo "== 6. Vendor apps MMI replaces (vendor home, CarMate, weather, video)"
printf "Disable them now? They can be switched back with mmi-undo.sh [y/N] "; read a
if [ "$a" = "y" ] || [ "$a" = "Y" ]; then
  for p in com.dofun.variety com.dofun.carassistant.car com.dofun.dofunweather.main com.tw.video com.tw.video.theme; do
    adb shell pm disable-user --user 0 $p >/dev/null 2>&1 && echo "  disabled $p"
  done
fi
echo "Done. Backup in $B. Restart the unit."
