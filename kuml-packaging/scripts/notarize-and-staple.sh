#!/usr/bin/env bash
# notarize-and-staple.sh
#
# Submits a macOS artifact (.dmg, .app, or a bare executable/zip) to Apple
# notarization and optionally staples the resulting ticket. Part of kUML's
# local macOS signing infrastructure (V3.2.25/26 — see the Obsidian vault
# plan "V3.2-Apple-Signierung-Wellenplan").
#
# Usage:
#   kuml-packaging/scripts/notarize-and-staple.sh <artifact> [--staple]
#
# Behavior:
#   - .dmg / .app / .pkg are submitted to notarytool directly.
#   - Any other file (e.g. a bare executable, or an already-built .zip) is
#     zipped first if it isn't already a .zip (notarytool requires
#     .zip/.dmg/.pkg — bare Mach-O executables are not accepted directly).
#   - --staple runs `xcrun stapler staple` after a successful submission.
#     Bare zips CANNOT be stapled (Gatekeeper does an online check instead
#     for those) — do not pass --staple for a runtimeZip artifact.
#
# Credentials (local machine, already configured):
#   Uses the notarytool keychain profile named by KUML_NOTARY_PROFILE
#   (default: "kuml-notary"), previously registered via
#   `xcrun notarytool store-credentials`.
#
# Future CI variant (V3.2.27, not wired yet): if KUML_NOTARY_KEY_PATH is set,
# prefer the explicit API-key flags over the keychain profile, since CI runs
# headless and has no keychain profile available:
#   KUML_NOTARY_KEY_PATH   — path to the App Store Connect API key .p8
#   KUML_NOTARY_KEY_ID     — API key ID
#   KUML_NOTARY_ISSUER_ID  — API issuer ID
#
# Gate: if neither KUML_NOTARY_PROFILE-worthy credentials nor the explicit
# key vars are usable this script still tries the default profile name
# ("kuml-notary") — it does NOT silently no-op, since notarization is an
# explicit, deliberate step invoked by the caller (unlike sign-macho.sh,
# which is invoked unconditionally from Gradle and must no-op for people
# without a cert).

set -euo pipefail

ARTIFACT="${1:?Usage: notarize-and-staple.sh <artifact> [--staple]}"
STAPLE=false
if [ "${2:-}" = "--staple" ]; then
    STAPLE=true
fi

if [ ! -e "$ARTIFACT" ]; then
    echo "notarize-and-staple.sh: no such file: $ARTIFACT" >&2
    exit 1
fi

# notarytool only accepts .zip, .dmg, or .pkg. Bare executables (and
# directories) get zipped into a temp file first.
SUBMIT_PATH="$ARTIFACT"
CLEANUP_ZIP=""
case "$ARTIFACT" in
    *.dmg | *.pkg | *.zip)
        # Already an accepted format — submit as-is.
        ;;
    *)
        TMP_ZIP="$(mktemp -t kuml-notarize-XXXXXX).zip"
        echo "notarize-and-staple.sh: zipping bare artifact for submission: $TMP_ZIP"
        ditto -c -k --keepParent "$ARTIFACT" "$TMP_ZIP"
        SUBMIT_PATH="$TMP_ZIP"
        CLEANUP_ZIP="$TMP_ZIP"
        ;;
esac

cleanup() {
    if [ -n "$CLEANUP_ZIP" ] && [ -f "$CLEANUP_ZIP" ]; then
        rm -f "$CLEANUP_ZIP"
    fi
}
trap cleanup EXIT

NOTARY_ARGS=(submit "$SUBMIT_PATH" --wait --timeout 30m)

if [ -n "${KUML_NOTARY_KEY_PATH:-}" ]; then
    # CI variant (V3.2.27 will wire this): explicit API key, no keychain profile.
    NOTARY_ARGS+=(
        --key "$KUML_NOTARY_KEY_PATH"
        --key-id "${KUML_NOTARY_KEY_ID:?KUML_NOTARY_KEY_ID must be set alongside KUML_NOTARY_KEY_PATH}"
        --issuer "${KUML_NOTARY_ISSUER_ID:?KUML_NOTARY_ISSUER_ID must be set alongside KUML_NOTARY_KEY_PATH}"
    )
else
    # Local variant: keychain profile registered via `notarytool store-credentials`.
    NOTARY_ARGS+=(--keychain-profile "${KUML_NOTARY_PROFILE:-kuml-notary}")
fi

echo "notarize-and-staple.sh: submitting $SUBMIT_PATH for notarization…"
xcrun notarytool "${NOTARY_ARGS[@]}"

if [ "$STAPLE" = true ]; then
    case "$ARTIFACT" in
        *.dmg | *.pkg | *.app)
            echo "notarize-and-staple.sh: stapling ${ARTIFACT}…"
            xcrun stapler staple "$ARTIFACT"
            ;;
        *)
            echo "notarize-and-staple.sh: --staple requested but $ARTIFACT is not a .dmg/.pkg/.app — bare files/zips cannot be stapled. Skipping staple (Gatekeeper will do an online check instead)." >&2
            ;;
    esac
fi
