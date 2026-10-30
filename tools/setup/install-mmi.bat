@echo off
rem Installs MMI on the head unit and allows every permission in one go.
rem Needs: adb (Android platform-tools) and USB/Wi-Fi debugging on the unit.
rem Usage: drag the APK onto this file, or run:  install-mmi.bat app-release.apk
setlocal
set PKG=com.openlauncher.app
set APK=%~1
if "%APK%"=="" set APK=app-release.apk

adb devices
adb install -r -g "%APK%" || goto :fail

adb shell appops set %PKG% MANAGE_EXTERNAL_STORAGE allow
adb shell appops set %PKG% WRITE_SETTINGS allow
adb shell cmd notification allow_listener %PKG%/%PKG%.service.MediaListenerService
adb shell cmd role add-role-holder android.app.role.HOME %PKG% 0
adb shell cmd package set-home-activity %PKG%/.MainActivity
adb shell dumpsys deviceidle whitelist +%PKG%
rem Lets MMI apply its speed settings itself (Settings > Unit cleanup & speed)
adb shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
rem Pre-compile MMI fully so every screen opens without stutter
adb shell cmd package compile -m speed -f %PKG%
rem Silent updates: as device owner MMI installs its own updates with no prompt (only while parked).
rem Android refuses this while accounts are signed in on the unit; MMI Settings can turn it off again.
adb shell dpm set-device-owner %PKG%/.AdminReceiver || echo Silent updates not enabled - sign out of accounts on the unit and run this again.
adb shell am start -n %PKG%/.MainActivity

echo.
echo Done. MMI is installed, set as home screen and has every permission.
pause
exit /b 0

:fail
echo Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, run:  adb uninstall %PKG%   and try again.
pause
exit /b 1
