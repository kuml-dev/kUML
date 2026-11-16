#!/usr/bin/env bash
# Nacharbeit 91: counts the files one Maven Central release would contain and
# fails if a js / wasm-js artifact (or the playground) is published again.
# Usage: scripts/verify-central-file-count.sh   (writes only to a temp repo, never ~/.m2)
# Note: locally there are no .asc/.md5/.sha1 files; Central receives roughly 4x the count.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$(mktemp -d)"
trap 'rm -rf "$REPO"' EXIT
./gradlew clean publishToMavenLocal -Dmaven.repo.local="$REPO" --no-build-cache -q
cd "$REPO/dev/kuml"
BAD="$(ls | grep -E -- '-js$|-wasm-js$|^kuml-wasm-playground' || true)"
if [ -n "$BAD" ]; then echo "FAIL: JS/Wasm artifacts are published again:"; echo "$BAD"; exit 1; fi
FILES="$(find . -type f ! -name 'maven-metadata-local.xml' | wc -l | tr -d ' ')"
echo "artifacts: $(ls | wc -l | tr -d ' '), local files: $FILES, approx. Central files (x4): $((FILES * 4))"
