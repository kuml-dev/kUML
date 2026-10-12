#!/usr/bin/env python3
"""Regenerates kuml-desktop's application icons from the official kUML logo.

    python3 kuml-desktop/scripts/generate-app-icon.py

Source of truth: docs/images/logo.svg (identical to the Vault's 03 Bereiche/kUML/Design/kUML.svg
and the logo shown on kuml.dev). Output: src/jvmMain/resources/icons/kuml-desktop.{icns,ico,png}.

Needs rsvg-convert, ImageMagick (magick) and, for the .icns, macOS' iconutil.

Design: macOS icon grid (1024 px canvas, 824 px rounded tile, soft shadow) with the full logo
({k} and the "kUML" wordmark). Below 64 px the wordmark is unreadable, so those sizes show the
{k} mark alone, cropped from the same SVG.
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOGO_SVG = ROOT / "docs/images/logo.svg"
OUT_DIR = ROOT / "kuml-desktop/src/jvmMain/resources/icons"

TILE_RATIO = 824 / 1024  # Apple's macOS icon grid
TILE_RADIUS_RATIO = 0.2237  # corner radius relative to the tile edge
MARK_ONLY_BELOW_PX = 64
FULL_LOGO_FILL = 0.70  # logo width relative to the tile
MARK_FILL = 0.76
MARK_HEIGHT_FRACTION = 0.585  # {k} occupies the top ~58 % of the logo's square viewBox
TILE_COLOR = "#FFFFFF"


def run(*args: object) -> None:
    subprocess.run([str(a) for a in args], check=True)


def render_logo(work: Path) -> tuple[Path, Path]:
    """Returns (full logo, {k} mark only) as tightly trimmed transparent PNGs at 4096 px."""
    big = work / "logo-4096.png"
    run("rsvg-convert", "-w", 4096, LOGO_SVG, "-o", big)
    full = work / "logo-full.png"
    run("magick", big, "-trim", "+repage", full)
    mark = work / "logo-mark.png"
    run("magick", big, "-crop", f"4096x{int(4096 * MARK_HEIGHT_FRACTION)}+0+0", "+repage", "-trim", "+repage", mark)
    return full, mark


def icon_png(size: int, full: Path, mark: Path, out: Path, work: Path) -> None:
    tile = round(size * TILE_RATIO)
    off = (size - tile) // 2
    radius = round(tile * TILE_RADIUS_RATIO)
    use_mark = size < MARK_ONLY_BELOW_PX
    src = mark if use_mark else full
    logo_w = round(tile * (MARK_FILL if use_mark else FULL_LOGO_FILL))
    logo = work / f"logo-{size}.png"
    run("magick", src, "-filter", "Lanczos", "-resize", f"{logo_w}x{logo_w}", logo)
    tile_png = work / f"tile-{size}.png"
    run(
        "magick", "-size", f"{size}x{size}", "xc:none", "-fill", TILE_COLOR, "-draw",
        f"roundrectangle {off},{off} {off + tile - 1},{off + tile - 1} {radius},{radius}", tile_png,
    )
    shadow_blur = max(1, round(size * 0.02))
    shadow_dy = round(size * 0.012)
    run(
        "magick", "-size", f"{size}x{size}", "xc:none",
        "(", tile_png, "-background", "black", "-shadow", f"28x{shadow_blur}+0+{shadow_dy}", ")",
        "-gravity", "center", "-compose", "over", "-composite",
        tile_png, "-gravity", "center", "-composite",
        logo, "-gravity", "center", "-geometry", f"+0+{round(size * 0.004)}", "-composite",
        "-crop", f"{size}x{size}+0+0", "+repage", out,
    )


def main() -> int:
    for tool in ("rsvg-convert", "magick"):
        if shutil.which(tool) is None:
            print(f"missing tool: {tool}", file=sys.stderr)
            return 1
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        full, mark = render_logo(work)

        iconset = work / "kuml-desktop.iconset"
        iconset.mkdir()
        for base in (16, 32, 128, 256, 512):
            icon_png(base, full, mark, iconset / f"icon_{base}x{base}.png", work)
            icon_png(base * 2, full, mark, iconset / f"icon_{base}x{base}@2x.png", work)
        if shutil.which("iconutil"):
            run("iconutil", "-c", "icns", iconset, "-o", OUT_DIR / "kuml-desktop.icns")
        else:
            print("iconutil not found (not macOS): kuml-desktop.icns left unchanged", file=sys.stderr)

        ico_sizes = [16, 24, 32, 48, 64, 128, 256]  # first entry must stay 16 (AppIconAssetsTest)
        ico_pngs = []
        for s in ico_sizes:
            p = work / f"ico-{s}.png"
            icon_png(s, full, mark, p, work)
            ico_pngs.append(p)
        run("magick", *ico_pngs, OUT_DIR / "kuml-desktop.ico")

        icon_png(512, full, mark, OUT_DIR / "kuml-desktop.png", work)
    print(f"wrote icons to {OUT_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
