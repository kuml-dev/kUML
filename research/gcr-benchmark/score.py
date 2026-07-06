#!/usr/bin/env python3
"""Score GCR benchmark generation results against the corpus gold standard.

Usage:
    python3 score.py <results.json> [--corpus corpus.json]

<results.json> must be a JSON array of objects with at least:
    taskId, dsl, compiledFirstShot, compiledFinal, iterations, nodes, edges

nodes/edges must follow the normalized convention used throughout corpus.json:
    nodes: "EntityName (kind)"
    edges: "Source -> Target (relationshipKind[, multiplicity])"
            (also accepts the unicode arrows -> / -> and ->)
See docs/handbook/modules/reference/pages/llm-benchmark.adoc for the full
methodology and the prompt primers used to generate results/2026-07-06-claude-sonnet-5/.
"""
import json
import re
import sys
from collections import defaultdict


def normalize_id(raw_id, dsl):
    for suffix in ("-kuml", "-plantuml", "-mermaid"):
        if raw_id.lower().endswith(suffix):
            return raw_id[: -len(suffix)]
    return raw_id


def norm_text(s):
    return re.sub(r"\s+", " ", s.strip().lower())


def node_key(s):
    m = re.match(r"^(.*?)\s*\(([^)]+)\)\s*$", s)
    return norm_text(m.group(1)) if m else norm_text(s)


def parse_edge(s):
    m = re.search(r"\(([^)]*)\)\s*$", s)
    kind = m.group(1).split(",")[0].strip().lower() if m else ""
    body = s[: m.start()] if m else s
    ci = body.find(" : ")
    if ci != -1:
        body = body[:ci]
    am = re.match(r"^(.*?)\s*(?:◆→|→|->)\s*(.*?)\s*$", body)
    if am:
        return norm_text(am.group(1)), norm_text(am.group(2)), kind
    return norm_text(body), "", kind


def edge_key(s):
    src, tgt, kind = parse_edge(s)
    return f"{src}|{tgt}|{kind}"


def jaccard(a_list, b_list, key_fn):
    a, b = set(key_fn(x) for x in a_list), set(key_fn(x) for x in b_list)
    if not a and not b:
        return 1.0
    union = len(a | b)
    return len(a & b) / union if union else 1.0


def hallucination_rate(gen_nodes, gold_nodes):
    if not gen_nodes:
        return 0.0
    gold_names = [node_key(n) for n in gold_nodes]
    halluc = sum(
        1
        for gn in gen_nodes
        if not any(
            node_key(gn) == g or (len(node_key(gn)) > 2 and (g in node_key(gn) or node_key(gn) in g))
            for g in gold_names
        )
    )
    return halluc / len(gen_nodes)


def score(results, tasks):
    task_by_id = {t["id"]: t for t in tasks}
    rows = []
    for r in results:
        dsl = r["dsl"].lower()
        tid = normalize_id(r["taskId"], dsl)
        task = task_by_id[tid]
        sf_nodes = jaccard(r["nodes"], task["goldNodes"], node_key)
        sf_edges = jaccard(r["edges"], task["goldEdges"], edge_key)
        rows.append({
            "taskId": tid, "dsl": dsl, "family": task["family"], "difficulty": task["difficulty"],
            "compiledFirstShot": r["compiledFirstShot"], "compiledFinal": r["compiledFinal"],
            "iterations": r["iterations"], "status": r.get("status"),
            "sfNodes": sf_nodes, "sfEdges": sf_edges, "sf": (sf_nodes + sf_edges) / 2,
            "hr": hallucination_rate(r["nodes"], task["goldNodes"]),
        })
    return rows


def aggregate(rows):
    n = len(rows)
    if n == 0:
        return None
    succ = [r for r in rows if r["compiledFinal"]]
    return {
        "n": n,
        "CR": sum(r["compiledFirstShot"] for r in rows) / n,
        "FCR": sum(r["compiledFinal"] for r in rows) / n,
        "SF": sum(r["sf"] for r in rows) / n,
        "SF_nodes": sum(r["sfNodes"] for r in rows) / n,
        "SF_edges": sum(r["sfEdges"] for r in rows) / n,
        "HR": sum(r["hr"] for r in rows) / n,
        "RI": (sum(r["iterations"] for r in succ) / len(succ)) if succ else float("nan"),
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    results_path = sys.argv[1]
    corpus_path = "corpus.json"
    if "--corpus" in sys.argv:
        corpus_path = sys.argv[sys.argv.index("--corpus") + 1]

    results = json.load(open(results_path, encoding="utf-8"))
    tasks = json.load(open(corpus_path, encoding="utf-8"))
    rows = score(results, tasks)

    def pct(x):
        return f"{100 * x:.1f}%"

    print(f"{'DSL':10s} {'n':>4s} {'CR':>7s} {'FCR':>7s} {'SF':>7s} {'HR':>7s} {'RI':>6s}")
    by_dsl = defaultdict(list)
    for r in rows:
        by_dsl[r["dsl"]].append(r)
    for dsl, subset in sorted(by_dsl.items()):
        a = aggregate(subset)
        print(f"{dsl:10s} {a['n']:4d} {pct(a['CR']):>7s} {pct(a['FCR']):>7s} {pct(a['SF']):>7s} {pct(a['HR']):>7s} {a['RI']:6.2f}")


if __name__ == "__main__":
    main()
