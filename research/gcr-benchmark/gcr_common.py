#!/usr/bin/env python3
"""Shared GCR-benchmark logic: prompt primers, validation/extraction per DSL,
the generic Generate-Compile-Repair loop, and the resumable run driver.

Provider-specific scripts (run-gemini.py, run-gpt4o.py, ...) only need to
supply a `call_fn(model, prompt) -> str` and, optionally, an exception type
that signals "stop the whole run, quota exhausted for today" so run_benchmark
can resume cleanly tomorrow instead of burning through retries.

Only the Class-diagram primer text is preserved verbatim from the published
handbook page (docs/handbook/modules/reference/pages/llm-benchmark.adoc). The
other 9 DSL x family primers were never saved as standalone artifacts from the
Claude run (they lived only in that run's now-gone workflow-agent prompts) and
are reconstructed here from corpus.json's validated gold-standard code, in the
same style/token budget documented in the handbook. Not byte-identical to what
Claude Sonnet 5 saw, but methodologically equivalent — and identical across
every provider script that imports this module, which is what keeps the
cross-model comparison fair.
"""
import concurrent.futures
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CORPUS_PATH = os.path.join(SCRIPT_DIR, "corpus.json")

# --------------------------------------------------------------------------
# Prompt primers — one per (dsl, family). Kept compact (~150-300 tokens) plus
# a worked example with generic entity names, per the documented methodology.
# --------------------------------------------------------------------------

KUML_PRIMERS = {
    "ClassDiagram": '''kUML class diagram DSL. Use exactly this shape:

classDiagram(name = "ModelName") {
    val a = classOf(name = "A") {
        isAbstract = true
        attribute(name = "field", type = "String", isReadOnly = true)
        operation(name = "doThing") { parameter(name = "x", type = "Int"); returns(typeName = "Boolean") }
    }
    val sub = classOf(name = "Sub") { extends(general = a) }
    association(source = a, target = sub) {
        aggregation = AggregationKind.COMPOSITE
        source { multiplicity(spec = "1") }
        target { multiplicity(spec = "0..*"); role = "items" }
    }
}

aggregation is one of AggregationKind.NONE / SHARED / COMPOSITE (omit the whole
`aggregation = ...` line for a plain association). Declare every class as a
`val` before referencing it in `association`/`extends`. Output ONLY the kUML
code, no markdown fences, no commentary.''',
    "SequenceDiagram": '''kUML sequence diagram DSL. Use exactly this shape:

sequenceDiagram(name = "ModelName") {
    val a = lifeline(name = "A") { isActor = true }
    val b = lifeline(name = "B")
    message(from = a, to = b, label = "doThing()")
    reply(from = b, to = a, label = "result")
}

`isActor = true` marks a human/external actor lifeline (omit for a system
lifeline). `message` is a synchronous call, `reply` is its return — order
matters, messages render in declaration order. Declare every lifeline as a
`val` before referencing it. Output ONLY the kUML code, no markdown fences,
no commentary.''',
    "C4": '''kUML C4 container diagram DSL. Use exactly this shape:

c4Model(name = "ModelName") {
    val user = person(name = "User") { description = "A person" }
    val sys = softwareSystem(name = "System") {
        container(name = "API") { technology = "Kotlin/Ktor"; description = "Handles requests" }
    }
    val ext = softwareSystem(name = "External") { external = true }
    relationship(source = user, target = sys) { technology = "HTTPS"; description = "Uses" }
    relationship(source = sys, target = ext) { technology = "REST"; description = "Calls" }
    containerDiagram(name = "ModelName - Container View") { system = sys; showExternalSystems = true }
}

Every model MUST end with a `containerDiagram { system = ...; ... }` block
naming which softwareSystem to render. Declare every person/softwareSystem as
a `val` before referencing it in `relationship`. Output ONLY the kUML code, no
markdown fences, no commentary.''',
    "SysML2": '''kUML SysML 2.0 block definition diagram (BDD) DSL. Use exactly this shape:

import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "ModelName") {
    val a = partDef(name = "A")
    val b = partDef(name = "B") {
        part(name = "a", typeId = a.id)
    }
    bdd(name = "ModelName BDD") {
        include(b)
        include(a)
    }
}

`part(name=..., typeId=...)` inside a partDef body declares a whole-part
composition to another partDef. The model MUST end with a `bdd { include(...) }`
block listing every partDef to render. Declare every partDef as a `val` before
referencing its `.id`. Output ONLY the kUML code (including the import line),
no markdown fences, no commentary.''',
}

