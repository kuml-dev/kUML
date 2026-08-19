#!/usr/bin/env python3
"""Run the GCR benchmark corpus against a local Ollama model. See
gcr_common.py for the shared primers/validation/GCR-loop logic used by every
provider script.

Usage:
    ollama serve                       # if not already running
    ollama pull llama3.2                # or whichever model you want to test
    python3 run-ollama.py [--model llama3.2] [--workers 1] [--limit N]

Unlike the cloud providers, there is no quota/rate-limit concept for a local
model — the failure mode is "Ollama isn't running" or "the model isn't
pulled", both of which should stop the run immediately with a clear message
rather than retrying. --workers defaults to 1 because Ollama serializes
generation on typical single-GPU/CPU hardware; raise it only if you know your
setup can actually run concurrent generations (e.g. a beefy GPU with several
models loaded).
"""
import argparse
import json
import os
import urllib.error
import urllib.request

from gcr_common import SCRIPT_DIR, run_benchmark

OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://localhost:11434")


class OllamaUnreachable(Exception):
    """Stand-in for the cloud scripts' "stop the whole run" quota exception.

    Never actually raised as a quota condition (there is none for a local
    model) — used only so a dead/unreachable Ollama server or an unpulled
    model aborts the run cleanly instead of retrying pointlessly.
    """


def call_ollama(model, prompt, max_retries=2):
    url = f"{OLLAMA_HOST}/v1/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
    }).encode("utf-8")
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
        }, method="POST")
        try:
            # Local inference on CPU can be slow; generous timeout.
            with urllib.request.urlopen(req, timeout=600) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return data["choices"][0]["message"]["content"]
        except urllib.error.URLError as e:
            raise OllamaUnreachable(
                f"Cannot reach Ollama at {OLLAMA_HOST}: {e}. "
                f"Is it running? ('ollama serve')"
            )
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 404:
                raise OllamaUnreachable(
                    f"Ollama HTTP 404 for model '{model}': {payload[:300]}. "
                    f"Is it pulled? ('ollama pull {model}')"
                )
            if e.code == 500 and attempt < max_retries - 1:
                continue
            raise RuntimeError(f"Ollama HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("Ollama call exhausted retries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="llama3.2")
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--limit", type=int, default=None, help="limit number of tasks (for smoke-testing)")
    ap.add_argument("--ids", default=None, help="comma-separated task IDs to run (for smoke-testing specific families)")
    ap.add_argument("--out", default=None, help="default: results/<model>/raw-results.json")
    args = ap.parse_args()
    out = args.out or os.path.join(SCRIPT_DIR, "results", args.model, "raw-results.json")

    run_benchmark(call_ollama, OllamaUnreachable, args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
