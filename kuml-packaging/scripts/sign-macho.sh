#!/usr/bin/env bash
# sign-macho.sh
#
# Developer-ID-signs a single Mach-O binary (executable or dylib) with
# hardened runtime + a secure timestamp. Part of kUML's local macOS
# signing infrastructure (V3.2.25/26 — see the Obsidian vault plan
# "V3.2-Apple-Signierung-Wellenplan" and
# "03 Bereiche/kUML/MCP-Server AMFI-Signierungsproblem (macOS)").
#
# Gate: if KUML_SIGN_IDENTITY is not set, this script is a silent no-op
# (exit 0, prints a notice) — it must never break an unsigned local build
# for a machine without a Developer ID certificate installed.
#
# Usage:
#   kuml-packaging/scripts/sign-macho.sh <path-to-macho> [entitlements.plist]
#
# Environment:
#   KUML_SIGN_IDENTITY   — codesign identity, e.g.
#                           "Developer ID Application: Irakli Betchvaia (A269Y55753)"
#                           If unset, script no-ops.
#   KUML_SIGN_KEYCHAIN   — optional explicit keychain path (used in CI with an
#                           ephemeral keychain; unset uses the default search list).

set -euo pipefail

if [ -z "${KUML_SIGN_IDENTITY:-}" ]; then
    echo "sign-macho.sh: KUML_SIGN_IDENTITY not set — skipping signing (unsigned build)." >&2
    exit 0
fi

TARGET="${1:?Usage: sign-macho.sh <path-to-macho> [entitlements.plist]}"
ENTITLEMENTS="${2:-}"

if [ ! -f "$TARGET" ]; then
    echo "sign-macho.sh: no such file: $TARGET" >&2
    exit 1
fi

CODESIGN_ARGS=(
    --sign "$KUML_SIGN_IDENTITY"
    --options runtime
    --timestamp
    --force
)

if [ -n "${KUML_SIGN_KEYCHAIN:-}" ]; then
    CODESIGN_ARGS+=(--keychain "$KUML_SIGN_KEYCHAIN")
fi

if [ -n "$ENTITLEMENTS" ]; then
    CODESIGN_ARGS+=(--entitlements "$ENTITLEMENTS")
fi

codesign "${CODESIGN_ARGS[@]}" "$TARGET"
