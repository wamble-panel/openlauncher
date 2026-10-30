@echo off
rem Collects everything needed to replace the vendor software with MMI:
rem system/vendor apps, framework, driver/service definitions, device nodes,
rem properties and logs. Read-only: nothing on the unit is changed.
rem Needs: adb (Android platform-tools) and USB/Wi-Fi debugging on the unit.
setlocal
set OUT=unit-info
if exist %OUT% rmdir /s /q %OUT%
mkdir %OUT%
adb devices

echo [1/6] Unit identity...
adb shell getprop > %OUT%\getprop.txt
adb shell cat /proc/cpuinfo > %OUT%\cpuinfo.txt
adb shell cat /proc/meminfo > %OUT%\meminfo.txt
adb shell uname -a > %OUT%\kernel.txt
adb shell id > %OUT%\id.txt
adb shell "su -c id" > %OUT%\root.txt 2>&1

echo [2/6] Installed apps and services...
adb shell pm list packages -f > %OUT%\packages.txt
adb shell service list > %OUT%\services.txt
adb shell dumpsys activity services > %OUT%\running-services.txt
adb shell dumpsys media.camera > %OUT%\camera.txt
adb shell dumpsys audio > %OUT%\audio.txt
adb shell dumpsys bluetooth_manager > %OUT%\bluetooth.txt
adb shell dumpsys input > %OUT%\input.txt

echo [3/6] Hardware: serial ports, video and audio devices...
adb shell ls -l /dev > %OUT%\dev.txt
adb shell ls -l /dev/snd /dev/video* /sys/class/video4linux > %OUT%\dev-av.txt 2>&1
adb shell ls -lR /vendor/lib/hw /vendor/lib64/hw /system/lib/hw /system/lib64/hw > %OUT%\hal-libs.txt 2>&1

echo [4/6] Startup definitions (which vendor daemons run)...
adb pull /system/etc/init %OUT%\init-system
adb pull /vendor/etc/init %OUT%\init-vendor
adb pull /system/build.prop %OUT%\build.prop
adb pull /vendor/build.prop %OUT%\vendor-build.prop

echo [5/6] Vendor and system apps + framework (largest step)...
adb pull /system/app %OUT%\apk-system-app
adb pull /system/priv-app %OUT%\apk-system-priv-app
adb pull /vendor/app %OUT%\apk-vendor-app
adb pull /product/app %OUT%\apk-product-app
adb pull /product/priv-app %OUT%\apk-product-priv-app
adb pull /system_ext/app %OUT%\apk-system-ext-app
adb pull /system_ext/priv-app %OUT%\apk-system-ext-priv-app
adb pull /system/framework %OUT%\framework

echo [6/6] Log of the last minutes (do reverse, radio, steering keys, a call BEFORE running this)...
adb logcat -d -v time > %OUT%\logcat.txt

echo Packing...
tar -a -c -f unit-info.zip %OUT%
echo.
echo Done: unit-info.zip  (send it over; if it is too large, send the folders apk-* and framework separately)
pause
