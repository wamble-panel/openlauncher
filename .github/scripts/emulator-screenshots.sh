#!/usr/bin/env bash
# Installs the debug APK on a running emulator and captures screenshots of the
# first launch, the MBUX-themed home screen, and the edge-swipe volume overlay.
# Usage: emulator-screenshots.sh <apk> <output-dir>
set -uo pipefail

APK="$1"
OUT="$2"
PKG=com.openlauncher.app
mkdir -p "$OUT"

shot() {
  adb exec-out screencap -p > "$OUT/$1.png" && echo "saved $1.png"
}

# Typical head-unit density for a ~9" 1280x720 panel.
adb shell wm density 160
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell cmd package set-home-activity "$PKG/.MainActivity" || true

# 1. First launch, as a new user sees it.
adb shell am start -W -n "$PKG/.MainActivity"
sleep 10
shot 01-first-launch

# 2. Skip onboarding and select the MBUX theme by writing the app's DataStore
#    preferences file directly (protobuf PreferenceMap, encoded by hand).
adb shell am force-stop "$PKG"
python3 - "$OUT/prefs.pb" <<'PY'
import sys

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def ld(field, payload):
    return varint((field << 3) | 2) + varint(len(payload)) + payload

def entry(key, value):
    return ld(1, ld(1, key.encode()) + ld(2, value))

bool_true = varint((1 << 3) | 0) + varint(1)
def string(s):
    return ld(5, s.encode())

data = entry("onboarding_completed", bool_true) + entry("theme_id", string("mbux"))
open(sys.argv[1], "wb").write(data)
PY
adb push "$OUT/prefs.pb" /data/local/tmp/prefs.pb
adb shell "run-as $PKG mkdir -p files/datastore"
adb shell "cat /data/local/tmp/prefs.pb | run-as $PKG sh -c 'cat > files/datastore/launcher_settings.preferences_pb'"
rm -f "$OUT/prefs.pb"

adb shell am start -W -n "$PKG/.MainActivity"
sleep 12
shot 02-home-mbux

# 3. Edge-swipe volume: drag upward along the right edge, capture while the
#    overlay is still visible (it hides ~900 ms after the last step).
adb shell input swipe 1255 560 1255 160 700
shot 03-volume-gesture

# Diagnostics for anything that went wrong.
adb shell dumpsys activity activities | grep -m3 -E "mResumedActivity|topResumedActivity" > "$OUT/foreground.txt" || true
adb logcat -d -b crash > "$OUT/crash.log" || true
exit 0
