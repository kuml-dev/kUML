#!/usr/bin/env bash
# watch-vault-examples.sh
#
# Watches the Obsidian vault example directory for changes and re-runs
# sync-vault-examples.sh whenever a .md file is created, modified or deleted.
#
# Requires `fswatch` (macOS / Linux). Install with:
#   brew install fswatch
#
# Usage:
#   scripts/watch-vault-examples.sh
#
# Stops with Ctrl+C. Designed to run in a long-lived terminal while you edit
# vault notes; pair with VaultExamplesRenderTest which auto-reruns when its
# committed resources change.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VAULT_EXAMPLES_DIR="${VAULT_EXAMPLES_DIR:-$HOME/Documents/Obsidian/Zweites Gehirn/03 Bereiche/kUML/Beispiele}"

if ! command -v fswatch >/dev/null 2>&1; then
  echo "[watch] fswatch not found. Install with: brew install fswatch" >&2
  exit 1
fi

if [[ ! -d "$VAULT_EXAMPLES_DIR" ]]; then
  echo "[watch] Vault directory not found: $VAULT_EXAMPLES_DIR" >&2
  exit 1
fi

echo "[watch] Watching $VAULT_EXAMPLES_DIR for .md changes — Ctrl+C to stop"

# Initial sync so the repo is up to date right after starting the watcher.
"$SCRIPT_DIR/sync-vault-examples.sh"

# fswatch -e excludes pattern, --include filters via extended regex.
fswatch -0 -r \
  --event Created --event Updated --event Removed --event Renamed \
  --include='\.md$' --exclude='.*' \
  --latency 0.5 \
  "$VAULT_EXAMPLES_DIR" | while IFS= read -r -d '' changed; do
  echo "[watch] $(date '+%H:%M:%S') change detected: $(basename "$changed")"
  "$SCRIPT_DIR/sync-vault-examples.sh"
done