PLANTUML_PRIMERS = {
    "ClassDiagram": '''PlantUML class diagram. Use exactly this shape:

@startuml
abstract class A
class Sub
A "1" *-- "0..*" Sub : items
Sub <|-- A
@enduml

`*--` is composition, `o--` is aggregation, `--` is a plain association,
`<|--` is generalization (points from subclass to superclass). Output ONLY
the PlantUML code, no markdown fences, no commentary.''',
    "SequenceDiagram": '''PlantUML sequence diagram. Use exactly this shape:

@startuml
actor A
participant B
A -> B : doThing()
B --> A : result
@enduml

`->` is a synchronous call, `-->` is its return/reply. Use `actor` for a
human/external participant, `participant` for a system one. Output ONLY the
PlantUML code, no markdown fences, no commentary.''',
    "C4": '''PlantUML C4 container diagram (C4-PlantUML stdlib, bundled locally). Use
exactly this shape:

@startuml
!include <C4/C4_Container>
Person(user, "User", "A person")
System_Boundary(sysB, "System") {
  Container(api, "API", "Kotlin/Ktor", "Handles requests")
}
System_Ext(ext, "External", "Third party")
Rel(user, api, "Uses", "HTTPS")
Rel(api, ext, "Calls", "REST")
@enduml

Output ONLY the PlantUML code, no markdown fences, no commentary.''',
    "SysML2": '''PlantUML has no native SysML 2 support. Transliterate the block
definition diagram (BDD) into plain class notation with composition arrows —
the realistic fallback a developer would reach for. Use exactly this shape:

@startuml
class A
class B
B *-- A : "1"
@enduml

Each SysML "part" (partDef) becomes a `class`; each whole-part relationship
becomes a `*--` composition line from the whole to the part. Output ONLY the
PlantUML code, no markdown fences, no commentary.''',
}

MERMAID_PRIMERS = {
    "ClassDiagram": '''Mermaid class diagram. Use exactly this shape:

classDiagram
  class A
  class B
  A "1" *-- "0..*" B : items
  A <|-- B

`*--` is composition, `o--` is aggregation, `--` is a plain association,
`<|--` is generalization (points from superclass to subclass). Output ONLY
the Mermaid code, no markdown fences, no commentary.''',
    "SequenceDiagram": '''Mermaid sequence diagram. Use exactly this shape:

sequenceDiagram
  actor A
  participant B
  A->>B: doThing()
  B-->>A: result

`->>` is a synchronous call, `-->>` is its return/reply. Use `actor` for a
human/external participant, `participant` for a system one. Output ONLY the
Mermaid code, no markdown fences, no commentary.''',
    "C4": '''Mermaid C4 container diagram (native `C4Container` diagram type). Use
exactly this shape:

C4Container
  Person(user, "User", "A person")
  System_Boundary(sysB, "System") {
    Container(api, "API", "Kotlin/Ktor", "Handles requests")
  }
  System_Ext(ext, "External", "Third party")
  Rel(user, api, "Uses", "HTTPS")
  Rel(api, ext, "Calls", "REST")

Output ONLY the Mermaid code, no markdown fences, no commentary.''',
    "SysML2": '''Mermaid has no native SysML 2 support. Transliterate the block
definition diagram (BDD) into plain class notation with composition arrows —
the realistic fallback a developer would reach for. Use exactly this shape:

classDiagram
  class A
  class B
  A *-- B : "1"

Each SysML "part" (partDef) becomes a `class`; each whole-part relationship
becomes a `*--` composition line from the whole to the part. Output ONLY the
Mermaid code, no markdown fences, no commentary.''',
}

PRIMERS = {"kuml": KUML_PRIMERS, "plantuml": PLANTUML_PRIMERS, "mermaid": MERMAID_PRIMERS}

EXT = {"kuml": ".kuml.kts", "plantuml": ".puml", "mermaid": ".mmd"}


def strip_fences(text):
    m = re.search(r"```(?:\w+)?\n(.*?)```", text, re.S)
    return m.group(1).strip() if m else text.strip()


