@echo off
rem ============================================================================
rem  MMI laptop session (Windows). Run from the folder that holds adb.exe.
rem  Does, in order:  1) full backup of the unit   2) MMI set up with every
rem  permission, pre-compiled, silent updates   3) vendor bloat switched OFF
rem  (not deleted; mmi-session-undo.bat turns it back on).
rem  Needs: USB debugging on in the unit's Android settings (Developer options).
rem ============================================================================
setlocal
set PKG=com.openlauncher.app
set OUT=mmi-backup-%date:~-4%%date:~3,2%%date:~0,2%
if not exist adb.exe (
  echo adb.exe not found. Unzip platform-tools and run this script from that folder.
  pause & exit /b 1
)

echo.
echo [1/5] Connecting to the unit...
adb kill-server >nul 2>&1
adb devices
echo If the unit shows "unauthorized", tap "Allow" on the screen, then press a key.
pause

echo.
echo [2/5] Trying root over ADB (test-keys build)...
adb root >nul 2>&1
timeout /t 3 >nul
adb shell id > "%TEMP%\mmi-id.txt"
findstr /c:"uid=0" "%TEMP%\mmi-id.txt" >nul && (set ROOT=1 & echo   Root: YES) || (set ROOT=0 & echo   Root: no - backup will be partial, the rest still works)

echo.
echo [3/5] Backup to %OUT%  (this is the safety net - do not skip)
mkdir "%OUT%" 2>nul
adb shell getprop > "%OUT%\getprop.txt"
adb shell cat /proc/partitions > "%OUT%\partitions.txt"
adb shell "ls -l /dev/block/by-name" > "%OUT%\by-name.txt" 2>&1
adb shell pm list packages -f > "%OUT%\packages.txt"
adb shell "settings list system" > "%OUT%\settings-system.txt"
adb shell "settings list global" > "%OUT%\settings-global.txt"
adb shell "settings list secure" > "%OUT%\settings-secure.txt"
adb pull /system/build.prop "%OUT%\build.prop" >nul
adb pull /system/etc "%OUT%\system-etc" >nul 2>&1
adb pull /vendor/etc "%OUT%\vendor-etc" >nul 2>&1
if "%ROOT%"=="1" (
  echo   Dumping every partition (several GB, 10-30 minutes)...
  for /f "tokens=*" %%p in ('adb shell "ls /dev/block/by-name"') do (
    echo     %%p
    adb exec-out "dd if=/dev/block/by-name/%%p bs=4M 2>/dev/null" > "%OUT%\%%p.img"
  )
) else (
  echo   Without root: saving the system apps and framework only.
  adb pull /system/app "%OUT%\system-app" >nul 2>&1
  adb pull /system/priv-app "%OUT%\system-priv-app" >nul 2>&1
  adb pull /system/framework "%OUT%\framework" >nul 2>&1
  adb pull /vendor/app "%OUT%\vendor-app" >nul 2>&1
)
echo   Backup done. Copy the %OUT% folder somewhere safe (cloud or external drive).

echo.
echo [4/5] MMI: permissions, pre-compile, silent updates, speed settings
adb shell pm grant %PKG% android.permission.ACCESS_FINE_LOCATION
adb shell pm grant %PKG% android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant %PKG% android.permission.READ_CONTACTS
adb shell pm grant %PKG% android.permission.READ_CALL_LOG
adb shell pm grant %PKG% android.permission.CALL_PHONE
adb shell pm grant %PKG% android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant %PKG% android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
adb shell appops set %PKG% WRITE_SETTINGS allow
adb shell appops set %PKG% REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_listener %PKG%/%PKG%.service.MediaListenerService
adb shell cmd package set-home-activity %PKG%/.MainActivity
adb shell dumpsys deviceidle whitelist +%PKG%
rem Snappier animations, no idle scanning
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
rem Vendor pop-ups MMI replaces: climate window, door and radar pop-ups
adb shell settings put system AIR_POPUP_WINDOW 0
adb shell settings put system SYSTEM_DOOR2 1
adb shell settings put system SYSTEM_FRADAR 1
rem Full ahead-of-time compile: screens open without stutter
adb shell cmd package compile -m speed -f %PKG%
rem Silent updates (device owner). Fails if a Google account is signed in on the unit.
adb shell dpm set-device-owner %PKG%/.AdminReceiver || echo   Silent updates not enabled: remove accounts in Android Settings and rerun this step.

echo.
echo [5/5] Switching OFF vendor bloat (nothing deleted; undo with mmi-session-undo.bat)
for %%a in (
  com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube
  yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic com.android.bookmarkprovider
  com.android.wallpapercropper com.huawei.dmsdpdevice com.huawei.hisight com.huawei.nearby
  com.android.theme.color.black com.android.theme.color.cinnamon com.android.theme.color.green
  com.android.theme.color.ocean com.android.theme.color.orchid com.android.theme.color.purple
  com.android.theme.color.space com.android.theme.font.notoserifsource
  com.android.theme.icon.roundedrect com.android.theme.icon.square com.android.theme.icon.squircle com.android.theme.icon.teardrop
  com.android.theme.icon_pack.circular.themepicker com.android.theme.icon_pack.circular.settings
  com.android.theme.icon_pack.circular.systemui com.android.theme.icon_pack.circular.android
  com.android.theme.icon_pack.filled.themepicker com.android.theme.icon_pack.filled.settings
  com.android.theme.icon_pack.filled.systemui com.android.theme.icon_pack.filled.android
  com.android.theme.icon_pack.rounded.settings com.android.theme.icon_pack.rounded.systemui com.android.theme.icon_pack.rounded.android
) do adb shell pm disable-user --user 0 %%a >nul 2>&1 && echo   off: %%a

echo.
echo Done. Restart the unit (ignition off and on). MMI starts as the home screen.
echo Kept ON on purpose: all com.tw.* car apps, CarSetting, the vendor home (Theme), Chrome, Maps, Play.
pause
