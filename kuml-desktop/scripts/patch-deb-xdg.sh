#!/bin/bash
# jpackage's generated DEB maintainer scripts call xdg-desktop-menu / xdg-icon-resource / xdg-mime
# directly. On systems without a desktop menu directory (servers, containers, minimal installs)
# those tools exit non-zero ("No writable system menu directory found") and dpkg aborts the
# whole install. Menu registration is cosmetic, so shadow the tools with tolerant wrappers at the
# top of postinst/prerm/postrm (dash does not allow hyphens in function names, hence a PATH
# wrapper directory instead of shell functions). Usage: patch-deb-xdg.sh <dir-with-debs>
set -euo pipefail
dir="$1"
shopt -s nullglob
for deb in "$dir"/*.deb; do
    work="$(mktemp -d)"
    dpkg-deb -R "$deb" "$work"
    for script in postinst prerm postrm; do
        f="$work/DEBIAN/$script"
        [ -f "$f" ] || continue
        grep -q 'kuml-tolerant-xdg' "$f" && continue
        {
            head -n 1 "$f"
            cat <<'SHIM'
# kuml-tolerant-xdg: menu/icon/mime registration must never fail the package operation
_kuml_w="$(mktemp -d 2>/dev/null)" && {
    for _kuml_t in xdg-desktop-menu xdg-icon-resource xdg-mime; do
        _kuml_r="$(command -v "$_kuml_t" 2>/dev/null)" || continue
        printf '#!/bin/sh\n"%s" "$@" || true\n' "$_kuml_r" > "$_kuml_w/$_kuml_t"
        chmod 0755 "$_kuml_w/$_kuml_t"
    done
    PATH="$_kuml_w:$PATH"
    export PATH
    trap 'rm -rf "$_kuml_w"' EXIT
}
SHIM
            tail -n +2 "$f"
        } > "$f.new"
        mv "$f.new" "$f"
        chmod 0755 "$f"
    done
    dpkg-deb --root-owner-group -b "$work" "$deb.new" >/dev/null
    mv "$deb.new" "$deb"
    rm -rf "$work"
    echo "patched $deb"
done