# --------------------------------------------------------------------------
# Validation + normalized node/edge extraction per DSL
# --------------------------------------------------------------------------

def run(cmd, cwd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=60)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def get_kuml_version():
    """Best-effort `kuml --version` capture for reproducibility bookkeeping.

    The original single-sample benchmark run (2026-07-06) never recorded which
    kuml build/PATH resolution produced its results, which made an exact-version
    replication for the 3-samples-per-cell study impossible to verify after the
    fact (only reconstructable from Homebrew-tap timestamps). This function
    exists so every subsequent run leaves a verifiable trail. Never raises: an
    unavailable or misbehaving `kuml` just yields an explanatory string instead
    of failing the whole benchmark run.
    """
    kuml_path = shutil.which("kuml")
    if not kuml_path:
        return {"kuml_path": None, "kuml_version": "kuml not found on $PATH"}
    try:
        p = subprocess.run(["kuml", "--version"], capture_output=True, text=True, timeout=10)
        # Prefer stdout (the actual version line); JVM native-access warnings
        # land on stderr and would otherwise pollute the recorded string.
        version_str = (p.stdout or "").strip() or ((p.stdout or "") + (p.stderr or "")).strip()
    except Exception as e:
        version_str = f"kuml --version failed: {e}"
    return {"kuml_path": kuml_path, "kuml_version": version_str}


def record_run_metadata(out_path):
    """Appends a {kuml_path, kuml_version, recorded_at} snapshot to
    "<out_path-without-ext>.meta.json" every time a run starts (including
    resumes), so later samples/replications can check whether the same
    renderer/compiler build produced this run. See Paper 3, Threats to
    Validity, "One sample per cell" / version-drift discussion."""
    meta_path = os.path.splitext(out_path)[0] + ".meta.json"
    entries = []
    if os.path.exists(meta_path):
        try:
            entries = json.load(open(meta_path))
        except Exception:
            entries = []
    entry = get_kuml_version()
    entry["recorded_at"] = datetime.datetime.now().isoformat(timespec="seconds")
    entries.append(entry)
    os.makedirs(os.path.dirname(meta_path) or ".", exist_ok=True)
    with open(meta_path, "w") as f:
        json.dump(entries, f, indent=2)
    print(f"kuml version recorded: {entry['kuml_version']} -> {meta_path}", file=sys.stderr)


def type_suffix(t):
    return t.rsplit(".", 1)[-1]


def extract_kuml_classlike(diagram_json):
    nodes, edges, id2name = [], [], {}
    for el in diagram_json.get("elements", []):
        t = type_suffix(el.get("@type", ""))
        if t in ("UmlClass", "UmlInterface", "UmlEnumeration", "UmlAbstractClass", "UmlDataType"):
            id2name[el["id"]] = el["name"]
            nodes.append(f"{el['name']} (class)")
    for el in diagram_json.get("elements", []):
        t = type_suffix(el.get("@type", ""))
        if t == "UmlGeneralization":
            spec = id2name.get(el.get("specificId"), el.get("specificId"))
            gen = id2name.get(el.get("generalId"), el.get("generalId"))
            edges.append(f"{spec} -> {gen} (generalization)")
        elif t == "UmlAssociation":
            ends = el.get("ends", [])
            if len(ends) == 2:
                agg = el.get("aggregation", "NONE")
                kind = {"COMPOSITE": "composition", "SHARED": "aggregation"}.get(agg, "association")
                src = id2name.get(ends[0].get("typeId"), ends[0].get("typeId"))
                tgt = id2name.get(ends[1].get("typeId"), ends[1].get("typeId"))
                edges.append(f"{src} -> {tgt} ({kind})")
        elif t == "UmlDependency":
            src = id2name.get(el.get("clientId"), el.get("clientId"))
            tgt = id2name.get(el.get("supplierId"), el.get("supplierId"))
            edges.append(f"{src} -> {tgt} (dependency)")
    return nodes, edges


