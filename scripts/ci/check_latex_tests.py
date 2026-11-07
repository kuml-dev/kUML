#!/usr/bin/env python3
"""Skip guard for the strict LaTeX CI job (job `latex-pdf`, Nacharbeit 34).

Reads the Gradle test XML of the pdflatex test classes and fails when a pdflatex test was
skipped, when one failed, when a class ran nothing or fewer tests than its floor, or when a
class is missing altogether. Also checks that the three copies of Pdflatex.kt are identical
apart from the package line.

Usage (from the repository root): python3 -I scripts/ci/check_latex_tests.py
There is deliberately no allow-list and no exception route: any skipped pdflatex test fails
the job (Nacharbeit 35 removed the last "known compile defect" marker).
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

RESULT_GLOBS = [
    "kuml-io/kuml-io-latex/build/test-results/test/*.xml",
    "kuml-docs/kuml-workspace-latex/build/test-results/test/*.xml",
    "kuml-cli/build/test-results/test/*.xml",
]
# class simple name -> predicate on the test name (None = every test of the class counts)
CLASSES = {
    "NoteTikzPdfBuildTest": None,
    "BpmnCollaborationPdfBuildTest": None,
    "LatexKnowledgePdfBuildTest": None,
    "LatexFolderPdfBuildTest": None,
    "WorkspaceLatexKnowledgePdfBuildTest": None,
    "LatexStandaloneAllFamiliesTest": lambda name: name.startswith("pdflatex compiles"),
}
# class simple name -> minimum number of executed tests (a floor against silently dropped tests)
MIN_EXECUTED = {
    "BpmnCollaborationPdfBuildTest": 2,
    "LatexStandaloneAllFamiliesTest": 15,
}
SUPPORT_COPIES = [
    "kuml-io/kuml-io-latex/src/test/kotlin/dev/kuml/io/latex/note/Pdflatex.kt",
    "kuml-docs/kuml-workspace-latex/src/test/kotlin/dev/kuml/workspace/latex/Pdflatex.kt",
    "kuml-cli/src/test/kotlin/dev/kuml/cli/Pdflatex.kt",
]


def check_copies(errors):
    bodies = []
    for path in SUPPORT_COPIES:
        if not os.path.isfile(path):
            errors.append(f"missing {path}")
            return
        with open(path, encoding="utf-8") as f:
            bodies.append("".join(line for line in f if not line.startswith("package ")))
    if len(set(bodies)) != 1:
        errors.append("the three Pdflatex.kt copies differ (apart from the package line)")


def main():
    errors = []
    stats = {c: {"executed": 0, "skipped": 0, "failed": 0} for c in CLASSES}
    files = [f for g in RESULT_GLOBS for f in sorted(glob.glob(g))]
    if not files:
        errors.append("no test result XML found; did the tests run?")
    for path in files:
        for case in ET.parse(path).getroot().iter("testcase"):
            cls = (case.get("classname") or "").split(".")[-1].split("$")[0]
            if cls not in CLASSES:
                continue
            name = case.get("name") or ""
            pred = CLASSES[cls]
            if pred is not None and not pred(name):
                continue
            s = stats[cls]
            if case.find("skipped") is not None:
                s["skipped"] += 1
                errors.append(f"{cls}: skipped: {name}")
            elif case.find("failure") is not None or case.find("error") is not None:
                s["failed"] += 1
                errors.append(f"{cls}: failed: {name}")
            else:
                s["executed"] += 1
    print(f"{'class':40} {'executed':>8} {'skipped':>8} {'failed':>7}")
    for cls, s in stats.items():
        print(f"{cls:40} {s['executed']:>8} {s['skipped']:>8} {s['failed']:>7}")
        if s["executed"] + s["skipped"] + s["failed"] == 0:
            errors.append(f"{cls}: not present in the results")
        elif s["executed"] < MIN_EXECUTED.get(cls, 1):
            errors.append(f"{cls}: only {s['executed']} test(s) executed, expected at least {MIN_EXECUTED.get(cls, 1)}")
    check_copies(errors)
    if errors:
        print("\nLaTeX skip guard FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print("\nLaTeX skip guard passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
