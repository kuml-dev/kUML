#!/usr/bin/env python3
"""Aggregate CR/FCR/SF/HR/RI across the 3 GCR-benchmark samples per (model, dsl)
cell, reporting mean and sample standard deviation (n=3) instead of the single
point estimates in score.py. This is the scoring counterpart to the
3-samples-per-cell replication (Paper 3, Threats to Validity, "One sample per
cell"): sample 1 is each model's original results/<model>/raw-results.json,
samples 2/3 are the raw-results-sample{2,3}.json files added in the
replication run.

Usage:
    python3 score_multisample.py

Reuses score()/aggregate() from score.py unchanged so the per-sample numbers
are computed identically to the single-sample tables already in the paper.
"""
import json
import statistics
from collections import defaultdict

from score import score, aggregate

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

METRICS = ["CR", "FCR", "SF", "HR", "RI"]


def pct(x):
    return f"{100 * x:.1f}%"


def fmt_mean_sd(values, is_pct=True):
    m = statistics.mean(values)
    sd = statistics.stdev(values) if len(values) > 1 else 0.0
    if is_pct:
        return f"{100*m:.1f}% (±{100*sd:.1f})"
    return f"{m:.2f} (±{sd:.2f})"


def main():
    tasks = json.load(open(CORPUS_PATH, encoding="utf-8"))
    all_out = {}

    for model, paths in MODELS.items():
        print(f"\n=== {model} — mean (±stdev) across 3 samples ===")
        print(f"{'DSL':10s} {'CR':>16s} {'FCR':>16s} {'SF':>16s} {'HR':>16s} {'RI':>14s}")

        # per_sample[dsl][metric] = [sample1_val, sample2_val, sample3_val]
        per_sample = defaultdict(lambda: defaultdict(list))
        raw_per_sample = defaultdict(list)  # dsl -> list of per-sample aggregate dicts, for JSON dump

        for path in paths:
            results = json.load(open(path, encoding="utf-8"))
            rows = score(results, tasks)
            by_dsl = defaultdict(list)
            for r in rows:
                by_dsl[r["dsl"]].append(r)
            for dsl, subset in by_dsl.items():
                a = aggregate(subset)
                for metric in METRICS:
                    per_sample[dsl][metric].append(a[metric])
                raw_per_sample[dsl].append(a)

        model_out = {}
        for dsl in sorted(per_sample.keys()):
            cr = fmt_mean_sd(per_sample[dsl]["CR"])
            fcr = fmt_mean_sd(per_sample[dsl]["FCR"])
            sf = fmt_mean_sd(per_sample[dsl]["SF"])
            hr = fmt_mean_sd(per_sample[dsl]["HR"])
            ri_vals = [v for v in per_sample[dsl]["RI"] if v == v]  # drop NaN (no successes)
            ri = fmt_mean_sd(ri_vals, is_pct=False) if ri_vals else "n/a"
            print(f"{dsl:10s} {cr:>16s} {fcr:>16s} {sf:>16s} {hr:>16s} {ri:>14s}")
            model_out[dsl] = {
                "samples": raw_per_sample[dsl],
                "mean": {m: statistics.mean(per_sample[dsl][m]) for m in METRICS if m != "RI"},
                "stdev": {m: (statistics.stdev(per_sample[dsl][m]) if len(per_sample[dsl][m]) > 1 else 0.0)
                          for m in METRICS if m != "RI"},
                "RI_mean": statistics.mean(ri_vals) if ri_vals else None,
                "RI_stdev": (statistics.stdev(ri_vals) if len(ri_vals) > 1 else 0.0) if ri_vals else None,
            }
        all_out[model] = model_out

    with open("results/multisample-summary.json", "w") as f:
        json.dump(all_out, f, indent=2)
    print("\nWrote results/multisample-summary.json")


if __name__ == "__main__":
    main()
