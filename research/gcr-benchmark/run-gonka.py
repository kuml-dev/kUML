#!/usr/bin/env python3
"""Run the GCR benchmark corpus against a model served over GonkaRouter
(https://gonkarouter.io), an independent OpenAI-compatible broker for the
Gonka decentralized/blockchain AI-compute network. See gcr_common.py for the
shared primers/validation/GCR-loop logic used by every provider script.

Usage:
    export GONKAROUTER_API_KEY=...
    python3 run-gonka.py --model <model-id> [--workers 3] [--limit N]

Not the same broker as kUML's built-in `kuml ai` Gonka provider, which is
hardcoded to the official broker at https://api.gonka.ai/v1 (see
kuml-ai/kuml-ai-core's BuiltInProviders.kt) -- GonkaRouter is a separate
third-party broker with its own account/API key/base URL. This script talks
to GonkaRouter directly via urllib, independent of kUML's Kotlin provider
system, following this repo's convention of one standalone script per
provider rather than routing through the CLI.

Model IDs on GonkaRouter can contain a `provider/name` slash (e.g.
`MiniMaxAI/MiniMax-M2.7`) -- gcr_common.sanitize_for_path handles turning
that into a safe results-directory name.

Quota-exhaustion detection is a loose heuristic (429 + "credit"/"quota" in
the payload), unverified against a real no-credit response from GonkaRouter
at time of writing -- tighten it if it turns out to misclassify a transient
rate limit as quota-exhausted.

Reasoning models on GonkaRouter (confirmed for MiniMax-M2.7) emit their
<think>...</think> trace inline in `message.content`, unlike Ollama's
`deepseek-r1` integration where the thinking text is a separate structured
field the API already excludes from `content`. Left unstripped, the trace
gets treated as part of the generated script and fails validation on
literally every cell -- see _strip_think below.
"""
import argparse
import json
import os
import re
import time
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark, sanitize_for_path

_THINK_RE = re.compile(r"<think>.*?</think>", re.S)


def _strip_think(content):
    """Removes a leading <think>...</think> reasoning trace, if present.
    Only strips the tag itself -- doesn't touch anything else in the
    response, so a model that doesn't emit one is unaffected.

    If <think> appears WITHOUT a matching </think>, the model ran out of
    budget mid-reasoning and never produced real code -- verified against a
    live response where this happened even at max_tokens=16384. Passing that
    through unmodified is actively dangerous, not just untidy: PlantUML/
    Mermaid silently compile it (their whole permissiveness point) and
    gcr_common's line-anchored node/edge regexes can match fragments of the
    prose itself (this benchmark's own prompt primers contain literal
    PlantUML-shaped example lines the model quotes back while reasoning),
    producing garbage structural-fidelity data that LOOKS like a real
    result. Discarding to "" instead makes it fail validation cleanly (kUML:
    empty script; PlantUML/Mermaid: empty extraction) so the GCR loop
    retries or the cell is honestly recorded as a failure -- not silently
    scored on reasoning-prose noise.
    """
    if "<think>" in content and "</think>" not in content:
        return ""
    return _THINK_RE.sub("", content, count=1).strip()

API_KEY = os.environ.get("GONKAROUTER_API_KEY")
BASE_URL = os.environ.get("GONKAROUTER_BASE_URL", "https://api.gonkarouter.io/v1")


class QuotaExceeded(Exception):
    pass


def call_gonka(model, prompt, max_retries=6):
    if not API_KEY:
        raise RuntimeError("GONKAROUTER_API_KEY not set")
    url = f"{BASE_URL}/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
        # Reasoning models (confirmed for MiniMax-M2.7) can burn thousands of
        # tokens on the <think> trace alone before ever reaching the code --
        # left at a provider default, generation gets cut off mid-thought and
        # _strip_think has no closing tag to find, discarding nothing but
        # also never seeing real code. High ceiling, not a per-model tune.
        "max_tokens": 32768,
    }).encode("utf-8")
    delay = 3
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {API_KEY}",
            # GonkaRouter sits behind Cloudflare, which 403s (error code 1010)
            # any request whose User-Agent doesn't look like a browser --
            # Python's default "Python-urllib/x.y" UA gets blocked outright.
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        }, method="POST")
        try:
            # 240s, not the other cloud scripts' 90s: MiniMax-M2.7 (and likely other
            # reasoning-style Gonka models) emit a visible <think> trace before the
            # final answer, which routinely exceeds 90s.
            with urllib.request.urlopen(req, timeout=240) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return _strip_think(data["choices"][0]["message"]["content"])
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 429 and ("credit" in payload.lower() or "quota" in payload.lower()):
                raise QuotaExceeded(payload[:300])
            # 524 = Cloudflare gateway timeout (the origin took too long to respond,
            # not something a client-side urlopen timeout can fix) -- retry anyway,
            # since it can be transient origin load rather than a systematic cap.
            if e.code in (429, 500, 502, 503, 524) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"GonkaRouter HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("GonkaRouter call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="e.g. MiniMaxAI/MiniMax-M2.7 -- check GonkaRouter's /v1/models for the exact id")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=None, help="default: results/<sanitized-model>/raw-results.json")
    args = ap.parse_args()
    out = args.out or os.path.join(SCRIPT_DIR, "results", sanitize_for_path(args.model), "raw-results.json")

    run_benchmark(call_gonka, QuotaExceeded, args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
