# kUML LLM Benchmark

The benchmark suite measures how reliably an LLM emits **valid** modelling DSL
for three target languages — kuml, PlantUML, and Mermaid — across 10 tasks in
German and English.

## What it measures

- **Valid syntax** (script compiles / fences match)
- **Schema fidelity** (required diagram elements present)
- **Per-tool success rate** (head-to-head: kuml vs. PlantUML vs. Mermaid)

## Mock report (in-repo)

A mock run shipped at [`benchmark-report-mock.md`](./benchmark-report-mock.md)
uses `LlmMockBackend` to validate the harness itself. It is **not** a
benchmark of any real LLM — it's a reproducible smoke test that the
runner, validators and tasks load and execute.

> The mock backend returns the canonical reference solution for each kuml
> task and a deliberately broken stub for PlantUML / Mermaid tasks, so the
> mock result shows kUML at 100 % and the others at 0 %. **This is the
> harness verifying itself, not the LLM.**

## Live reports

Each live run lives in this directory as
`benchmark-report-<backend>-<YYYYMMDD>.md`. So far:

| Date       | Model                         | Tool success                          | Overall |
|------------|-------------------------------|---------------------------------------|---------|
| 2026-06-02 | `claude-haiku-4-5-20251001`   | kuml 33 % · PlantUML 100 % · Mermaid 100 % | 60 % |

→ [`benchmark-report-claude-haiku-4-5-20260602.md`](./benchmark-report-claude-haiku-4-5-20260602.md)

### Interpretation of the first run

Haiku 4.5 handles the established DSLs (PlantUML, Mermaid) without a hitch
but stumbles on kUML in two ways:

1. **Type confusion in extended class diagrams** — passes a `String` where
   the DSL expects a `UmlClassifier` (e.g. `extends("Order")` instead of
   `extends(order)`).
2. **Wrong / missing C4 entrypoints** — emits unqualified calls such as
   `c4Model { … }` against the wrong scope, or invents pseudo-DSL verbs
   like `softwareSystem.includes(…)`.

Both failure modes point at the system prompt rather than the model: kUML's
DSL is new to the training data, so a few-shot prompt with a worked example
is likely to lift the rate substantially. The benchmark is doing its job —
this is exactly the signal it was built to produce.

### Running it yourself

```bash
export ANTHROPIC_API_KEY=sk-...
./gradlew :kuml-llm:kuml-llm-bench:run
cp kuml-llm/kuml-llm-bench/benchmark-report.md \
   docs/benchmark/benchmark-report-<model>-$(date +%Y%m%d).md
```

## Adding your own backend

`kuml-llm-core` exposes the `LlmBackend` interface. Implement it for OpenAI,
Gemini, Ollama, etc., then plug it into `BenchmarkRunner.run(...)`. See
[`kuml-llm/kuml-llm-anthropic`](../../kuml-llm/kuml-llm-anthropic/README.adoc)
for a 100-LOC reference implementation.

## Tasks

The current task set lives in
[`kuml-llm/kuml-llm-bench/src/main/kotlin/dev/kuml/llm/bench/BenchmarkTasks.kt`](../../kuml-llm/kuml-llm-bench/src/main/kotlin/dev/kuml/llm/bench/BenchmarkTasks.kt).
Each task carries a natural-language prompt and a validator that asserts the
emitted DSL parses and contains required elements.

Submit new tasks via PR — keep prompts under 100 words and validators
deterministic.