def extract_kuml_sequence(diagram_json):
    nodes, edges, id2name = [], [], {}
    for el in diagram_json.get("elements", []):
        if type_suffix(el.get("@type", "")) == "UmlInteraction":
            for ll in el.get("lifelines", []):
                id2name[ll["id"]] = ll["name"]
                nodes.append(f"{ll['name']} (lifeline)")
            for m in sorted(el.get("messages", []), key=lambda x: x.get("sequence", 0)):
                src = id2name.get(m.get("fromLifelineId"), m.get("fromLifelineId"))
                tgt = id2name.get(m.get("toLifelineId"), m.get("toLifelineId"))
                kind = "reply" if m.get("sort") == "REPLY" else "message"
                edges.append(f"{src} -> {tgt} : {m.get('label', '')} ({kind})")
    return nodes, edges


def extract_kuml_c4(model_json):
    nodes, edges, id2name = [], [], {}
    kind_map = {"C4Person": "person", "C4SoftwareSystem": "softwareSystem", "C4Container": "container", "C4Component": "component"}
    for el in model_json.get("elements", []):
        t = type_suffix(el.get("@type", ""))
        id2name[el["id"]] = el["name"]
        nodes.append(f"{el['name']} ({kind_map.get(t, t.lower())})")
    for rel in model_json.get("relationships", []):
        src = id2name.get(rel.get("source"), rel.get("source"))
        tgt = id2name.get(rel.get("target"), rel.get("target"))
        edges.append(f"{src} -> {tgt} (relationship)")
    return nodes, edges


def extract_kuml_sysml2(model_json):
    nodes, edges, id2name = [], [], {}
    defs = model_json.get("definitions", [])
    for d in defs:
        id2name[d["id"]] = d["name"]
        nodes.append(f"{d['name']} (partDef)")
    for d in defs:
        for f in d.get("features", []):
            tgt_id = f.get("typeId") or f.get("definitionId")
            tgt = id2name.get(tgt_id, tgt_id)
            edges.append(f"{d['name']} -> {tgt} (composition)")
    return nodes, edges


def validate_kuml(code, family, workdir):
    path = os.path.join(workdir, "gen.kuml.kts")
    with open(path, "w") as f:
        f.write(code)
    rc, out = run(["kuml", "render", path, "-f", "svg", "-o", os.path.join(workdir, "out.svg")], workdir)
    if rc != 0:
        return False, out, [], []
    diagram_out = os.path.join(workdir, "diagram.json")
    layout_out = os.path.join(workdir, "layout.json")
    model_out = os.path.join(workdir, "model.json")
    if family in ("C4", "SysML2"):
        rc2, out2 = run(["kuml", "dump-json", path, "--diagram-out", diagram_out, "--layout-out", layout_out, "--model-out", model_out], workdir)
        if rc2 != 0 or not os.path.exists(model_out):
            return True, out, [], []  # compiled/rendered but extraction failed - still counts as compile success
        data = json.load(open(model_out))
        nodes, edges = (extract_kuml_c4(data) if family == "C4" else extract_kuml_sysml2(data))
    else:
        rc2, out2 = run(["kuml", "dump-json", path, "--diagram-out", diagram_out, "--layout-out", layout_out], workdir)
        if rc2 != 0 or not os.path.exists(diagram_out):
            return True, out, [], []
        data = json.load(open(diagram_out))
        nodes, edges = (extract_kuml_sequence(data) if family == "SequenceDiagram" else extract_kuml_classlike(data))
    return True, out, nodes, edges


CLASS_DECL_RE = re.compile(r'^\s*(?:abstract\s+)?class\s+"?([^"{\n]+?)"?\s*(?:as\s+\w+)?\s*\{?\s*$', re.M)
GENERALIZATION_RE = re.compile(r'^\s*(\S+)\s*(<\|--|--\|>)\s*(\S+)', re.M)
ASSOC_RE = re.compile(r'^\s*(\S+)\s*(?:"[^"]*"\s*)?(\*--|o--|--)\s*(?:"[^"]*"\s*)?(\S+)', re.M)


def extract_classlike_text(code):
    nodes = [f"{m.group(1).strip()} (class)" for m in CLASS_DECL_RE.finditer(code)]
    edges = []
    for m in GENERALIZATION_RE.finditer(code):
        a, op, b = m.group(1), m.group(2), m.group(3)
        edges.append(f"{(b if op == '<|--' else a)} -> {(a if op == '<|--' else b)} (generalization)")
    for m in ASSOC_RE.finditer(code):
        a, op, b = m.group(1), m.group(2), m.group(3)
        kind = {"*--": "composition", "o--": "aggregation"}.get(op, "association")
        edges.append(f"{a} -> {b} ({kind})")
    return nodes, edges


