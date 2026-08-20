#!/usr/bin/env python3
"""Run the GCR benchmark corpus's kUML cells against GPT-4o, with the real
`kuml.examples` MCP tool available via OpenAI function-calling. Companion to
run-claude-examples.py (same experiment, Anthropic tool-use) and
run-gemini-examples.py (Gemini function-calling) -- see run-claude-
examples.py's module docstring for the full rationale, which applies here
unchanged. Written after the fact to close a gap: GPT-4o had a no-tool
baseline (run-gpt4o.py) but was the only frontier model missing a
kuml.examples arm, so the "which model benefits most from tool access"
ranking was two-thirds complete.

Only the kUML DSL cells are run (50, not 150) -- kuml.examples is kUML-
specific.

Mechanics: OpenAI's tool-call shape differs from both Anthropic's tool-use
and Gemini's function-calling (tools=[{"type":"function","function":{...}}],
response.choices[0].message.tool_calls, each with an id and a
function.arguments JSON *string* rather than a parsed object), but the loop
is structurally the same as the other two scripts: send a request with tools
declared; if the message has tool_calls, execute each against a REAL
kuml-mcp server subprocess (same binary/JSON-RPC-over-stdio mechanism as
run-claude-examples.py -- duplicated here rather than shared, matching this
repo's existing per-provider-script convention), feed back one "tool" role
message per call, and continue (bounded at MAX_TOOL_ROUNDS) until a
content-only message is returned.

Usage:
    export OPENAI_API_KEY=...
    export KUML_MCP_BIN=/path/to/kuml-mcp/build/install/kuml-mcp/bin/kuml-mcp
    python3 run-gpt4o-examples.py --model gpt-4o [--workers 3] [--limit N]
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

API_KEY = os.environ.get("OPENAI_API_KEY")
MAX_TOOL_ROUNDS = 4  # mirrors run-claude-examples.py / run-gemini-examples.py

KUML_MCP_BIN = os.environ.get(
    "KUML_MCP_BIN",
    os.path.join(SCRIPT_DIR, "..", "..", "kuml-mcp", "build", "install", "kuml-mcp", "bin", "kuml-mcp"),
)

# OpenAI's function tool shape: {"type": "function", "function": {name,
# description, parameters}} -- "parameters" is a plain JSON Schema object,
# same content as KUML_EXAMPLES_TOOL's input_schema in run-claude-examples.py.
KUML_EXAMPLES_TOOL = {
    "type": "function",
    "function": {
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
    },
}


class QuotaExceeded(Exception):
    pass


# --------------------------------------------------------------------------
# Real kuml-mcp subprocess client -- identical mechanism to
# run-claude-examples.py / run-gemini-examples.py (duplicated, not shared,
# per this repo's per-provider-script convention).
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
# OpenAI Chat Completions API, tool-use loop
# --------------------------------------------------------------------------
def _post_chat(model, messages, max_retries=6):
    if not API_KEY:
        raise RuntimeError("OPENAI_API_KEY not set")
    url = "https://api.openai.com/v1/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": messages,
        "tools": [KUML_EXAMPLES_TOOL],
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
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            if e.code == 429 and "insufficient_quota" in payload:
                raise QuotaExceeded(payload[:300])
            if e.code in (429, 500, 503) and attempt < max_retries - 1:
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise RuntimeError(f"OpenAI HTTP {e.code}: {payload[:300]}")
    raise RuntimeError("OpenAI call exhausted retries")


def call_gpt4o_with_examples(model, prompt):
    messages = [{"role": "user", "content": prompt}]
    for _round in range(MAX_TOOL_ROUNDS):
        data = _post_chat(model, messages)
        message = data["choices"][0]["message"]
        tool_calls = message.get("tool_calls")
        if not tool_calls:
            return message.get("content") or ""
        messages.append(message)
        for call in tool_calls:
            args = json.loads(call["function"]["arguments"] or "{}")
            result_text = call_kuml_examples_tool(args)
            messages.append({
                "role": "tool",
                "tool_call_id": call["id"],
                "content": result_text,
            })
    # Round cap hit: return whatever content the last assistant message had, if any.
    last_assistant = messages[-2] if isinstance(messages[-2], dict) else None
    return (last_assistant or {}).get("content") or ""


# --------------------------------------------------------------------------
# Driver -- trimmed copy of run_benchmark's resumable-driver shape,
# restricted to the kuml DSL only (see run-claude-examples.py).
# --------------------------------------------------------------------------
def _gcr_loop_with_tool_count(gcr_loop, model, task, dsl):
    _local.tool_call_count = 0
    result = gcr_loop(model, task, dsl)
    result["toolCallCount"] = _local.tool_call_count
    return result


def run_kuml_only_benchmark(model, out_path, workers=3, limit=None, ids=None):
    quota_event = threading.Event()
    gcr_loop = make_gcr_loop(call_gpt4o_with_examples, QuotaExceeded, quota_event)

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
        print(f"Quota exhausted -- {remaining} cells still outstanding. Re-run to resume.")
    elif remaining:
        print(f"{remaining} cells did not complete (see per-row status). Re-run to retry them.")
    else:
        print("All cells done.")
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gpt-4o")
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
