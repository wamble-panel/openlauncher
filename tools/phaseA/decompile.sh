#!/usr/bin/env bash
# Phase A step 3: decompile everything pulled by pull_vendor.sh with jadx (headless),
# then regenerate the docs. Host-only, never touches the unit.
#   tools/phaseA/decompile.sh            # all vendor apps + framework.jar
#   tools/phaseA/decompile.sh com.tw.bt  # one package
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DUMP="$ROOT/device_dump"
JADX="${JADX:-$ROOT/tools/bin/jadx/bin/jadx}"
OUT="$DUMP/jadx"
[ -x "$JADX" ] || { echo "jadx missing: run tools/setup_tools.sh" >&2; exit 1; }
mkdir -p "$OUT"

has_dex() { unzip -l "$1" 2>/dev/null | grep -qE ' classes[0-9]*\.dex$'; }

decompile() {  # <input> <outdir>
  if [ -d "$2/sources" ]; then echo "[skip] $2"; return; fi
  echo "[jadx] $1 -> $2"
  "$JADX" --show-bad-code -j "$(nproc)" -d "$2" "$1" > "$2.log" 2>&1 || \
    echo "[warn] jadx exited non-zero for $1 (see $2.log)" >&2
}

for appdir in "$DUMP"/apps/*/; do
  pkg="$(basename "$appdir")"
  [ $# -gt 0 ] && [[ " $* " != *" $pkg "* ]] && continue
  apk="$(find "$appdir" -maxdepth 3 -name '*.apk' ! -name 'split_*' | head -n1)"
  [ -n "$apk" ] || { echo "[warn] no apk for $pkg" >&2; continue; }
  if ! has_dex "$apk"; then
    echo "[warn] $pkg: $apk has no classes.dex (odexed system app)." >&2
    echo "       Deodex the .vdex in $(dirname "$apk")/oat/ with vdexExtractor/baksmali, then rerun." >&2
    continue
  fi
  decompile "$apk" "$OUT/$pkg"
done

fw="$DUMP/system/framework/framework.jar"
if [ $# -eq 0 ] && [ -f "$fw" ]; then
  if has_dex "$fw"; then
    decompile "$fw" "$OUT/framework"
  else
    echo "[warn] framework.jar has no dex (odexed). TWUtil is in $DUMP/system/framework/arm*/boot-framework.vdex;" >&2
    echo "       extract with vdexExtractor -> jadx, output to $OUT/framework" >&2
  fi
fi

PY="python3 -I"
$PY "$ROOT/tools/phaseA/twutil_api.py"      --jadx "$OUT" --out "$ROOT/docs/TWUtil-API.md"
$PY "$ROOT/tools/phaseA/mcu_map.py"         --jadx "$OUT" --out "$ROOT/docs/mcu-message-map.md"
$PY "$ROOT/tools/phaseA/vendor_intents.py"  --jadx "$OUT" --carlink "$ROOT/mmi-carlink.txt" --out "$ROOT/docs/vendor-intents.md"
