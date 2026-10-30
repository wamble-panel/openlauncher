@echo off
rem ============================================================================
rem  MMI laptop session (Windows). Put this file in the platform-tools folder
rem  (next to adb.exe) and double-click it. It asks before every change.
rem    1) Backup of the unit's system (read-only, nothing on the unit changes)
rem    2) MMI: permissions, full pre-compile, speed settings
rem    3) Optional: silent updates, Opel boot animation
rem    4) Optional: vendor bloat switched OFF (disabled, not deleted)
rem  Car apps (radio, Bluetooth, steering keys, camera, CAN box) are never touched.
rem  Undo for step 4: mmi-session-undo.bat.
rem ============================================================================
setlocal EnableExtensions
set "PKG=com.openlauncher.app"
cd /d "%~dp0"
if not exist adb.exe (
  echo adb.exe not found. Put this file in the platform-tools folder and run it again.
  pause & exit /b 1
)
set "OUT=mmi-backup"
if exist "%OUT%" set "OUT=mmi-backup-2"
if exist "%OUT%" set "OUT=mmi-backup-3"

echo.
echo [1/6] Connecting to the unit...
adb kill-server >nul 2>&1
adb start-server >nul 2>&1
adb wait-for-device
adb devices
echo If the unit shows "unauthorized", tap Allow on its screen first.
pause

echo.
echo [2/6] Checking for root access...
adb root >nul 2>&1
timeout /t 4 >nul
adb wait-for-device
set "ROOT=0"
adb shell id | findstr /c:"uid=0" >nul && set "ROOT=1"
if "%ROOT%"=="1" (echo   Root: YES - full system backup) else (echo   Root: no - apps and settings backup only. Everything else still works.)

echo.
echo [3/6] Backup to "%CD%\%OUT%"  (read-only: nothing on the unit changes)
echo   Needs about 5 GB free on this laptop.
mkdir "%OUT%" 2>nul
adb shell getprop > "%OUT%\getprop.txt"
adb shell cat /proc/partitions > "%OUT%\partitions.txt"
adb shell "ls -l /dev/block/by-name" > "%OUT%\by-name.txt" 2>&1
adb shell pm list packages -f > "%OUT%\packages.txt"
adb shell "settings list system" > "%OUT%\settings-system.txt"
adb shell "settings list global" > "%OUT%\settings-global.txt"
adb shell "settings list secure" > "%OUT%\settings-secure.txt"
adb pull /system/build.prop "%OUT%\build.prop" >nul 2>&1
if "%ROOT%"=="1" goto dumpparts
echo   Saving system apps, framework and settings...
adb pull /system/app "%OUT%\system-app" >nul 2>&1
adb pull /system/priv-app "%OUT%\system-priv-app" >nul 2>&1
adb pull /system/framework "%OUT%\framework" >nul 2>&1
adb pull /vendor/app "%OUT%\vendor-app" >nul 2>&1
goto backupdone

:dumpparts
echo   Copying each system partition (10-30 minutes, do not unplug)...
rem Personal data and storage partitions are skipped: they are large and not needed to restore the system.
for /f "tokens=*" %%p in ('adb exec-out "ls /dev/block/by-name"') do call :dumpone %%p
goto checkbackup

:dumpone
set "P=%~1"
for %%s in (userdata UDISK cache media sdcard data) do if /i "%P%"=="%%s" (echo     skip %P% ^(personal data^) & goto :eof)
echo     %P%
adb exec-out "dd if=/dev/block/by-name/%P% bs=4M 2>/dev/null" > "%OUT%\%P%.img"
goto :eof

:checkbackup
set "OK=1"
for %%f in ("%OUT%\boot*.img") do if %%~zf LSS 1000000 set "OK=0"
if not exist "%OUT%\boot*.img" set "OK=0"
if "%OK%"=="0" (
  echo.
  echo   The partition backup looks incomplete. Stopping here so nothing is changed.
  echo   Send a photo of this window to Claude.
  pause & exit /b 1
)

:backupdone
echo   Backup done. Copy the "%OUT%" folder to a cloud drive or USB disk and keep it.
echo.
choice /m "Continue with the MMI setup (all reversible)"
if errorlevel 2 (echo Stopped after the backup. Nothing on the unit was changed. & pause & exit /b 0)

echo.
echo [4/6] MMI: permissions, pre-compile, speed settings
for %%g in (ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CONTACTS READ_CALL_LOG CALL_PHONE READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE WRITE_SECURE_SETTINGS) do adb shell pm grant %PKG% android.permission.%%g >nul 2>&1
adb shell appops set %PKG% WRITE_SETTINGS allow
adb shell appops set %PKG% REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_listener %PKG%/%PKG%.service.MediaListenerService
adb shell cmd package set-home-activity %PKG%/.MainActivity
adb shell dumpsys deviceidle whitelist +%PKG% >nul
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
echo   Pre-compiling MMI (1-2 minutes)...
adb shell cmd package compile -m speed -f %PKG%

echo.
echo [5/6] Optional extras
choice /m "Silent MMI updates (MMI updates itself while parked; turn off later in MMI Settings)"
if errorlevel 2 goto noowner
adb shell dpm set-device-owner %PKG%/.AdminReceiver || echo   Not enabled: a Google account is signed in on the unit. Remove it in Android Settings, Accounts, and run this again.
:noowner
if not exist opel-bootanimation-1280x800.zip goto noboot
if not "%ROOT%"=="1" goto noboot
choice /m "Opel boot animation at start-up (the current one is kept as a backup)"
if errorlevel 2 goto noboot
adb shell "cp -n /data/local/bootanimation.zip /data/local/bootanimation.zip.bak" >nul 2>&1
adb push opel-bootanimation-1280x800.zip /data/local/bootanimation.zip
adb shell "chmod 644 /data/local/bootanimation.zip"
:noboot

echo.
echo [6/6] Vendor bloat
choice /m "Switch OFF the apps a car doesn't need (store, weather, Facebook, YouTube, Huawei, theme packs - undo anytime)"
if errorlevel 2 goto finish
for %%a in (
  com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube
  yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic
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

:finish
echo.
echo Done. Turn the ignition off and on. MMI starts as the home screen.
echo Kept on purpose: every car app (com.tw.*), CarSetting, the vendor home, Chrome, Maps, Play Store.
pause
