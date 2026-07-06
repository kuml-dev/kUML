#!/usr/bin/env python3
"""Run the GCR benchmark corpus against a Gemini model. See gcr_common.py for
the shared primers/validation/GCR-loop logic used by every provider script.

Usage:
    export GEMINI_API_KEY=...
    python3 run-gemini.py [--model gemini-2.5-flash] [--workers 3] [--limit N]
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark

API_KEY = os.environ.get("GEMINI_API_KEY")


class DailyQuotaExceeded(Exception):
    pass


def call_gemini(model, prompt, max_retries=6):
    if not API_KEY:
        raise RuntimeError("GEMINI_API_KEY not set")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={API_KEY}"
    body = json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode("utf-8")
    delay = 3
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return data["candidates"][0]["content"]["parts"][0]["text"]
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 429 and "PerDay" in payload:
                # Free-tier daily quota exhausted for this model - no point retrying,
                # it won't reset for hours. Fail fast so the caller can stop cleanly.
                raise DailyQuotaExceeded(payload[:300])
            if e.code in (429, 500, 503) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"Gemini HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("Gemini call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=os.path.join(SCRIPT_DIR, "results", "gemini-2.5-flash", "raw-results.json"))
    args = ap.parse_args()

    run_benchmark(call_gemini, DailyQuotaExceeded, args.model, args.out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
