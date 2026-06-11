# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Layout — ELK back as default engine

`kuml.grid` was the default for Class, Component, and UseCase diagrams since V2.0.26.
It is now **opt-in** (`--layout=grid`); ELK (`elk.layered`) is the default for all
diagram types again until the grid engine reaches feature parity.

**Changed:**
- `RenderPipeline`: removed `GRID_DEFAULT_KINDS`; `pickEngine()` now always requests
  `elk.layered` unless the user passes `--layout=grid` or the DSL sets
  `kuml.layout.engine = "kuml.grid"`.
- `WebRenderPipeline`: same change; `GRID_DEFAULT_KINDS` removed.
- Engine registration order flipped: ELK is now registered before Grid so that
  `LayoutEngineRegistry.pickFor(kind, null)` also returns ELK.
- `RenderCommand` help text updated to reflect ELK as the default.
- Grid layout can still be activated per-diagram via the DSL:
  ```kotlin
  classDiagram(name = "...") {
      metadata { put(LayoutMetadataKeys.ENGINE, KumlMetaValue.Text("kuml.grid")) }
  }
  ```
  or globally via `--layout=grid`.

## [0.7.0] — 2026-06-10

### Behaviour Runtime — Snapshot/Restore + MigrationPolicy (V2.0.35)
Full snapshot/restore cycle for `StateMachineInstance` and `ActivityInstance` with
configurable migration strategies.

**New API** in `kuml-runtime-core` (package `dev.kuml.runtime.snapshot`):
- `StateMachineRuntime.snapshotFull(instance)` — captures current vertices, variable
  scope, internal event queue, trace, sequence counter, and termination flag
- `StateMachineRuntime.restoreFrom(model, snapshot, policy)` — rebuilds an instance
  from a snapshot; validates with the chosen `MigrationPolicy`
- `ActivityRuntime.snapshotFull(instance, …)` / `restoreFrom(snapshot, policy)` — same
  contract for activity diagrams
- `MigrationPolicy` — sealed interface: `Reject` (default — any model change throws),
  `AcceptIfFingerprintMatches`, `AcceptIfVerticesPresent` (allows additive changes),
  `Custom`
- `SnapshotIo` — `writeStateMachineSnapshot` / `readStateMachineSnapshot` helpers

**Bug fix**: the previous `snapshot()`/`restore()` path silently lost the internal event
queue and the sequence counter on round-trip. `snapshotFull` preserves both. The old API
is retained for MCP compatibility.

### Web UI — LaTeX Download (V2.0.36)
`POST /api/render` in `kuml-web` now accepts `format = "latex"`.

- All 10 diagram types supported (UML, C4, all 8 SysML 2 diagram types)
- `standaloneTex = true` wraps output in a `\documentclass{standalone}` preamble
- Browser SPA gains a third download button "↓ LaTeX (.tex)" and a standalone-mode
  checkbox alongside the existing SVG and PNG buttons
- No breaking change: `standaloneTex` defaults to `false`; existing clients unaffected

### JetBrains IDE Plugin — Code Folding (V2.0.37)
Code folding for all kUML DSL blocks in `.kuml.kts` files.

- Folds `umlModel`, `classOf`, `interfaceOf`, `enumOf`, `componentOf`, `stateMachine`,
  `c4Model`, `sysml2Model`, `diagram`, `actDiagram`, `stmDiagram`, `bdd`, `ibd`, `uc`,
  `req`, `seq`, `par`, and 7 definition-level blocks (`partDef`, `stateDef`, …)
- Placeholder text shows the first string argument: `classOf("User") {…}`
- Guard: only activates on `*.kuml.kts` files; no impact on other Kotlin files
- `DumbAware` — works during project indexing

### CLI — `kuml run` (V2.0.38)
New subcommand for interactive and live-mirror execution of state machines and activity
diagrams.

**Three adapters**:
- `--adapter stdin` *(default)* — interactive REPL; reads events as `eventName {payload}`
  lines; built-in commands `snapshot`, `status`, `quit`
