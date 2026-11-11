#!/usr/bin/env bash
# Smoke test for the kUML Desktop AppImage.
# Usage: smoke-appimage.sh <file.AppImage> [expected-version]
# Env:   SMOKE_GUI=1 additionally starts the app under xvfb and checks that it
#        is still running after 20 s (proves only "did not crash at startup").
set -euo pipefail

IMG="$(realpath "${1:?usage: $0 <file.AppImage> [expected-version]}")"
EXPECTED="${2:-}"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
cd "$WORK"

"$IMG" --appimage-extract >/dev/null
R=squashfs-root
[ -x "$R/AppRun" ] || { echo "AppRun missing or not executable" >&2; exit 1; }
[ -f "$R/kuml-desktop.desktop" ] || { echo "desktop file missing" >&2; exit 1; }
[ -f "$R/kuml-desktop.png" ] || { echo "icon missing" >&2; exit 1; }
[ -x "$R/opt/kuml-desktop/bin/kuml-desktop" ] || { echo "launcher missing or not executable" >&2; exit 1; }
[ -n "$(ls -A "$R/opt/kuml-desktop/lib/app" 2>/dev/null)" ] || { echo "lib/app empty" >&2; exit 1; }
desktop-file-validate "$R/kuml-desktop.desktop"
if [ -n "$EXPECTED" ]; then
  grep -qx "X-AppImage-Version=$EXPECTED" "$R/kuml-desktop.desktop" \
    || { echo "X-AppImage-Version does not match $EXPECTED" >&2; exit 1; }
fi
echo "structure OK"

if [ "${SMOKE_GUI:-0}" = "1" ]; then
  setsid xvfb-run -a "$IMG" --appimage-extract-and-run > gui.log 2>&1 &
  PID=$!
  sleep 20
  if kill -0 "$PID" 2>/dev/null; then
    echo "GUI start: still running after 20 s (does not prove a window was drawn)"
    kill -- "-$PID" 2>/dev/null || pkill -P "$PID" || true
    kill "$PID" 2>/dev/null || true
  else
    echo "GUI start: process exited early" >&2
    cat gui.log >&2
    exit 1
  fi
fi
echo "smoke OK"
