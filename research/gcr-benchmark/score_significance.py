#!/usr/bin/env python3
"""Paired significance testing for the kUML-vs-baseline comparison (Paper 3,
Future Work item "statistical significance testing proper").

Section 5's tables report mean (+/-stdev) across 3 samples per (model, DSL)
cell -- a useful but small n=3 summary. This script instead runs a *paired*
test at the (task, sample) granularity: for a given model and metric (SF or
HR), each of the 50 tasks in each of the 3 samples gives one paired
observation (kUML value, baseline value) for the *same* task under the *same*
sample -- up to 150 pairs per model per baseline, far more power than the
n=3 sample-level comparison, and appropriate because pairing removes
per-task difficulty variance rather than averaging it away.

Two tests are reported per (model, baseline, metric) cell:
  - paired t-test (scipy.stats.ttest_rel) -- assumes normally distributed
    paired differences; included because it is the more familiar test.
  - Wilcoxon signed-rank test (scipy.stats.wilcoxon) -- non-parametric, does
    not assume normality, arguably the more defensible choice here since SF
    and HR are bounded [0,1] Jaccard-derived quantities, not naturally normal.

This is a description of the paired-difference distribution, not a claim
about the true population effect beyond this 50-task corpus; see Paper 3
Threats to Validity item 1 for the accompanying caveats (corpus-specific,
not a random sample of "all possible architecture-modeling tasks").

Requires scipy (not otherwise a dependency of this reproducibility package):
    pip install scipy

Usage:
    python3 score_significance.py
"""
import json
import statistics

from scipy.stats import ttest_rel, wilcoxon

from score import score

CORPUS_PATH = "corpus.json"

MODELS = {
    "Claude Sonnet 5": [
        "results/claude-sonnet-5/raw-results.json",
        "results/claude-sonnet-5/raw-results-sample2.json",
        "results/claude-sonnet-5/raw-results-sample3.json",
    ],
    "GPT-4o": [
        "results/gpt-4o/raw-results.json",
        "results/gpt-4o/raw-results-sample2.json",
        "results/gpt-4o/raw-results-sample3.json",
    ],
    "Gemini 2.5 Flash": [
        "results/gemini-2.5-flash/raw-results.json",
        "results/gemini-2.5-flash/raw-results-sample2.json",
        "results/gemini-2.5-flash/raw-results-sample3.json",
    ],
    "Gemini 2.5 Pro": [
        "results/gemini-2.5-pro/raw-results.json",
        "results/gemini-2.5-pro/raw-results-sample2.json",
        "results/gemini-2.5-pro/raw-results-sample3.json",
    ],
}

METRICS = ["sf", "hr"]
BASELINES = ["plantuml", "mermaid"]


def paired_values(sample_rows, tasks, metric, baseline):
    kuml_vals, base_vals = [], []
    for by_key in sample_rows:
        for t in tasks:
            tid = t["id"]
            k = by_key.get((tid, "kuml"))
            b = by_key.get((tid, baseline))
            if k is None or b is None:
                continue
            kuml_vals.append(k[metric])
            base_vals.append(b[metric])
    return kuml_vals, base_vals


def run_tests(kuml_vals, base_vals):
    n = len(kuml_vals)
    diffs = [a - b for a, b in zip(kuml_vals, base_vals)]
    mean_diff = statistics.mean(diffs)
    median_diff = statistics.median(diffs)

    if all(d == 0 for d in diffs):
        t_stat, t_p = float("nan"), 1.0
    else:
        t_stat, t_p = ttest_rel(kuml_vals, base_vals)

    try:
        if all(d == 0 for d in diffs):
            w_stat, w_p = float("nan"), 1.0
        else:
            w_stat, w_p = wilcoxon(kuml_vals, base_vals)
    except ValueError:
        # e.g. all differences identical and nonzero (degenerate rank sum) --
        # extremely unlikely at n up to 150 but guarded rather than crashing.
        w_stat, w_p = float("nan"), float("nan")

    return {
        "n": n,
        "mean_diff": mean_diff,
        "median_diff": median_diff,
        "t_stat": float(t_stat),
        "t_p": float(t_p),
        "wilcoxon_stat": float(w_stat),
        "wilcoxon_p": float(w_p),
    }


def sig_marker(p):
    if p != p:  # NaN
        return "n/a"
    if p < 0.001:
        return "***"
    if p < 0.01:
        return "**"
    if p < 0.05:
        return "*"
    return "ns"


def main():
    tasks = json.load(open(CORPUS_PATH, encoding="utf-8"))
    all_out = {}

    for model, paths in MODELS.items():
        print(f"\n=== {model} — paired kUML vs. baseline, task x sample level (n up to 150) ===")
        print(f"{'Metric':6s} {'vs.':10s} {'n':>4s} {'mean diff':>10s} {'t p':>10s} {'Wilcoxon p':>12s}")

        sample_rows = []
        for path in paths:
            results = json.load(open(path, encoding="utf-8"))
            rows = score(results, tasks)
            by_key = {(r["taskId"], r["dsl"]): r for r in rows}
            sample_rows.append(by_key)

        model_out = {}
        for metric in METRICS:
            for baseline in BASELINES:
                kuml_vals, base_vals = paired_values(sample_rows, tasks, metric, baseline)
                result = run_tests(kuml_vals, base_vals)
                key = f"{metric}_vs_{baseline}"
                model_out[key] = result
                print(f"{metric.upper():6s} {baseline:10s} {result['n']:4d} "
                      f"{result['mean_diff']:+10.3f} "
                      f"{result['t_p']:9.2e}{sig_marker(result['t_p']):>3s} "
                      f"{result['wilcoxon_p']:11.2e}{sig_marker(result['wilcoxon_p']):>3s}")
        all_out[model] = model_out

    with open("results/significance-summary.json", "w") as f:
        json.dump(all_out, f, indent=2)
    print("\nWrote results/significance-summary.json")
    print("Significance markers: *** p<0.001, ** p<0.01, * p<0.05, ns = not significant")


if __name__ == "__main__":
    main()