- `--adapter mcp` — starts a JDK `HttpServer` (no new dependencies) on `--port N` (0 =
  random free port). Five REST endpoints:
  - `POST /run/event` — fire an event; returns fired transitions + active states
  - `GET  /run/snapshot` — current state + variable scope
  - `POST /run/patch` — update variables or force-transition to a named state
  - `POST /run/stop` — terminate session and shut down the server
  - `GET  /run/health` — liveness probe
- `--adapter batch` — loads `--events <file.json>`, runs to completion, writes trace via
  `--out`

**Options**:
- `--restore <snapshot.json>` — resume from a `StateMachineSnapshot` (V2.0.35) instead of
  starting fresh
- `--migration reject|fingerprint|vertices` — MigrationPolicy when restoring (default:
  `fingerprint`)
- `--snapshot-out <path>` — persist snapshot on session end

**New exit codes**: `RUN_PORT_BUSY = 20`, `RUN_MIGRATION_REJECTED = 21`

**Supports**: UML state machines, SysML 2 STM, SysML 2 ACT

### Web UI — `kuml-web` (V2.0.34)
New executable module `kuml-web` provides a Ktor/Netty HTTP server with a browser-based
editing and preview environment for kUML scripts.

**REST API**:
- `POST /api/render` — evaluates a `*.kuml.kts` script source (UML, C4, or SysML 2) and
  returns SVG or PNG; supports `theme` and `layout` overrides
- `GET /api/themes` — lists registered theme names
- `GET /api/examples` / `GET /api/examples/{name}` — three bundled example scripts
  (UML class diagram, C4 container diagram, SysML 2 BDD)
- `GET /api/health`

**Browser SPA**:
- CodeMirror 6 editor (ESM from esm.sh CDN — no build step required)
- Live SVG preview with 300 ms debounce
- Theme and layout (auto / grid / elk) dropdowns
- One-click SVG and PNG download
- Examples picker to load any bundled script into the editor

**CLI**: `kuml serve [--port N] [--host H]` — new subcommand that starts the web server

### Dependency and toolchain updates
- Kotlin upgraded from 2.3.21 to 2.4.0; K2 strictness fixes in example scripts
- All library dependencies updated to latest stable versions

## [0.6.0] — 2026-06-09

### M2M Transformation (V2.0.22–V2.0.25)
Four new transformers join `uml-to-jpa` (V2.0.21):
- `uml-to-rest` — OpenAPI 3.0 YAML from UML class diagrams
- `uml-to-k8s` — Kubernetes Deployment + Service manifests per component
- `uml-to-docker` — Dockerfile per component
- `c4-to-uml` — C4 model → UML class diagram script
`kuml transform --list-transformers` now lists all five.

### Layout (V2.0.26)
Grid layout is now the default engine for Class, Component, and UseCase diagrams.
State diagrams continue to use ELK for compact vertical layouts with curved back-edges.
`kuml render --layout=grid|elk|auto` overrides the per-diagram default.
New `LayoutHintWriter` API for drag-and-drop editor round-trips.

### Behaviour Runtime via MCP (V2.0.27)
Five new MCP tools expose the headless runtime to external agents:
`kuml.run.start`, `kuml.run.event`, `kuml.run.snapshot`, `kuml.run.patch`, `kuml.run.stop`.
Both STM (state machines) and ACT (activity diagrams) are supported.

### JetBrains IDE Plugin — full authoring experience (V2.0.28a/b, V2.0.30)
- Annotator: inline error squiggles in `.kuml.kts` files
- Four IntentionAction quick fixes (missing parameter, unknown parameter, rename, suppress)
- Live SVG preview pane in split editor (Batik JSVGCanvas, 300 ms debounce, zoom/pan)
- Structure view showing diagram element tree

### CLI improvements (V2.0.31)
- `kuml validate` now includes structural checks: duplicate IDs, circular inheritance, dangling references. Flag `--no-check-structure` to opt out.
- `kuml render --latex-standalone` produces a compilable `.tex` file (was library-only before).

### Distribution Phase 1 (V2.0.32)
Native installers via `jpackage`:
- `packageDeb` / `packageRpm` (Linux, unsigned)
- `packageDmg` (macOS, unsigned — signing in Phase 2)
- `packageMsi` (Windows, unsigned — signing in Phase 2)
- `dockerBuildCli` — `ghcr.io/kuml-dev/kuml-cli:<version>` Docker image
New CI workflow `release-installers.yml` builds all four on push to version tags.

