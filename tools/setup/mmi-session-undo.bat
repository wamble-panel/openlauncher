@echo off
rem Undoes the MMI laptop session's reversible parts: turns the switched-off
rem apps back on, restores the vendor pop-ups and default animation speeds, and
rem removes MMI as device owner. MMI itself and the backup are left alone.
setlocal
set PKG=com.openlauncher.app
adb devices
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
) do adb shell pm enable %%a >nul 2>&1 && echo   on: %%a
adb shell settings put system AIR_POPUP_WINDOW 1
adb shell settings put system SYSTEM_DOOR2 0
adb shell settings put system SYSTEM_FRADAR 0
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell dpm remove-active-admin %PKG%/.AdminReceiver >nul 2>&1
echo Done. Restart the unit.
pause
