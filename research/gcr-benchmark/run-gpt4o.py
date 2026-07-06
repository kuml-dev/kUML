#!/usr/bin/env python3
"""Run the GCR benchmark corpus against GPT-4o. See gcr_common.py for the
shared primers/validation/GCR-loop logic used by every provider script.

Usage:
    export OPENAI_API_KEY=...
    python3 run-gpt4o.py [--model gpt-4o] [--workers 3] [--limit N]

Unlike the Gemini free tier, OpenAI's pay-as-you-go API has no hard daily
request cap for a funded account — 429s here are almost always transient
per-minute rate limits (retried with backoff) rather than "come back
tomorrow". The one exception is `insufficient_quota` (no credit on the
account), which is treated the same way run-gemini.py treats a free-tier
daily quota: stop the whole run cleanly so it can be resumed once the
account is topped up, rather than burning through retries pointlessly.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark

API_KEY = os.environ.get("OPENAI_API_KEY")


class QuotaExceeded(Exception):
    pass


def call_openai(model, prompt, max_retries=6):
    if not API_KEY:
        raise RuntimeError("OPENAI_API_KEY not set")
    url = "https://api.openai.com/v1/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
    }).encode("utf-8")
    delay = 3
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {API_KEY}",
        }, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return data["choices"][0]["message"]["content"]
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 429 and "insufficient_quota" in payload:
                # No credit on the account - retrying won't help, stop cleanly.
                raise QuotaExceeded(payload[:300])
            if e.code in (429, 500, 503) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"OpenAI HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("OpenAI call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gpt-4o")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=None, help="default: results/<model>/raw-results.json")
    args = ap.parse_args()
    out = args.out or os.path.join(SCRIPT_DIR, "results", args.model, "raw-results.json")

    run_benchmark(call_openai, QuotaExceeded, args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
