#!/bin/sh
# Undoes mmi-setup.sh: vendor apps back on, Android's default animations and scanning,
# no window mode, MMI's compiled code reset. MMI stays installed.
MMI=com.openlauncher.app
adb wait-for-device
for p in com.dofun.variety com.dofun.carassistant.car com.dofun.dofunweather.main com.tw.video com.tw.video.theme; do
  adb shell pm enable $p >/dev/null 2>&1 && echo "  enabled $p"
done
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings delete global wifi_scan_always_enabled
adb shell settings delete global ble_scan_always_enabled
adb shell settings delete global enable_freeform_support
adb shell settings delete global force_resizable_activities
adb shell cmd package compile --reset $MMI
echo "Done. Restart the unit."
