#!/usr/bin/env python3
"""Run the GCR benchmark corpus's kUML cells against Gemini, with the real
`kuml.examples` MCP tool available via Gemini function-calling. Companion to
run-claude-examples.py (same experiment, Anthropic tool-use) and run-gemini.py
(the "without kuml.examples" baseline for this model) -- see run-claude-
examples.py's module docstring for the full rationale, which applies here
unchanged. This script exists because the V3.3 backlog explicitly flagged
Gemini as the most interesting test case: it showed the strongest missing-
syntax-anchor symptoms in the baseline DSL-comparison runs (0% C4 first-shot,
64-69% sequence-diagram hallucination rate across both Flash and Pro) -- if
kuml.examples helps anywhere the most, it should be here.

Only the kUML DSL cells are run (50, not 150) -- kuml.examples is kUML-
specific.

Mechanics: Gemini's function-calling shape differs from Anthropic's tool-use
(functionDeclarations/functionCall/functionResponse instead of
tools/tool_use/tool_result, response.candidates[0].content.parts instead of
response.content), but the loop is structurally the same: send a request
with tools declared; if a candidate part contains a functionCall, execute it
against a REAL kuml-mcp server subprocess (same binary/JSON-RPC-over-stdio
mechanism as run-claude-examples.py -- see that file for the client, which
this one duplicates rather than sharing, matching this repo's existing
per-provider-script convention of not sharing HTTP/tool-call plumbing across
providers), feed back a functionResponse, and continue (bounded at
MAX_TOOL_ROUNDS) until a text-only candidate is returned.

Usage:
    export GEMINI_API_KEY=...
    export KUML_MCP_BIN=/path/to/kuml-mcp/build/install/kuml-mcp/bin/kuml-mcp
    python3 run-gemini-examples.py --model gemini-2.5-flash [--workers 3] [--limit N]
"""
import argparse
import concurrent.futures
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
    make_gcr_loop,
)

API_KEY = os.environ.get("GEMINI_API_KEY")
MAX_TOOL_ROUNDS = 4  # mirrors run-claude-examples.py

KUML_MCP_BIN = os.environ.get(
    "KUML_MCP_BIN",
    os.path.join(SCRIPT_DIR, "..", "..", "kuml-mcp", "build", "install", "kuml-mcp", "bin", "kuml-mcp"),
)

# Gemini functionDeclarations use "parameters" (OpenAPI-subset schema), not
# Anthropic's "input_schema" -- same content as KUML_EXAMPLES_TOOL in
# run-claude-examples.py, translated to that shape. Mirrors
# dev.kuml.mcp.tools.ExamplesTool.descriptor exactly.
KUML_EXAMPLES_FUNCTION_DECLARATION = {
    "name": "kuml_examples",
    "description": (
        "Fetch curated kUML DSL example scripts for a specific diagram type -- the strongest "
        "anti-hallucination lever. Call with `language` only to list available diagram "
        "types; add `diagramType` to get the matching `.kuml.kts` example script(s) plus "
        "a one-line description and the source note title."
    ),
    "parameters": {
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


class DailyQuotaExceeded(Exception):
    pass


# --------------------------------------------------------------------------
# Real kuml-mcp subprocess client -- identical mechanism to run-claude-
# examples.py (one persistent process per worker thread, JSON-RPC over
# stdio). Duplicated rather than imported: see module docstring.
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
# Gemini generateContent API, function-calling loop
# --------------------------------------------------------------------------
def _post_generate_content(model, contents, max_retries=6):
    if not API_KEY:
        raise RuntimeError("GEMINI_API_KEY not set")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={API_KEY}"
    body = json.dumps({
        "contents": contents,
        "tools": [{"functionDeclarations": [KUML_EXAMPLES_FUNCTION_DECLARATION]}],
    }).encode("utf-8")
    delay = 3
    last_err = None
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 429 and "PerDay" in payload:
                raise DailyQuotaExceeded(payload[:300])
            if e.code in (429, 500, 503) and attempt < max_retries - 1:
                last_err = f"HTTP {e.code}: {payload[:200]}"
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"Gemini HTTP {e.code}: {payload[:300]}")
        except (urllib.error.URLError, TimeoutError) as e:
            last_err = e
            time.sleep(delay)
            delay = min(delay * 2, 60)
    raise RuntimeError(f"Gemini API: exhausted {max_retries} retries: {last_err}")


def call_gemini_with_examples(model, prompt):
    contents = [{"role": "user", "parts": [{"text": prompt}]}]
    for _round in range(MAX_TOOL_ROUNDS):
        data = _post_generate_content(model, contents)
        candidates = data.get("candidates") or []
        if not candidates:
            return ""
        parts = candidates[0].get("content", {}).get("parts", [])
        function_calls = [p["functionCall"] for p in parts if "functionCall" in p]
        if not function_calls:
            return "\n".join(p.get("text", "") for p in parts if "text" in p)
        contents.append({"role": "model", "parts": parts})
        response_parts = []
        for fc in function_calls:
            result_text = call_kuml_examples_tool(fc.get("args", {}))
            response_parts.append({
                "functionResponse": {
                    "name": fc["name"],
                    "response": {"result": result_text},
                }
            })
        contents.append({"role": "user", "parts": response_parts})
    # Round cap hit: return whatever text the last model turn had, if any.
    last_model_parts = contents[-2]["parts"] if len(contents) >= 2 else []
    return "\n".join(p.get("text", "") for p in last_model_parts if isinstance(p, dict) and "text" in p)


# --------------------------------------------------------------------------
# Driver -- deliberately NOT gcr_common.run_benchmark (hardcodes all three
# DSLs). Structurally identical to run-claude-examples.py's driver; see that
# file for the rationale (kept duplicated per this repo's per-provider-
# script convention).
# --------------------------------------------------------------------------
def _gcr_loop_with_tool_count(gcr_loop, model, task, dsl):
    _local.tool_call_count = 0
    result = gcr_loop(model, task, dsl)
    result["toolCallCount"] = _local.tool_call_count
    return result


def run_kuml_only_benchmark(model, out_path, workers=3, limit=None, ids=None):
    quota_event = threading.Event()
    gcr_loop = make_gcr_loop(call_gemini_with_examples, DailyQuotaExceeded, quota_event)

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
        print(f"Daily quota exhausted -- {remaining} cells still outstanding. Re-run to resume.")
    elif remaining:
        print(f"{remaining} cells did not complete (see per-row status). Re-run to retry them.")
    else:
        print("All cells done.")
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemini-2.5-flash")
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
