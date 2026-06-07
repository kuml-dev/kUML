# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.5.0] — 2026-06-06

### SysML 2 — complete diagram-type series (8/8)

This release closes the SysML 2 diagram-type series. All eight SysML 2 diagram kinds
are now supported end-to-end (metamodel → DSL → layout bridge → SVG + LaTeX renderer
→ CLI). Twelve atomic waves (V2.0.1 → V2.0.12) delivered over the V2 line.

#### Diagram types added

- **BDD** (Block Definition Diagram) — V2.0.3 + V2.0.4
- **IBD** (Internal Block Diagram) — V2.0.6
- **UC** (Use Case Diagram) — V2.0.7
- **REQ** (Requirement Diagram) — V2.0.8
- **STM** (State Transition Diagram) — V2.0.9
- **ACT** (Activity Diagram) — V2.0.10
- **SEQ** (Sequence Diagram) — V2.0.11
- **PAR** (Parametric Diagram) — V2.0.12

#### Other V2 additions

- `kuml update check` / `kuml update notes` — version + release-notes subcommands (V2.0.1)
- LaTeX / TikZ export pipeline via `kuml-io-latex` (V2.0.2)
- SDKMAN! release pipeline (V2.0.5; vendor-onboarding async)

#### Architecture notes

- `Sysml2Model.usages` is now the typed view of all usages parallel to the KerML
  feature view on definitions (V2.0.6 architecture bonus)
- SEQ renders messages directly from the model instead of via LayoutGraph edges —
  ELK is unsuited to axis-constrained sequence layouts (V2.0.11)
- All eight `Sysml2Diagram` sealed sub-types are exhaustively dispatched across
  `RenderPipeline`, `GradlePipeline`, and `DiagramExtractor` — Kotlin compiler
  guarantees no consumer can miss a diagram kind

#### Test coverage

~263 new tests across the twelve V2 waves. Full `./gradlew check` green on every commit.

#### V2.x-deferred polish

Edge labels (UC/REQ/STM/ACT/PAR stereotypes), PNG export for SysML 2, typed
constraint-expression AST, SEQ Combined Fragments + Execution Specs, ACT Activity
Partitions / Swimlanes, Behaviour-Runtime hookup — all explicitly deferred to V2.x
waves.

## [Unreleased]

### Features

- Markdown integration: `MarkdownProcessor` library + `kuml markdown` CLI
  subcommand with `inline` / `linked-svg` / `linked-png` modes
- Pandoc Lua filter (`tools/pandoc/kuml.lua`) for HTML / PDF rendering
- Maven Central publishing infrastructure (vanniktech plugin) for all
  library modules; applications (`kuml-cli`, `kuml-mcp`, `kuml-llm-bench`)
  remain unpublished
- GitHub Release workflow with `git-cliff` changelog generation
- Homebrew formula template (`Formula/kuml.rb`)

### Documentation

- Getting-Started guide (`docs/getting-started.adoc`)
- Diagram-Type Reference (`docs/diagram-types.adoc`)
- LLM benchmark guide + reproducible mock report
  (`docs/benchmark/README.md`, `docs/benchmark/benchmark-report-mock.md`)
- Release & distribution walkthrough (`docs/release.md`)

### Tests

- `DynamicDiagramBuilderTest` closes the C4 DSL coverage gap

> Once a `v*.*.*` tag is pushed, the release workflow re-generates this file
> automatically from the Conventional-Commit history via `cliff.toml`.
