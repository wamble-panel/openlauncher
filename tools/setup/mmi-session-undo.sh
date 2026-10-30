#!/bin/sh
# Undoes the MMI laptop session: turns the switched-off apps back on, restores
# the default animation speeds and the original boot animation.
# Silent updates are turned off from MMI: Settings, "Silent updates: on (tap to turn off)".
cd "$(dirname "$0")" || exit 1
if [ -x ./adb ]; then ADB=./adb; else ADB=adb; fi
$ADB wait-for-device
for a in com.dofun.market com.dofun.dofunweather.main com.facebook.katana com.google.android.youtube \
  yuku.mp3recorder.full com.android.printspooler com.android.dreams.basic \
  com.android.wallpapercropper com.huawei.dmsdpdevice com.huawei.hisight com.huawei.nearby \
  com.android.theme.color.black com.android.theme.color.cinnamon com.android.theme.color.green \
  com.android.theme.color.ocean com.android.theme.color.orchid com.android.theme.color.purple \
  com.android.theme.color.space com.android.theme.font.notoserifsource \
  com.android.theme.icon.roundedrect com.android.theme.icon.square com.android.theme.icon.squircle com.android.theme.icon.teardrop \
  com.android.theme.icon_pack.circular.themepicker com.android.theme.icon_pack.circular.settings \
  com.android.theme.icon_pack.circular.systemui com.android.theme.icon_pack.circular.android \
  com.android.theme.icon_pack.filled.themepicker com.android.theme.icon_pack.filled.settings \
  com.android.theme.icon_pack.filled.systemui com.android.theme.icon_pack.filled.android \
  com.android.theme.icon_pack.rounded.settings com.android.theme.icon_pack.rounded.systemui com.android.theme.icon_pack.rounded.android; do
  $ADB shell pm enable $a >/dev/null 2>&1 && echo "  on: $a"
done
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 1; done
$ADB root >/dev/null 2>&1; $ADB wait-for-device
$ADB shell "[ -f /data/local/bootanimation.zip.bak ] && mv /data/local/bootanimation.zip.bak /data/local/bootanimation.zip && echo '  Original boot animation restored'"
echo "Done. Turn the ignition off and on."
