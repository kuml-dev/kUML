#!/usr/bin/env bash
# Builds the kUML Desktop AppImage from a jpackage app image (createDistributable).
# Usage: build-appimage.sh <app-image-dir> <icon.png> <version> <output-file>
# Env:   APPIMAGETOOL      path to the verified appimagetool (required)
#        APPIMAGE_RUNTIME  path to the verified AppImage runtime file (required)
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <app-image-dir> <icon.png> <version> <output-file>" >&2
  exit 2
fi
APP_DIR="$1"
ICON="$2"
VERSION="$3"
OUT="$4"
: "${APPIMAGETOOL:?APPIMAGETOOL must point to appimagetool}"
: "${APPIMAGE_RUNTIME:?APPIMAGE_RUNTIME must point to the AppImage runtime file}"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]]; then
  echo "invalid version '$VERSION' (expected X.Y.Z without leading v)" >&2
  exit 2
fi
[ -x "$APP_DIR/bin/kuml-desktop" ] || { echo "missing launcher: $APP_DIR/bin/kuml-desktop" >&2; exit 1; }
[ -f "$ICON" ] || { echo "missing icon: $ICON" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
APPDIR="$WORK/kuml-desktop.AppDir"

mkdir -p "$APPDIR/opt/kuml-desktop" \
         "$APPDIR/usr/share/applications" \
         "$APPDIR/usr/share/icons/hicolor/512x512/apps"
cp "$SCRIPT_DIR/AppRun" "$APPDIR/AppRun"
chmod 755 "$APPDIR/AppRun"
sed "s/@VERSION@/$VERSION/" "$SCRIPT_DIR/kuml-desktop.desktop" > "$APPDIR/kuml-desktop.desktop"
cp "$APPDIR/kuml-desktop.desktop" "$APPDIR/usr/share/applications/kuml-desktop.desktop"
cp "$ICON" "$APPDIR/kuml-desktop.png"
cp "$ICON" "$APPDIR/usr/share/icons/hicolor/512x512/apps/kuml-desktop.png"
ln -s kuml-desktop.png "$APPDIR/.DirIcon"
cp -a "$APP_DIR/." "$APPDIR/opt/kuml-desktop/"

mkdir -p "$(dirname "$OUT")"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$APPIMAGETOOL" --no-appstream \
  --runtime-file "$APPIMAGE_RUNTIME" "$APPDIR" "$OUT"
chmod +x "$OUT"
ls -l "$OUT"
