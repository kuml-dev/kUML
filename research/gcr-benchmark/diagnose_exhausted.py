#!/usr/bin/env python3
"""Reconstruct the last-attempt compiler diagnostic for every 'exhausted'
kUML cell across all four models' primary datasets, by re-validating each
cell's finalCode against a pinned kuml build. raw-results*.json only stores
the final code + terminal status, not per-attempt diagnostics, so this
recovers attempt-3's diagnostic (the one the model saw last and failed to
act on) -- not attempts 1/2, which are lost. Requires `kuml` on PATH,
ideally the same version the model's samples were originally validated
against (see each results/<model>/*.meta.json for the exact commit).

Categorizes each diagnostic (unresolved_reference, wrong_param_name,
type_mismatch, missing_required_param, syntax_error, other_compiler_error,
unclassified) and, for unresolved_reference/wrong_param_name, extracts the
offending identifier/parameter name so failure patterns can be tallied
(Paper 3 Section 6.2's Gemini-C4 "undeclared val" finding and Section 6.3's
cross-model SysML 2 isAbstract/specializesId primer-gap finding were both
produced this way).

Usage:
    python3 diagnose_exhausted.py [--models claude-sonnet-5,gpt-4o,...]
"""
import argparse
import json
import re
import tempfile
from collections import Counter

from gcr_common import validate_kuml

CORPUS_PATH = "corpus.json"
ALL_MODELS = ["claude-sonnet-5", "gpt-4o", "gemini-2.5-flash", "gemini-2.5-pro"]
SAMPLES = ["raw-results.json", "raw-results-sample2.json", "raw-results-sample3.json"]


def categorize(diag):
    d = diag.lower()
    m = re.search(r"no parameter with name '([^']+)' found", d)
    if m:
        return "wrong_param_name", m.group(1)
    m = re.search(r"unresolved reference '([^']+)'", d)
    if m:
        return "unresolved_reference", m.group(1)
    if "type mismatch" in d:
        return "type_mismatch", None
    if "none of the following functions can be called" in d or "overload resolution ambiguity" in d:
        return "overload_resolution", None
    m = re.search(r"no value passed for parameter '([^']+)'", d)
    if m:
        return "missing_required_param", m.group(1)
    if "expecting" in d and ("'{'" in d or "')'" in d or "eof" in d):
        return "syntax_error", None
    if re.search(r"\berror:", d):
        return "other_compiler_error", None
    if "exception" in d:
        return "runtime_exception", None
    return "unclassified", None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=",".join(ALL_MODELS))
    ap.add_argument("--out", default="results/exhausted-diagnostics.json")
    args = ap.parse_args()
    models = args.models.split(",")

    tasks = {t["id"]: t for t in json.load(open(CORPUS_PATH, encoding="utf-8"))}

    all_entries = []
    for model_dir in models:
        per_model = []
        for sample_name in SAMPLES:
            path = f"results/{model_dir}/{sample_name}"
            try:
                rows = json.load(open(path, encoding="utf-8"))
            except FileNotFoundError:
                continue
            for r in rows:
                if r.get("status") != "exhausted" or r.get("dsl") != "kuml":
                    continue
                tid = r["taskId"]
                family = tasks[tid]["family"]
                with tempfile.TemporaryDirectory() as wd:
                    _, diag, _, _ = validate_kuml(r["finalCode"], family, wd)
                cat, detail = categorize(diag)
                entry = {
                    "model": model_dir, "sample": sample_name, "taskId": tid,
                    "family": family, "category": cat, "detail": detail,
                    "diag_excerpt": diag.strip()[:400],
                }
                per_model.append(entry)
                all_entries.append(entry)
        print(f"{model_dir}: {len(per_model)} exhausted kUML cells re-validated")

    with open(args.out, "w") as f:
        json.dump(all_entries, f, indent=2)

    print()
    for model_dir in models:
        subset = [e for e in all_entries if e["model"] == model_dir]
        if not subset:
            continue
        cats = Counter(e["category"] for e in subset)
        print(f"=== {model_dir}: failure categories ({len(subset)} cells) ===")
        for cat, n in cats.most_common():
            print(f"  {cat:25s} {n:3d}  ({100*n/len(subset):.0f}%)")
        fam = Counter(e["family"] for e in subset)
        print(f"  by family: {dict(fam)}")
        print()

    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
