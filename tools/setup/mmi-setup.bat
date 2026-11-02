@echo off
rem MMI setup for the TS10 (Allwinner A133) over USB debugging. Undo: mmi-undo.bat. Nothing is deleted.
set MMI=com.openlauncher.app
where adb >nul 2>nul || (echo adb not found: install Android platform-tools first. & pause & exit /b 1)
adb wait-for-device
echo == 1. Backup
for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set STAMP=%%d
set B=mmi-backup\%STAMP%
mkdir "%B%\apps" 2>nul
adb shell getprop > "%B%\props.txt"
for %%t in (global system secure) do adb shell settings list %%t > "%B%\settings-%%t.txt"
adb shell pm list packages -f > "%B%\packages.txt"
powershell -NoProfile -Command "$pk = adb shell pm list packages | %% { $_.Trim() -replace 'package:','' } | ? { $_ -match '^(com\.tw\.|com\.dofun\.|com\.zjinnova\.|android\.tw\.)' }; foreach ($p in $pk) { foreach ($path in (adb shell pm path $p | %% { $_.Trim() -replace 'package:','' })) { adb pull $path ('%B%\apps\' + $p + '-' + (Split-Path $path -Leaf)) | Out-Null; Write-Host ('  saved ' + $p) } }"
echo == 2. Compile MMI fully (takes a minute)
adb shell cmd package compile -m speed -f %MMI%
echo == 3. Home, permissions, kept awake
adb shell cmd package set-home-activity %MMI%/.MainActivity
for %%p in (WRITE_SECURE_SETTINGS READ_CALL_LOG READ_CONTACTS CALL_PHONE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION RECORD_AUDIO READ_EXTERNAL_STORAGE) do adb shell pm grant %MMI% android.permission.%%p 2>nul
adb shell appops set %MMI% WRITE_SETTINGS allow
adb shell appops set %MMI% SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +%MMI% >nul
adb shell cmd notification allow_listener %MMI%/com.openlauncher.app.service.MediaListenerService 2>nul
echo == 4. Lighter system for the A133
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
echo == 4b. Vendor climate and door pop-ups off (MMI shows its own)
adb shell settings put system AIR_POPUP_WINDOW 0
adb shell settings put system SYSTEM_DOOR2 1
echo == 5. Window mode for CarPlay / Android Auto inside MMI
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
echo == 6. Vendor apps MMI replaces (vendor home, CarMate, weather, video)
set /p A=Disable them now? They can be switched back with mmi-undo.bat [y/N] 
if /i "%A%"=="y" for %%p in (com.dofun.variety com.dofun.carassistant.car com.dofun.dofunweather.main com.tw.video com.tw.video.theme) do adb shell pm disable-user --user 0 %%p
echo Done. Backup in %B%. Restart the unit.
pause
