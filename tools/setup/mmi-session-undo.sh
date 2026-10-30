#!/bin/sh
# Undoes the reversible parts of the MMI laptop session.
PKG=com.openlauncher.app
cd "$(dirname "$0")"; command -v ./adb >/dev/null && ADB=./adb || ADB=adb
for a in com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic com.android.bookmarkprovider com.android.wallpapercropper com.huawei.dmsdpdevice com.huawei.hisight com.huawei.nearby com.android.theme.color.black com.android.theme.color.cinnamon com.android.theme.color.green com.android.theme.color.ocean com.android.theme.color.orchid com.android.theme.color.purple com.android.theme.color.space com.android.theme.font.notoserifsource com.android.theme.icon.roundedrect com.android.theme.icon.square com.android.theme.icon.squircle com.android.theme.icon.teardrop com.android.theme.icon_pack.circular.themepicker com.android.theme.icon_pack.circular.settings com.android.theme.icon_pack.circular.systemui com.android.theme.icon_pack.circular.android com.android.theme.icon_pack.filled.themepicker com.android.theme.icon_pack.filled.settings com.android.theme.icon_pack.filled.systemui com.android.theme.icon_pack.filled.android com.android.theme.icon_pack.rounded.settings com.android.theme.icon_pack.rounded.systemui com.android.theme.icon_pack.rounded.android; do $ADB shell pm enable $a >/dev/null 2>&1 && echo "  on: $a"; done
$ADB shell settings put system AIR_POPUP_WINDOW 1; $ADB shell settings put system SYSTEM_DOOR2 0; $ADB shell settings put system SYSTEM_FRADAR 0
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 1; done
$ADB shell dpm remove-active-admin $PKG/.AdminReceiver >/dev/null 2>&1
echo "Done. Restart the unit."
