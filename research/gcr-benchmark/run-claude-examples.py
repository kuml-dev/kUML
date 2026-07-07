#!/usr/bin/env python3
"""Run the GCR benchmark corpus's kUML cells against Claude, with the real
`kuml.examples` MCP tool available via Anthropic tool-use. Companion to
run-claude.py (the "without kuml.examples" baseline) -- together they form
the V3.3 backlog benchmark arm: does the teaching-channel MCP tool improve
generation quality over the plain compiler-feedback (GCR) loop alone?

Only the kUML DSL cells are run (50, not 150) -- kuml.examples is kUML-
specific, so offering it for PlantUML/Mermaid cells would either never be
called (wasted cost) or be irrelevant to what's being measured.

Mechanics: each generation call gets one Anthropic Messages API request with
`tools=[KUML_EXAMPLES_TOOL]`. If the model responds with a tool_use block,
the tool is executed against a REAL kuml-mcp server subprocess (JSON-RPC
over stdio, same binary a real MCP client would talk to -- not a
reimplementation), the result is fed back as a tool_result, and the
conversation continues (bounded at MAX_TOOL_ROUNDS) until the model returns
a final text-only response. That final text is what gcr_common validates,
exactly as with the no-tool baseline -- so the two runs are otherwise
identical (same primers, same GCR repair loop, same toolchain validation).

Usage:
    export ANTHROPIC_API_KEY=...
    export KUML_MCP_BIN=/path/to/kuml-mcp/build/install/kuml-mcp/bin/kuml-mcp
    python3 run-claude-examples.py --model claude-sonnet-5 [--workers 3] [--limit N]

See run-claude.py for the CreditExhausted / retry-backoff rationale, which
this script mirrors verbatim.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

from gcr_common import (
    CORPUS_PATH,
    DONE_STATUSES,
    SCRIPT_DIR,
    build_prompt,
    make_gcr_loop,
)

API_KEY = os.environ.get("ANTHROPIC_API_KEY")
MAX_TOKENS = 4096
MAX_TOOL_ROUNDS = 4  # bounds runaway tool-use loops; a real MCP session has no such cap

# kuml-mcp binary path. Defaults to the local repo's installDist output
# (built via `./gradlew :kuml-mcp:installDist`) -- override via env var to
# point at e.g. a Homebrew install.
KUML_MCP_BIN = os.environ.get(
    "KUML_MCP_BIN",
    os.path.join(SCRIPT_DIR, "..", "..", "kuml-mcp", "build", "install", "kuml-mcp", "bin", "kuml-mcp"),
)

# Mirrors dev.kuml.mcp.tools.ExamplesTool.descriptor exactly (see
# kuml-mcp/src/main/kotlin/dev/kuml/mcp/tools/ExamplesTool.kt) -- same
# name/description/schema a real MCP client would see, translated into
# Anthropic's tool-use shape (name can't contain '.', unlike the MCP tool
# name "kuml.examples").
KUML_EXAMPLES_TOOL = {
    "name": "kuml_examples",
    "description": (
        "Fetch curated kUML DSL example scripts for a specific diagram type -- the strongest "
        "anti-hallucination lever. Call with `language` only to list available diagram "
        "types; add `diagramType` to get the matching `.kuml.kts` example script(s) plus "
        "a one-line description and the source note title."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "language": {
                "type": "string",
                "enum": ["uml", "c4", "sysml2", "bpmn", "blueprint"],
                "description": "kUML modelling language. Required.",
            },
            "diagramType": {
                "type": "string",
                "description": (
                    "Optional per-language diagram-type token (kebab-case, e.g. 'class', "
                    "'sequence', 'state-machine', 'composite-structure', 'bdd', "
                    "'container', 'service-blueprint', 'journey'). Omit to list the "
                    "diagram types available for the given language. Call with "
                    "'language' only first to discover valid values."
                ),
            },
        },
        "required": ["language"],
    },
}


class CreditExhausted(Exception):
    pass


# --------------------------------------------------------------------------
# Real kuml-mcp subprocess client -- one persistent process per worker
# thread (JVM startup is too slow to pay per tool call), JSON-RPC over
# stdio, exactly like a real MCP client talks to this binary.
# --------------------------------------------------------------------------
_local = threading.local()


def _mcp_rpc(proc, req_id, method, params=None):
    req = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        req["params"] = params
    proc.stdin.write(json.dumps(req) + "\n")
    proc.stdin.flush()
    line = proc.stdout.readline()
    if not line:
        stderr = proc.stderr.read() if proc.stderr else ""
        raise RuntimeError(f"kuml-mcp closed stdout unexpectedly (method={method}): {stderr[:500]}")
    return json.loads(line)


def _get_mcp_proc():
    proc = getattr(_local, "proc", None)
    if proc is not None and proc.poll() is None:
        return proc
    proc = subprocess.Popen(
        [KUML_MCP_BIN],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    _mcp_rpc(proc, 0, "initialize")
    _local.proc = proc
    _local.next_id = 1
    return proc


def call_kuml_examples_tool(tool_input):
    """Executes kuml.examples against the real kuml-mcp server and returns
    the tool_result content as a plain string (concatenated text blocks)."""
    _local.tool_call_count = getattr(_local, "tool_call_count", 0) + 1
    proc = _get_mcp_proc()
    req_id = _local.next_id
    _local.next_id += 1
    resp = _mcp_rpc(proc, req_id, "tools/call", {"name": "kuml.examples", "arguments": tool_input})
    if "error" in resp:
        return f"ERROR: {resp['error'].get('message', resp['error'])}"
    result = resp.get("result", {})
    text = "\n".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")
    if result.get("isError"):
        return f"ERROR: {text}"
    return text


# --------------------------------------------------------------------------
# Anthropic Messages API, tool-use loop
# --------------------------------------------------------------------------
def _post_messages(model, messages, max_retries=6):
    if not API_KEY:
        raise RuntimeError("ANTHROPIC_API_KEY not set")
    url = "https://api.anthropic.com/v1/messages"
    body = json.dumps({
        "model": model,
        "max_tokens": MAX_TOKENS,
        "tools": [KUML_EXAMPLES_TOOL],
        "messages": messages,
    }).encode("utf-8")
    delay = 3
    last_err = None
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "content-type": "application/json",
            "x-api-key": API_KEY,
            "anthropic-version": "2023-06-01",
        }, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 400 and "credit balance" in payload.lower():
                raise CreditExhausted(payload[:300])
            if e.code in (429, 500, 502, 503, 529):
                last_err = f"HTTP {e.code}: {payload[:200]}"
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"Anthropic HTTP {e.code}: {payload[:300]}")
        except (urllib.error.URLError, TimeoutError) as e:
            last_err = e
            time.sleep(delay)
            delay = min(delay * 2, 60)
    raise RuntimeError(f"Anthropic API: exhausted {max_retries} retries: {last_err}")


def call_claude_with_examples(model, prompt):
    messages = [{"role": "user", "content": prompt}]
    for _round in range(MAX_TOOL_ROUNDS):
        data = _post_messages(model, messages)
        blocks = data.get("content", [])
        stop_reason = data.get("stop_reason")
        if stop_reason != "tool_use":
            return "\n".join(b["text"] for b in blocks if b.get("type") == "text")
        messages.append({"role": "assistant", "content": blocks})
        tool_results = []
        for block in blocks:
            if block.get("type") != "tool_use":
                continue
            result_text = call_kuml_examples_tool(block.get("input", {}))
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block["id"],
                "content": result_text,
            })
        messages.append({"role": "user", "content": tool_results})
    # Round cap hit: return whatever text the last response had, if any.
    text_blocks = [b["text"] for b in messages[-2]["content"] if isinstance(b, dict) and b.get("type") == "text"] \
        if isinstance(messages[-2]["content"], list) else []
    return "\n".join(text_blocks)


# --------------------------------------------------------------------------
# Driver -- deliberately NOT gcr_common.run_benchmark, which hardcodes all
# three DSLs per task. This arm only runs "kuml" cells (see module
# docstring), so it's a trimmed copy of run_benchmark's resumable-driver
# shape, restricted to one DSL.
# --------------------------------------------------------------------------
def _gcr_loop_with_tool_count(gcr_loop, model, task, dsl):
    """Wraps gcr_loop to also report how many times kuml_examples was
    invoked while processing this cell (across all GCR repair attempts) --
    tool-usage rate is itself a metric worth reporting alongside CR/FCR/SF/HR.
    Relies on _local being reset per-task: a ThreadPoolExecutor future runs
    entirely on one worker thread, so this is race-free across concurrent
    cells."""
    _local.tool_call_count = 0
    result = gcr_loop(model, task, dsl)
    result["toolCallCount"] = _local.tool_call_count
    return result


def run_kuml_only_benchmark(model, out_path, workers=3, limit=None, ids=None):
    import concurrent.futures
    quota_event = threading.Event()
    gcr_loop = make_gcr_loop(call_claude_with_examples, CreditExhausted, quota_event)

    tasks = json.load(open(CORPUS_PATH))
    if ids:
        wanted = set(ids.split(","))
        tasks = [t for t in tasks if t["id"] in wanted]
    elif limit:
        tasks = tasks[:limit]

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    results = []
    if os.path.exists(out_path):
        prior = json.load(open(out_path))
        results = [r for r in prior if r.get("status") in DONE_STATUSES and r.get("finalCode")]
    completed = {r["taskId"] for r in results}
    cells = [t for t in tasks if t["id"] not in completed]

    if not cells:
        print(f"All {len(tasks)} kuml cells already done in {out_path}.")
        return results

    print(f"{len(completed)} cells already done, {len(cells)} remaining.", file=sys.stderr)
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(_gcr_loop_with_tool_count, gcr_loop, model, t, "kuml"): t["id"] for t in cells}
        for fut in concurrent.futures.as_completed(futures):
            tid = futures[fut]
            try:
                r = fut.result()
            except Exception as e:
                r = {"taskId": tid, "dsl": "kuml", "compiledFirstShot": False, "compiledFinal": False,
                     "iterations": 0, "status": f"error: {e}", "nodes": [], "edges": []}
            results.append(r)
            done += 1
            print(f"[{done}/{len(cells)}] {tid} kuml: {r['status']}", file=sys.stderr)
            with open(out_path, "w") as f:
                json.dump(results, f, indent=2)

    remaining = len(tasks) - len({r["taskId"] for r in results if r.get("status") in DONE_STATUSES})
    print(f"\nWrote {len(results)} rows to {out_path}.")
    if quota_event.is_set():
        print(f"Credit exhausted -- {remaining} cells still outstanding. Re-run to resume.")
    elif remaining:
        print(f"{remaining} cells did not complete (see per-row status). Re-run to retry them.")
    else:
        print("All cells done.")
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="claude-sonnet-5")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--ids", default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    if not os.path.exists(KUML_MCP_BIN):
        sys.exit(
            f"kuml-mcp binary not found at {KUML_MCP_BIN} -- build it first: "
            f"./gradlew :kuml-mcp:installDist (or set KUML_MCP_BIN)"
        )

    out = args.out or os.path.join(SCRIPT_DIR, "results", f"{args.model}-with-examples", "raw-results.json")
    run_kuml_only_benchmark(args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
