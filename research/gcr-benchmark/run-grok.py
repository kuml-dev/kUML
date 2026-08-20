#!/usr/bin/env python3
"""Run the GCR benchmark corpus against xAI's Grok. See gcr_common.py for
the shared primers/validation/GCR-loop logic used by every provider script.

Usage:
    export XAI_API_KEY=...
    python3 run-grok.py [--model grok-4] [--workers 3] [--limit N]

xAI's Chat Completions endpoint is OpenAI-compatible (same request/response
shape as run-gpt4o.py), just a different base URL, auth env var, and default
model name -- this script is otherwise a straight copy of run-gpt4o.py's
call/retry logic, matching this repo's convention of not sharing HTTP
plumbing across provider scripts. The "stop the whole run" condition mirrors
run-gpt4o.py's insufficient_quota check but is looser (any 429 whose payload
mentions "credit" or "quota"), since xAI's exact error-body shape for a
no-credit account hasn't been confirmed against a real account yet -- if
this turns out to be wrong in practice (e.g. a transient 429 gets
misclassified as quota-exhausted), tighten the substring match once you've
seen a real error payload.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark

API_KEY = os.environ.get("XAI_API_KEY")


class QuotaExceeded(Exception):
    pass


def call_grok(model, prompt, max_retries=6):
    if not API_KEY:
        raise RuntimeError("XAI_API_KEY not set")
    url = "https://api.x.ai/v1/chat/completions"
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
            if e.code == 429 and ("credit" in payload.lower() or "quota" in payload.lower()):
                # No credit on the account - retrying won't help, stop cleanly.
                raise QuotaExceeded(payload[:300])
            if e.code in (429, 500, 503) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"xAI HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("xAI call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="grok-4")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=None, help="default: results/<model>/raw-results.json")
    args = ap.parse_args()
    out = args.out or os.path.join(SCRIPT_DIR, "results", args.model, "raw-results.json")

    run_benchmark(call_grok, QuotaExceeded, args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