### SDKMAN! — Windows + Linux ARM64
The `package-runtime` matrix and SDKMAN! release matrix now include:
- `windows-x86_64` → `WINDOWS_64`
- `linux-aarch64` → `LINUX_ARM64` (via QEMU on ubuntu-latest)

### Showcases (V2.0.29, V2.0.19)
- Keysight Car2x V2X intersection scenario: 5-state SysML 2 STM with V2X message exchanges, three event files, runnable via `kuml simulate`
- Pepela Smart Home thermostat: STM + ACT, Golden-Trace tests

### UML Sequence and State Machine renderer
Native UML `sequenceDiagram` and `stateDiagram` scripts now render correctly end-to-end:

- **SEQ**: `UmlInteraction` bridge computes lifeline heights from message count (reusing SysML 2 SEQ constants). Renderer uses the same renderer-direct path as SysML 2 SEQ — messages, combined fragments, and execution specs are drawn without edge routing through ELK.
- **STATE**: `UmlStateMachine` bridge creates a LayoutGroup frame + LayoutNodes for all vertices (states, pseudostates, final states) + LayoutEdges for transitions. Renderer dispatches per vertex kind: filled circle (initial), donut (final), rounded box (state), with `trigger [guard] / effect` transition labels.
- `NodeRendererDispatcher` extended with `UmlLifeline`, `UmlPseudostate`, `UmlFinalState` dispatch cases.
- New `.kuml-frame` CSS class for SVG state machine frame borders.

### Handbook
All reference documentation updated for V2.x: SysML 2 diagram types, runtime-MCP tools, `kuml validate` page, CLI command table, IntelliJ plugin sections.

## [0.5.1] — 2026-06-07

### SysML 2 polish (post-v0.5.0)

#### Edge labels & arrowheads (V2.0.13)
All five stereotype-bearing diagram types now render proper labels on edges:
- UC: «include» (dashed + open angle) / «extend» (dashed + open angle)
- REQ: «satisfy» / «verify» / «deriveReqt» / «containment» (all dashed + open angle)
- STM: `trigger [guard] / effect` above transition arrows
- ACT: `[guard]` on ControlFlow / `[ObjectType]` on ObjectFlow
- PAR: binding connectors with correct solid line style

Implemented via a shared `Sysml2EdgeAdapter` interface in `kuml-metamodel-sysml2` so both SVG and LaTeX renderers share the same metadata mapping.

#### PNG export for all SysML 2 diagram types (V2.0.14)
`kuml render --format png` no longer throws for SysML 2 scripts. All 8 diagram types produce valid PNG output via the existing Batik transcoder path.

#### SEQ: Combined Fragments + Execution Specifications + Create/Destroy (V2.0.15)
- Combined Fragments (`alt`, `opt`, `loop`, `par`, `break`, `critical`, `strict`, `seq`) rendered as dashed frames with operator-tag pentagon and operand guard labels
- Execution Specifications rendered as thin vertical activation bars on lifelines
- `Create` message kind: arrow to lifeline head box with «create» stereotype
- `Destroy` message kind: arrow to lifeline with «destroy» stereotype + X marker

#### ACT: Activity Partitions (Swimlanes) + Pins (V2.0.16)
- Activity Partitions rendered as vertical lanes with dashed borders and header bars
- Actions can be assigned to partitions via `partition = myPartition` parameter
- Action Pins (typed input/output ports) rendered as small squares on action box edges

#### STM: Behaviour-Runtime hookup (V2.0.17)
`kuml simulate` now accepts SysML 2 scripts in addition to UML scripts. A new `Sysml2StateMachineAdapter` translates `StateDefinition` + `TransitionUsage` into the existing `StateMachineRuntime` — guards, triggers, entry/exit actions and trace output all work as expected.

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

> Once a `v*.*.*` tag is pushed, the release workflow re-generates this file
> automatically from the Conventional-Commit history via `cliff.toml`.
