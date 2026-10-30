#!/bin/sh
# Collects everything needed to replace the vendor software with MMI (read-only).
# Needs adb and USB/Wi-Fi debugging on the unit.
OUT=unit-info
rm -rf "$OUT"; mkdir -p "$OUT"
adb devices
echo "[1/6] Unit identity"
adb shell getprop > "$OUT/getprop.txt"
adb shell cat /proc/cpuinfo > "$OUT/cpuinfo.txt"
adb shell cat /proc/meminfo > "$OUT/meminfo.txt"
adb shell uname -a > "$OUT/kernel.txt"
adb shell id > "$OUT/id.txt"
adb shell "su -c id" > "$OUT/root.txt" 2>&1
echo "[2/6] Installed apps and services"
adb shell pm list packages -f > "$OUT/packages.txt"
adb shell service list > "$OUT/services.txt"
adb shell dumpsys activity services > "$OUT/running-services.txt"
adb shell dumpsys media.camera > "$OUT/camera.txt"
adb shell dumpsys audio > "$OUT/audio.txt"
adb shell dumpsys bluetooth_manager > "$OUT/bluetooth.txt"
adb shell dumpsys input > "$OUT/input.txt"
echo "[3/6] Hardware"
adb shell ls -l /dev > "$OUT/dev.txt"
adb shell "ls -l /dev/snd /dev/video* /sys/class/video4linux" > "$OUT/dev-av.txt" 2>&1
adb shell "ls -lR /vendor/lib/hw /vendor/lib64/hw /system/lib/hw /system/lib64/hw" > "$OUT/hal-libs.txt" 2>&1
echo "[4/6] Startup definitions"
adb pull /system/etc/init "$OUT/init-system"
adb pull /vendor/etc/init "$OUT/init-vendor"
adb pull /system/build.prop "$OUT/build.prop"
adb pull /vendor/build.prop "$OUT/vendor-build.prop"
echo "[5/6] Apps and framework"
for d in /system/app /system/priv-app /vendor/app /product/app /product/priv-app /system_ext/app /system_ext/priv-app; do
  adb pull "$d" "$OUT/apk$(echo "$d" | tr / -)"
done
adb pull /system/framework "$OUT/framework"
echo "[6/6] Log (do reverse, radio, steering keys, a call before running this)"
adb logcat -d -v time > "$OUT/logcat.txt"
zip -qr unit-info.zip "$OUT" && echo "Done: unit-info.zip"