SEQ_PARTICIPANT_RE = re.compile(r'^\s*(?:actor|participant)\s+"?([^"\n]+?)"?\s*(?:as\s+(\w+))?\s*$', re.M)
SEQ_MSG_RE = re.compile(r'^\s*(\S+)\s*(-->>|->>|-->|->)\s*(\S+?)\s*:\s*(.*)$', re.M)


def extract_sequence_text(code):
    nodes = []
    for m in SEQ_PARTICIPANT_RE.finditer(code):
        display, alias = m.group(1), m.group(2)
        nodes.append(f"{alias or display} (lifeline)")
    edges = []
    for m in SEQ_MSG_RE.finditer(code):
        src, op, tgt, label = m.groups()
        kind = "reply" if op in ("-->", "-->>") else "message"
        edges.append(f"{src} -> {tgt} : {label.strip()} ({kind})")
    return nodes, edges


C4_ELEM_RE = re.compile(r'\b(Person|Person_Ext|System|System_Ext|System_Boundary|Container|ContainerDb|Container_Ext|Component)\s*\(\s*(\w+)\s*,\s*"([^"]*)"', re.M)
C4_REL_RE = re.compile(r'\bRel(?:_\w+)?\s*\(\s*(\w+)\s*,\s*(\w+)', re.M)
C4_KIND = {"Person": "person", "Person_Ext": "person", "System": "softwareSystem", "System_Ext": "softwareSystem",
           "System_Boundary": "softwareSystem", "Container": "container", "ContainerDb": "container",
           "Container_Ext": "container", "Component": "component"}


def extract_c4_text(code):
    nodes, id2name = [], {}
    for m in C4_ELEM_RE.finditer(code):
        kind, alias, name = m.groups()
        id2name[alias] = name
        nodes.append(f"{name} ({C4_KIND.get(kind, kind.lower())})")
    edges = []
    for m in C4_REL_RE.finditer(code):
        src, tgt = m.groups()
        edges.append(f"{id2name.get(src, src)} -> {id2name.get(tgt, tgt)} (relationship)")
    return nodes, edges


def validate_text_dsl(dsl, family, code, workdir):
    path = os.path.join(workdir, "gen" + EXT[dsl])
    with open(path, "w") as f:
        f.write(code)
    if dsl == "plantuml":
        rc, out = run(["plantuml", "-tsvg", path], workdir)
    else:
        rc, out = run(["mmdc", "-i", path, "-o", os.path.join(workdir, "out.svg")], workdir)
    if rc != 0:
        return False, out, [], []
    if family == "SequenceDiagram":
        nodes, edges = extract_sequence_text(code)
    elif family == "C4":
        nodes, edges = extract_c4_text(code)
    else:
        nodes, edges = extract_classlike_text(code)
    return True, out, nodes, edges


def validate(dsl, family, code, workdir):
    if dsl == "kuml":
        return validate_kuml(code, family, workdir)
    return validate_text_dsl(dsl, family, code, workdir)


# --------------------------------------------------------------------------
# Generic GCR loop + resumable run driver — provider-agnostic
# --------------------------------------------------------------------------

DONE_STATUSES = {"success", "exhausted", "stuck"}


def build_prompt(task, dsl, primer, prior_code=None, diagnostic=None):
    parts = [primer, "", "Task:", task["nl"]]
    if prior_code is not None:
        parts += ["", "Your previous attempt:", prior_code, "", "It failed validation with this diagnostic:", diagnostic,
                  "", "Fix the code. Output ONLY the corrected code, no markdown fences, no commentary."]
    return "\n".join(parts)


