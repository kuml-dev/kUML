#!/bin/bash
# Auto-resume for the Gemini GCR benchmark run. Installed as a daily macOS
# LaunchAgent (com.kuml.gcr-benchmark-gemini) because the free-tier daily
# quota for gemini-2.5-flash only gets through ~20 cells/day; run-gemini.py
# itself is resumable (skips cells already marked success/exhausted/stuck).
set -euo pipefail
cd "$(dirname "$0")"
set -a
source .env
set +a
mkdir -p results/gemini-2.5-flash
{
  echo "=== $(date) ==="
  python3 run-gemini.py --model gemini-2.5-flash --workers 2
  echo "=== exit=$? $(date) ==="
} >> results/gemini-2.5-flash/daily-run.log 2>&1
