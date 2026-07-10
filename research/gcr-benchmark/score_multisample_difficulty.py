#!/usr/bin/env python3
"""Per-difficulty breakdown of the 3-sample mean/stdev, mirroring Paper 3's
Tables 3/5/7/9 ("<Model> by difficulty"). Same score()/aggregate() reuse."""
import json
import statistics
from collections import defaultdict

from score import score, aggregate

CORPUS_PATH = "corpus.json"

MODELS = {
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

METRICS = ["CR", "FCR", "SF", "HR"]
DIFF_ORDER = ["Simple", "Medium", "Complex"]
DSL_ORDER = ["kuml", "plantuml", "mermaid"]


def fmt(values):
    m = statistics.mean(values)
    sd = statistics.stdev(values) if len(values) > 1 else 0.0
    return f"{100*m:.1f}% (+/-{100*sd:.1f})"


def fmt_ri(values):
    values = [v for v in values if v == v]
    if not values:
        return "n/a"
    m = statistics.mean(values)
    sd = statistics.stdev(values) if len(values) > 1 else 0.0
    return f"{m:.2f} (+/-{sd:.2f})"


def main():
    tasks = json.load(open(CORPUS_PATH, encoding="utf-8"))
    all_out = {}

    for model, paths in MODELS.items():
        print(f"\n=== {model} by difficulty — mean (+/-stdev) across 3 samples ===")
        print(f"{'Difficulty':11s} {'DSL':10s} {'CR':>16s} {'FCR':>16s} {'SF':>16s} {'HR':>16s} {'RI':>14s}")

        per_sample = defaultdict(lambda: defaultdict(list))
        for path in paths:
            results = json.load(open(path, encoding="utf-8"))
            rows = score(results, tasks)
            by_dd = defaultdict(list)
            for r in rows:
                by_dd[(r["difficulty"], r["dsl"])].append(r)
            for (difficulty, dsl), subset in by_dd.items():
                a = aggregate(subset)
                for metric in METRICS:
                    per_sample[(difficulty, dsl)][metric].append(a[metric])
                per_sample[(difficulty, dsl)]["RI"].append(a["RI"])

        model_out = {}
        for difficulty in DIFF_ORDER:
            for dsl in DSL_ORDER:
                key = (difficulty, dsl)
                if key not in per_sample:
                    continue
                cr = fmt(per_sample[key]["CR"])
                fcr = fmt(per_sample[key]["FCR"])
                sf = fmt(per_sample[key]["SF"])
                hr = fmt(per_sample[key]["HR"])
                ri = fmt_ri(per_sample[key]["RI"])
                print(f"{difficulty:11s} {dsl:10s} {cr:>16s} {fcr:>16s} {sf:>16s} {hr:>16s} {ri:>14s}")
                model_out[f"{difficulty}|{dsl}"] = {
                    "CR": per_sample[key]["CR"], "FCR": per_sample[key]["FCR"],
                    "SF": per_sample[key]["SF"], "HR": per_sample[key]["HR"],
                    "RI": per_sample[key]["RI"],
                }
        all_out[model] = model_out

    with open("results/multisample-difficulty-summary.json", "w") as f:
        json.dump(all_out, f, indent=2)
    print("\nWrote results/multisample-difficulty-summary.json")


if __name__ == "__main__":
    main()
