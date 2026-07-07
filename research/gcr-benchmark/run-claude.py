#!/usr/bin/env python3
"""Run the GCR benchmark corpus against a Claude (Anthropic) model. See
gcr_common.py for the shared primers/validation/GCR-loop logic used by every
provider script.

This is the confound-free counterpart to the original Claude Sonnet 5 run,
which went through isolated Claude Code subagents. Those subagents ran in an
environment where the kUML MCP server *may* have been configured, and their
Sequence/C4/SysML2 primers were never saved as a standalone artifact. This
script removes both ambiguities: it calls the raw Anthropic Messages API
(no MCP server reachable), validates kUML purely through the `kuml` CLI via
gcr_common (same as run-gpt4o.py / run-gemini.py), and uses the exact same
reconstructed primers as the GPT-4o and Gemini runs. Result: all four models
share one methodology.

Usage:
    export ANTHROPIC_API_KEY=...
    python3 run-claude.py [--model claude-sonnet-4-6] [--workers 3] [--limit N]

NOTE on --model: set this to whatever the real "Claude Sonnet 5" API id is.
The catalog default here is `claude-sonnet-4-6` (the current Sonnet). Pass
--model explicitly to pin the exact tier you want to reproduce.

Anthropic's pay-as-you-go API has no hard daily request cap for a funded
account -- 429s are transient per-minute rate limits (retried with backoff).
The one "stop the whole run" case is a low credit balance (HTTP 400 with a
"credit balance is too low" message), treated the same way run-gpt4o.py
treats OpenAI `insufficient_quota`: stop cleanly so the run can be resumed
once the account is topped up, rather than burning through retries.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark

API_KEY = os.environ.get("ANTHROPIC_API_KEY")

# Anthropic requires max_tokens; diagram DSL output is small, 4096 is ample.
MAX_TOKENS = 4096


class CreditExhausted(Exception):
    pass


def call_claude(model, prompt, max_retries=6):
    if not API_KEY:
        raise RuntimeError("ANTHROPIC_API_KEY not set")
    url = "https://api.anthropic.com/v1/messages"
    # temperature is deprecated/rejected (HTTP 400) for newer Claude models
    # (e.g. claude-sonnet-5) — omit it and let the API use its default rather
    # than pinning a param this model generation no longer accepts.
    body = json.dumps({
        "model": model,
        "max_tokens": MAX_TOKENS,
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")
    delay = 3
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "content-type": "application/json",
            "x-api-key": API_KEY,
            "anthropic-version": "2023-06-01",
        }, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                # content is a list of blocks; return the first text block
                # (thinking blocks, if any, precede it).
                for block in data.get("content", []):
                    if block.get("type") == "text":
                        return block["text"]
                return ""
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 400 and "credit balance" in payload.lower():
                # No credit on the account - retrying won't help, stop cleanly.
                raise CreditExhausted(payload[:300])
            if e.code in (429, 500, 503, 529) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"Anthropic HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("Anthropic call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-sonnet-4-6",
                    help="Anthropic model id (set to the real 'Sonnet 5' id to reproduce that tier)")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=None, help="default: results/<model>/raw-results.json")
    args = ap.parse_args()
    out = args.out or os.path.join(SCRIPT_DIR, "results", args.model, "raw-results.json")

    run_benchmark(call_claude, CreditExhausted, args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
