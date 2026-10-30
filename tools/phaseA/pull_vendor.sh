#!/usr/bin/env bash
# Phase A steps 1-2: pull the vendor layer from the head unit.
# Run on a PC on the same Wi-Fi as the unit, after `adb connect <ip>:5555`.
#
# READ-ONLY on the unit: it only runs pm / dumpsys / getprop / ls / cat / cmd package resolve-activity
# and `adb pull`. Nothing is installed, written, or changed on the unit.
# Every adb command is appended to docs/device-log.md.
#
#   ANDROID_SERIAL=192.168.1.50:5555 tools/phaseA/pull_vendor.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/device_dump"
LOG="$ROOT/docs/device-log.md"
FILTER='com\.tw|com\.dofun|openlauncher'
mkdir -p "$OUT/apps" "$OUT/system" "$OUT/dumpsys"

{ echo; echo "## $(date -u +%FT%TZ) pull_vendor.sh (serial: ${ANDROID_SERIAL:-default})"; } >> "$LOG"
adbx() {
  echo "- \`adb $*\`" >> "$LOG"
  adb "$@"
}

adbx get-state >/dev/null || { echo "No device. Run: adb connect <unit-ip>:5555" >&2; exit 1; }

# --- identity / props
adbx shell getprop > "$OUT/system/getprop.txt"
adbx pull /system/build.prop "$OUT/system/build.prop" || true
adbx shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  > "$OUT/system/current-home.txt" || true

# --- step 1: vendor packages (whole app dir, so splits + oat/odex/vdex come along)
adbx shell pm list packages -f > "$OUT/packages-all.txt"
grep -E "$FILTER" "$OUT/packages-all.txt" | tr -d '\r' > "$OUT/packages-vendor.txt" || true
while IFS= read -r line; do
  rest="${line#package:}"
  pkg="${rest##*=}"
  apk="${rest%=*}"
  dir="$(dirname "$apk")"
  echo "[pull] $pkg  <- $dir"
  mkdir -p "$OUT/apps/$pkg"
  adbx shell pm path "$pkg" | tr -d '\r' > "$OUT/apps/$pkg/pm-path.txt" || true
  adbx pull "$dir" "$OUT/apps/$pkg/" || echo "[warn] pull failed for $pkg" >&2
  adbx shell dumpsys package "$pkg" | tr -d '\r' > "$OUT/dumpsys/$pkg.txt" || true
done < "$OUT/packages-vendor.txt"

# --- step 2: framework (TWUtil) + native side + platform config
adbx pull /system/framework "$OUT/system/" || true          # framework.jar + arm/arm64 boot*.oat/vdex if odexed
adbx shell ls -la /system/framework | tr -d '\r' > "$OUT/system/framework-ls.txt" || true
mkdir -p "$OUT/system/lib" "$OUT/system/lib64"
adbx pull /system/lib/libandroid_runtime.so "$OUT/system/lib/" || true
adbx pull /system/lib64/libandroid_runtime.so "$OUT/system/lib64/" || true
adbx shell ls -la /sdcard/.Tcfg/ | tr -d '\r' > "$OUT/system/Tcfg-ls.txt" || true
adbx pull /sdcard/.Tcfg "$OUT/system/" || true

echo
echo "[done] $(wc -l < "$OUT/packages-vendor.txt") vendor packages pulled into $OUT"
echo "Next: tools/phaseA/decompile.sh"
