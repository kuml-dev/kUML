#!/usr/bin/env python3
"""Render SVGs for a model's GCR benchmark results and merge per-cell metrics
into the kuml.dev gallery manifest, additively (never touches the existing
default-model `variants` key or its SVG files at
`public/benchmark-gallery/{taskId}/{dsl}.svg`).

New models get their own SVGs at
`public/benchmark-gallery/{taskId}/{dsl}--{model-key}.svg` and their metrics
under a new `modelVariants[model-key]` key per task in the manifest, so the
existing default (Claude Sonnet 5) rendering is completely unaffected.

Usage:
    python3 build-manifest.py --model-key gpt-4o --results results/gpt-4o/raw-results.json
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score import score  # noqa: E402
from gcr_common import EXT, run  # noqa: E402

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CORPUS_PATH = os.path.join(SCRIPT_DIR, "corpus.json")
KUML_DEV = "/Users/irakli/WebstormProjects/kuml.dev"
MANIFEST_PATH = os.path.join(KUML_DEV, "src/data/gcr-benchmark-manifest.json")
SVG_OUT_DIR = os.path.join(KUML_DEV, "public/benchmark-gallery")


def render_svg(dsl, code, out_path):
    with tempfile.TemporaryDirectory() as workdir:
        path = os.path.join(workdir, "gen" + EXT[dsl])
        with open(path, "w") as f:
            f.write(code)
        svg_tmp = os.path.join(workdir, "out.svg")
        if dsl == "kuml":
            rc, out = run(["kuml", "render", path, "-f", "svg", "-o", svg_tmp], workdir)
        elif dsl == "plantuml":
            rc, out = run(["plantuml", "-tsvg", path], workdir)
            svg_tmp = os.path.join(workdir, "gen.svg")
        else:
            rc, out = run(["mmdc", "-i", path, "-o", svg_tmp], workdir)
        if rc != 0 or not os.path.exists(svg_tmp):
            return False, out
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(svg_tmp, "rb") as src, open(out_path, "wb") as dst:
            dst.write(src.read())
        return True, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-key", required=True, help="slug used in manifest/filenames, e.g. gpt-4o")
    ap.add_argument("--results", required=True, help="path to a raw-results.json with finalCode per row")
    args = ap.parse_args()

    tasks = json.load(open(CORPUS_PATH))
    results = json.load(open(args.results))
    rows = {(r["taskId"], r["dsl"]): r for r in results}
    scored = {(r["taskId"], r["dsl"]): r for r in score(results, tasks)}

    manifest = json.load(open(MANIFEST_PATH))
    by_id = {t["id"]: t for t in manifest}

    rendered, skipped, failed_render = 0, 0, 0
    for t in tasks:
        tid = t["id"]
        for dsl in ("kuml", "plantuml", "mermaid"):
            key = (tid, dsl)
            row = rows.get(key)
            s = scored.get(key)
            if row is None or s is None:
                continue
            entry = by_id.setdefault(tid, {
                "id": tid, "title": t["title"], "family": t["family"], "difficulty": t["difficulty"],
                "diagramType": t["diagramType"], "nl": t["nl"], "variants": {},
            })
            entry.setdefault("modelVariants", {}).setdefault(args.model_key, {})
            entry["modelVariants"][args.model_key][dsl] = {
                "sf": round(s["sf"], 4), "hr": round(s["hr"], 4),
                "compiledFirstShot": row["compiledFirstShot"], "compiledFinal": row["compiledFinal"],
                "iterations": row["iterations"], "status": row["status"],
            }
            out_path = os.path.join(SVG_OUT_DIR, tid, f"{dsl}--{args.model_key}.svg")
            code = row.get("finalCode")
            if not row["compiledFinal"] or not code:
                skipped += 1
                continue
            ok, err = render_svg(dsl, code, out_path)
            if ok:
                rendered += 1
            else:
                failed_render += 1
                print(f"RENDER FAILED {tid} {dsl}: {err[:200]}", file=sys.stderr)

    with open(MANIFEST_PATH, "w") as f:
        json.dump(list(by_id.values()), f, indent=2)
        f.write("\n")

    print(f"Rendered {rendered} SVGs, skipped {skipped} (no diagram/failed compile), {failed_render} render failures.")
    print(f"Manifest updated: {MANIFEST_PATH}")


if __name__ == "__main__":
    main()
