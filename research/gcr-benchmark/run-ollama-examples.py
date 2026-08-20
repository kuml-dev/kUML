#!/usr/bin/env python3
"""Run the GCR benchmark corpus's kUML cells against a local Ollama model,
with the real `kuml.examples` MCP tool available via Ollama's OpenAI-
compatible tool-calling. Companion to run-ollama.py (the "without
kuml.examples" baseline) and the cloud kuml.examples arms
(run-claude-examples.py, run-gemini-examples.py, run-gpt4o-examples.py) --
see run-claude-examples.py's module docstring for the full rationale, which
applies here unchanged.

Only the kUML DSL cells are run (50, not 150) -- kuml.examples is
kUML-specific.

Mechanics: identical wire format to run-gpt4o-examples.py (Ollama's
`/v1/chat/completions` tool-calling shape is OpenAI-compatible), pointed at
a local Ollama server instead of the OpenAI API and without an API key. Not
every locally-served model honors tool-calling reliably -- some may never
emit a tool_calls block even when the tool would help (see the handbook's
MCP tool-use arm section for a cloud-side example of this: Gemini 2.5 Flash
called kuml_examples in only 18 of 50 cells). Report the observed
`toolCallCount` per cell regardless -- a model that never calls the tool is
itself a data point, not a script bug.

Usage:
    ollama serve                       # if not already running
    ollama pull <model>
    python3 run-ollama-examples.py --model <model> [--workers 1] [--limit N]
"""
import argparse
import concurrent.futures
import json
import os
import subprocess
import sys
import threading
import urllib.error
import urllib.request

from gcr_common import (
    CORPUS_PATH,
    DONE_STATUSES,
    SCRIPT_DIR,
    make_gcr_loop,
    sanitize_for_path,
)

OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://localhost:11434")
MAX_TOOL_ROUNDS = 4  # mirrors run-gpt4o-examples.py

KUML_MCP_BIN = os.environ.get(
    "KUML_MCP_BIN",
    os.path.join(SCRIPT_DIR, "..", "..", "kuml-mcp", "build", "install", "kuml-mcp", "bin", "kuml-mcp"),
)

# Same OpenAI-compatible function-tool shape as run-gpt4o-examples.py.
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


class OllamaUnreachable(Exception):
    """Stand-in for the cloud scripts' "stop the whole run" exception --
    see run-ollama.py. Never a quota condition; used for a dead/unreachable
    server or an unpulled model."""


# --------------------------------------------------------------------------
# Real kuml-mcp subprocess client -- identical mechanism to the cloud
# kuml.examples scripts (duplicated, not shared, per this repo's
# per-provider-script convention).
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
# Ollama's OpenAI-compatible /v1/chat/completions, tool-use loop
# --------------------------------------------------------------------------
def _post_chat(model, messages, max_retries=2):
    url = f"{OLLAMA_HOST}/v1/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": messages,
        "tools": [KUML_EXAMPLES_TOOL],
        "temperature": 0.2,
    }).encode("utf-8")
    for attempt in range(max_retries):
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
        }, method="POST")
        try:
            # Local inference on CPU can be slow; generous timeout, mirrors run-ollama.py.
            with urllib.request.urlopen(req, timeout=600) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.URLError as e:
            raise OllamaUnreachable(
                f"Cannot reach Ollama at {OLLAMA_HOST}: {e}. Is it running? ('ollama serve')"
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


def call_ollama_with_examples(model, prompt):
    messages = [{"role": "user", "content": prompt}]
    for _round in range(MAX_TOOL_ROUNDS):
        data = _post_chat(model, messages)
        message = data["choices"][0]["message"]
        tool_calls = message.get("tool_calls")
        if not tool_calls:
            return message.get("content") or ""
        messages.append(message)
        for call in tool_calls:
            raw_args = call["function"]["arguments"]
            args = raw_args if isinstance(raw_args, dict) else json.loads(raw_args or "{}")
            result_text = call_kuml_examples_tool(args)
            messages.append({
                "role": "tool",
                "tool_call_id": call.get("id", ""),
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


def run_kuml_only_benchmark(model, out_path, workers=1, limit=None, ids=None):
    quota_event = threading.Event()
    gcr_loop = make_gcr_loop(call_ollama_with_examples, OllamaUnreachable, quota_event)

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
    if remaining:
        print(f"{remaining} cells did not complete (see per-row status). Re-run to retry them.")
    else:
        print("All cells done.")
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="llama3.2")
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--ids", default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    if not os.path.exists(KUML_MCP_BIN):
        sys.exit(
            f"kuml-mcp binary not found at {KUML_MCP_BIN} -- build it first: "
            f"./gradlew :kuml-mcp:installDist (or set KUML_MCP_BIN)"
        )

    out = args.out or os.path.join(SCRIPT_DIR, "results", f"{sanitize_for_path(args.model)}-with-examples", "raw-results.json")
    run_kuml_only_benchmark(args.model, out, args.workers, args.limit, args.ids)


if __name__ == "__main__":
    main()
