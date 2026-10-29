#!/bin/sh
# Installs MMI on the head unit and allows every permission in one go.
# Needs: adb (Android platform-tools) and USB/Wi-Fi debugging on the unit.
# Usage: ./install-mmi.sh app-release.apk
set -e
PKG=com.openlauncher.app
APK=${1:-app-release.apk}

adb devices
adb install -r -g "$APK"
# The rest are best effort: older Android versions lack some of these commands.
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow || true
adb shell appops set $PKG WRITE_SETTINGS allow || true
adb shell cmd notification allow_listener $PKG/$PKG.service.MediaListenerService || true
adb shell cmd role add-role-holder android.app.role.HOME $PKG 0 || true
adb shell cmd package set-home-activity $PKG/.MainActivity || true
adb shell dumpsys deviceidle whitelist +$PKG || true
adb shell am start -n $PKG/.MainActivity
echo "Done. MMI is installed, set as home screen and has every permission."
