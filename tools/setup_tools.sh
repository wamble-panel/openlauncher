#!/usr/bin/env bash
# Installs host-side tooling into tools/bin and tools/.venv (both gitignored).
# Safe to re-run. Nothing here talks to the head unit.
#   jadx    - headless decompiler for the vendor APKs / framework.jar (needs Java 11+)
#   binwalk - optional, signature scan of firmware images (tools/phaseB/carve.py works without it)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/tools/bin"
JADX_VER="${JADX_VER:-1.5.3}"
mkdir -p "$BIN"

if [ ! -x "$BIN/jadx/bin/jadx" ]; then
  echo "[setup] jadx $JADX_VER"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/jadx.zip" "https://github.com/skylot/jadx/releases/download/v$JADX_VER/jadx-$JADX_VER.zip"
  mkdir -p "$BIN/jadx" && unzip -q -o "$tmp/jadx.zip" -d "$BIN/jadx"
  chmod +x "$BIN/jadx/bin/"*
  rm -rf "$tmp"
fi
"$BIN/jadx/bin/jadx" --version

if [ ! -x "$ROOT/tools/.venv/bin/python" ]; then
  echo "[setup] python venv"
  python3 -m venv "$ROOT/tools/.venv"
fi
"$ROOT/tools/.venv/bin/pip" install -q "binwalk==2.1.0" || \
  echo "[setup] WARN: binwalk install failed; tools/phaseB/carve.py works without it"

command -v adb >/dev/null || echo "[setup] NOTE: adb not found. Install Android platform-tools on the PC that can reach the head unit."
echo "[setup] done"