def make_gcr_loop(call_fn, quota_exc_type, quota_event):
    """Build a gcr_loop(model, task, dsl) closure bound to a provider's call_fn.

    quota_exc_type: exception class that means "stop the whole run, resume
    later" (e.g. a daily free-tier quota). Pass a type nothing will ever raise
    (e.g. a private dummy class) if the provider has no such concept.
    """
    def gcr_loop(model, task, dsl):
        family = task["family"]
        primer = PRIMERS[dsl][family]
        prior_code, diagnostic = None, None
        first_shot_ok = None
        with tempfile.TemporaryDirectory() as workdir:
            for attempt in range(1, 4):
                if quota_event.is_set():
                    return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": False, "compiledFinal": False,
                            "iterations": 0, "status": "quota_exceeded", "nodes": [], "edges": []}
                prompt = build_prompt(task, dsl, primer, prior_code, diagnostic)
                try:
                    raw = call_fn(model, prompt)
                except quota_exc_type:
                    quota_event.set()
                    return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": bool(first_shot_ok), "compiledFinal": False,
                            "iterations": attempt, "status": "quota_exceeded", "nodes": [], "edges": []}
                except Exception as e:
                    return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": False, "compiledFinal": False,
                            "iterations": attempt, "status": f"api_error: {e}", "nodes": [], "edges": []}
                code = strip_fences(raw)
                if prior_code is not None and code.strip() == prior_code.strip():
                    return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": bool(first_shot_ok), "compiledFinal": False,
                            "iterations": attempt, "status": "stuck", "nodes": [], "edges": [], "finalCode": code}
                ok, diag, nodes, edges = validate(dsl, family, code, workdir)
                if attempt == 1:
                    first_shot_ok = ok
                if ok:
                    return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": bool(first_shot_ok), "compiledFinal": True,
                            "iterations": attempt, "status": "success", "nodes": nodes, "edges": edges, "finalCode": code}
                prior_code, diagnostic = code, diag
            return {"taskId": task["id"], "dsl": dsl, "compiledFirstShot": bool(first_shot_ok), "compiledFinal": False,
                    "iterations": 3, "status": "exhausted", "nodes": [], "edges": [], "finalCode": prior_code}
    return gcr_loop


def run_benchmark(call_fn, quota_exc_type, model, out_path, workers=3, limit=None, ids=None):
    """Resumable driver: runs every (task, dsl) cell not already done in
    out_path, writing incrementally so a quota-interrupted run can resume."""
    quota_event = threading.Event()
    gcr_loop = make_gcr_loop(call_fn, quota_exc_type, quota_event)

    tasks = json.load(open(CORPUS_PATH))
    if ids:
        wanted = set(ids.split(","))
        tasks = [t for t in tasks if t["id"] in wanted]
    elif limit:
        tasks = tasks[:limit]
    all_cells = [(t, dsl) for t in tasks for dsl in ("kuml", "plantuml", "mermaid")]

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    record_run_metadata(out_path)

    results = []
    if os.path.exists(out_path):
        prior = json.load(open(out_path))
        # A row only counts as done if it also has the generated code saved
        # (finalCode) - older runs predating that field get backfilled here.
        results = [r for r in prior if r.get("status") in DONE_STATUSES and r.get("finalCode")]
    completed = {(r["taskId"], r["dsl"]) for r in results}
    cells = [(t, dsl) for t, dsl in all_cells if (t["id"], dsl) not in completed]

    if not cells:
        print(f"All {len(all_cells)} cells already done in {out_path}.")
        return results

    print(f"{len(completed)} cells already done, {len(cells)} remaining.", file=sys.stderr)
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(gcr_loop, model, t, dsl): (t["id"], dsl) for t, dsl in cells}
        for fut in concurrent.futures.as_completed(futures):
            tid, dsl = futures[fut]
            try:
                r = fut.result()
            except Exception as e:
                r = {"taskId": tid, "dsl": dsl, "compiledFirstShot": False, "compiledFinal": False,
                     "iterations": 0, "status": f"error: {e}", "nodes": [], "edges": []}
            results.append(r)
            done += 1
            print(f"[{done}/{len(cells)}] {tid} {dsl}: {r['status']}", file=sys.stderr)
            with open(out_path, "w") as f:
                json.dump(results, f, indent=2)

    remaining = len(all_cells) - len({(r["taskId"], r["dsl"]) for r in results if r.get("status") in DONE_STATUSES})
    print(f"\nWrote {len(results)} rows to {out_path}.")
    if quota_event.is_set():
        print(f"Quota/rate limit hit — {remaining} cells still outstanding. Re-run to resume.")
    elif remaining:
        print(f"{remaining} cells did not complete (see per-row status). Re-run to retry them.")
    else:
        print("All cells done.")
    return results
