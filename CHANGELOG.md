# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

**V3.1.42 — `kuml run --adapter chain-evm`: Wire EVM Chain Adapter into the Run Command**

New files in `kuml-cli/src/main/kotlin/dev/kuml/cli/run/`:
- `EvmUrlValidator` — validates EVM RPC URLs (http/https only; rejects private IP ranges
  10.x, 172.16–31.x, 192.168.x, 127.x, ::1 via `RpcUrlValidator.Default` SSRF guard)
  and contract addresses (40 hex chars, optional 0x prefix). `normalizeContract()` ensures
  the required 0x prefix before calling `EvmChainAdapter.connect()`.
- `ChainEvmCliOptions` — value holder (`data class`) for `--rpc`, `--contract`,
  `--from-block`, and `--chain-id` CLI options.
- `ChainEvmAdapterRunner` — bridges `RunCommand` to `EvmChainAdapter`. Connects, verifies
  on-chain `modelHash` against the local script hash (exit code 50 `CHAIN_HASH_MISMATCH` on
  mismatch), then feeds chain events into the running `RunSessionManager` session. Uses
  `replay(fromBlock)` when `--from-block` is set, otherwise the infinite `subscribe()` Cold
  Flow (guarded by `takeWhile { !manager.isTerminated }` to ensure clean termination).
  `EvmChainAdapterException.ReorgDetected` exits with `CHAIN_CONNECT_ERROR` (51).

Modified:
- `RunCommand.kt` — adds `"chain-evm"` to the `--adapter` choice; adds `--rpc`,
  `--contract`, `--from-block (.long())`, and `--chain-id (.int())` options; dispatches to
  `ChainEvmAdapterRunner` with full validation before connect.

New tests in `kuml-cli/src/test/kotlin/dev/kuml/cli/run/`:
- `EvmUrlValidatorTest` — 15 unit tests covering https/http accept, file:// reject, all
  private-range IP literals, IPv6 loopback, contract address format variants, and prefix
  normalization.
- `ChainEvmRunCommandTest` — integration tests using `FakeChainAdapter` (no network/testnet):
  subscribe vs replay dispatch, hash mismatch exit code, network error friendly message,
  SSRF and contract validation.

**V3.1.41 — EMF Profile Conversion: kUML Profile ⇌ Eclipse UML2 `.profile.uml`**

New source files in `kuml-io/kuml-io-emf`:
- `KumlProfileToEmfConverter` — converts a `KumlProfile` (from `kuml-profile-api`) into
  an Eclipse UML2 `Profile` model. Encodes each stereotype's `targetMetaclass` as a
  sentinel owned attribute (`_kuml_metaclass_<Name>`) because the Eclipse P2 resource
  `org.eclipse.uml2.uml.resources` (required for real metaclass proxies) is not available
  on Maven Central. All metadata (namespace, version, description, extendsProfiles, property
  required/default/min) is preserved via EAnnotations with source `dev.kuml.profile`.
- `EmfProfileToKumlConverter` — reads an Eclipse UML2 `Profile` back into a `KumlProfile`
  via the existing profile DSL builder. Recovers metaclass from the sentinel attribute,
  tag properties from remaining owned attributes, and types via explicit `when`-dispatch
  (String/Int/Long/Double/Boolean). Enum-typed properties fall back to `String::class`
  (documented limitation — enum class may not be on the classpath at import time).
- `ProfileXmiExporter` — writes a `KumlProfile` to a `.profile.uml` XMI file with a
  `uml:Profile` root element (distinct from `XmiWriter` which uses `uml:Model`). Public
  no-arg constructor enables CLI reflection-loading.
- `ProfileXmiImporter` — reads a `.profile.uml` XMI file via `ResourceSetImpl` and delegates
  to `EmfProfileToKumlConverter`. Provides `import(File): KumlProfile` (throws) and
  `importResult(File): ProfileResult` (catches all `Throwable` → `ProfileResult.Failure`).
- `ProfileResult` — sealed class `Success(profile)` / `Failure(message, cause)` for
  safe error handling on malformed files.
- `build.gradle.kts` of `kuml-io-emf` gains `implementation(project(":kuml-profile:kuml-profile-api"))`
  and test deps on `kuml-profile-autosar`, `-spring`, `-javaee` for round-trip tests.
- 47 new tests across `KumlProfileToEmfConverterTest`, `EmfProfileToKumlConverterTest`,
  `AutosarProfileRoundtripTest`, `SpringProfileRoundtripTest`, `JavaEeProfileRoundtripTest`,
  `ProfileXmiExporterTest`, `ProfileXmiImporterTest`, `ProfileXmiSerializationTest`.
- `kuml export --format profile-uml <script>` CLI hook — script must return a `KumlProfile`
  as its last expression; the profile is written to a `.profile.uml` XMI file via
  `ProfileXmiExporter` (loaded via reflection; Fat-JAR only, degrades to exit code 24 on
  Native Image). `ExportCommandProfileUmlCliTest` covers the happy path, wrong-type-script,
  default output extension, and FORMAT_NOT_AVAILABLE degradation.
- `kuml-cli/build.gradle.kts` gains `testRuntimeOnly(project(":kuml-io:kuml-io-emf"))` for
  the profile-uml CLI tests.

**V3.1.40 — C# Reverse-Engineering Plugin (Handwritten Structural Parser)**

New Drittanbieter-Plugin `kuml-plugin-examples/plugin-reverse-csharp`:
- `CsharpReversePlugin` — implements `KumlReversePlugin` SPI with id
  `dev.kuml.plugins.reverse-csharp`, capability `REVERSE`, permission `FS_READ`.
- `CsharpReverseEngine` — analyses `.cs` source files in a directory tree and produces
  a `KumlModel` with a `KumlDiagram(type=CLASS)`. Safety limits: 10 MB per file (REV-CS-004),
  2000 files per run (REV-CS-003), symlink-escape guard, `StackOverflowError` catch (REV-CS-005).
- `CsharpLexer` — 4-phase handwritten lexer: strip preprocessor (#if/#region/#pragma),
  strip string literals (regular, verbatim @"...", interpolated $"..."), strip comments,
  tokenise into `CsharpToken` stream.
- `CsharpReverseParser` — recursive-descent structural parser. Supports file-scoped
  namespaces (C# 10: `namespace X.Y;`) and block namespaces; class / abstract class /
  sealed class / static class / interface / struct / record / enum; auto-properties
  (`T Name { get; set; }`); methods; fields; readonly fields; generic type parameters;
  `[Attribute]` lists; base list (`class C : Base, IFace1, IFace2`). Max namespace depth
  guard → REV-CS-002. Never throws on malformed input.
- `CsharpTypeMapper` — maps C# primitive aliases (string, int, bool, …), BCL types
  (Int32, Boolean, …), and generic collections (List<T>, IEnumerable<T>, T[], Dictionary<K,V>,
  HashSet<T>) to UML-friendly names. Depth-guarded (MAX_RECURSION_DEPTH=32).
- `CsharpUmlMapper` — two-pass mapper: first pass collects all declared interface names
  for accurate base-list classification; second pass builds `UmlClass` / `UmlInterface` /
  `UmlEnumeration` / `UmlGeneralization` / `UmlInterfaceRealization`. Duplicate-definition
  guard → REV-CS-006. Base-type classification: known-interface set first, then
  `I`+uppercase heuristic (IShape, IDisposable) as fallback.
- Note: ANTLR4 was evaluated but rejected — no reliable ANTLR4 C# grammar artifact is
  available on Maven Central. Option B (handwritten structural parsing) mirrors the approach
  proven in V3.1.39 for C++.
- 24 Kotest `FunSpec` tests covering: plugin descriptor, engine id, capabilities,
  permissions, simple class, auto-property, method with params, abstract class, interface,
  `UmlInterfaceRealization`, `UmlGeneralization`, mixed inheritance, file-scoped namespace,
  block namespace, dotted namespace, enum, sealed class, record (positional params),
  readonly field, static method, attribute (best-effort), generic property `List<string>`,
  and malformed-input no-crash guard.

**V3.1.36 — ARXML CLI Integration + Comprehensive Roundtrip Tests + Vault Example**

CLI export:
- `kuml export --format arxml <script> [-o output.arxml]` — exports UML/AUTOSAR models to
  AUTOSAR Classic ARXML R22-11 via the Fat-JAR `kuml-io-arxml` module (JVM-only, reflection-loaded).
  Supports `componentDiagram { ... }` scripts (elements wrapped into a synthetic UmlPackage) and
  scripts returning a `UmlPackage` root directly. C4 / SysML 2 / BPMN / Blueprint rejected with
  a clear `SCRIPT_ERROR` message. Derives `.arxml` extension when `-o` is omitted.

CLI reverse:
- `kuml reverse --format arxml <dir> [-o output.kuml.kts]` — reads all `*.arxml` files in the
  given directory (sorted by name), imports each via `ArxmlClassicImporter` (reflection), and
  merges them into a single `KumlModel` via `ArxmlModelMerge`. Packages with the same AR-PACKAGE
  name across files are merged recursively (no duplicate package declarations). Output is a
  `*.kuml.kts` script using raw UML metamodel constructors with AUTOSAR stereotypes preserved.
  Empty directory exits `REVERSE_NO_SOURCES`. Source-language reverse is fully backwards-compatible.

New internal classes:
- `dev.kuml.cli.reverse.ArxmlModelMerge` — pure-Kotlin merge of `List<KumlModel>` into one
  consolidated model; deduplicates `UmlPackage`s by name recursively; no `dev.kuml.io.arxml.*`
  compile-time dependency (GraalVM-safe).
- `dev.kuml.cli.reverse.ArxmlPackageDslPrinter` — prints `KumlModel` (root = `UmlPackage`) as
  a `*.kuml.kts` source with AUTOSAR imports and full metadata preservation.

New tests (20+ total):
- `ArxmlCompositionRoundtripTest` (6 tests) — 3 SWCs / 6 ports / 2 interfaces / 4 runnables;
  import←export←import structural equivalence, zero unresolved TREFs, trigger-type preservation,
  deterministic byte-identical export, no raw XML entities.
- `ArxmlVersionMatrixTest` (5 parametrised cases) — R19_11 through R23_11 parse and round-trip
  with correct schema-label detection.
- `ArxmlAdaptiveManifestRoundtripTest` (3 tests) — SERVICE-MANIFEST / MACHINE-MANIFEST /
  mixed classic+adaptive document.
- `ArxmlComponentRenderTest` (2 tests, kuml-vault-examples-tests) — imported ARXML composition
  renders as non-empty SVG containing all SWC names; interface names present in SVG.
- `ExportCommandArxmlCliTest` (4 tests) — CLI export to ARXML; C4 rejection; extension derivation;
  FORMAT_NOT_AVAILABLE guard.
- `ReverseCommandArxmlCliTest` (4 tests) — multi-file merge without duplicate packages; empty-dir;
  single file; source-language reverse regression guard.

Vault example:
- `03 Bereiche/kUML/Beispiele/35 AUTOSAR Classic – SW-Komponenten.md` — sensor-brake-diag
  SWC composition with 3 components, ports, interfaces, connect() calls; DSL-Anatomie table;
  ARXML export/import shell examples; AUTOSAR background explanation.

**V3.1.32 — SMIL Vault Examples + Vault-Examples SMIL Tests**

Two new animated vault example notes added to `03 Bereiche/kUML/Beispiele/` and synced
into `kuml-tests/kuml-vault-examples-tests/src/test/resources/vault-examples/`:

- `07 BPMN animiert – PdV Mitgliedsantrag.md` — BPMN process diagram showing a PdV
  membership application (Antrag-Prüfung → [Vollständig?] → Bestätigung/Nachforderung)
  with embedded `kuml.trace.v1` JSON for the happy path (Start → Prüfung → Bestätigung → End).
- `08 STM animiert – Traffic Light.md` — UML state machine diagram (via `stateDiagram { }` DSL,
  not SysML 2) for a traffic light (Red/Green/Yellow cycle) with a two-cycle trace.
  Uses the UML path because `StmSmilRenderer` requires a `UmlStateMachine`.

New test spec `VaultExamplesSmilTest` (4 test cases):
  1. *rendert animiert: BPMN PdV Mitgliedsantrag (SMIL)* — asserts `hasAnimation=true`,
     `<animateMotion` present, animated SVG written to `build/sample-output/vault-examples/smil/`.
  2. *rendert animiert: STM Traffic Light (SMIL)* — asserts `hasAnimation=true`,
     `<animate` present, animated SVG written to `build/sample-output/vault-examples/smil/`.
  3. *SMIL strip ist deterministisch: BPMN* — `SmilEmitter.inject(STRIPPED)` removes all
     `<animate*` and `<set ` elements from the animated BPMN SVG.
  4. *SMIL strip ist deterministisch: STM* — same strip assertion for the animated STM SVG.

New helper `AnimatedExampleRenderer` in the test source set: evaluates kUML scripts via
`KumlScriptHost`, routes BPMN to `BpmnSmilRenderer` and UML STM to `StmSmilRenderer`,
parses traces via `KumlRuntimeJson.decodeFromString(TraceFile.serializer(), …)`.

`build.gradle.kts` of `kuml-vault-examples-tests` adds
`testImplementation(project(":kuml-io:kuml-render-smil"))` to expose `SmilEmitter`,
`SmilTimeline`, and `StaticSnapshotMode` on the test classpath.

`scripts/sync-vault-examples.sh` extended with two new case entries:
  - `07 BPMN animiert – PdV Mitgliedsantrag.md` → `bpmn-pdv-mitgliedsantrag-animiert.kuml.kts`
  - `08 STM animiert – Traffic Light.md` → `stm-traffic-light-animiert.kuml.kts`

**V3.1.31 — STM + Activity SMIL Renderers**

- `StmSmilRenderer` — renders UML State Machine diagrams as optionally animated SVGs.
  Injects overlay `<rect>` elements (stable ids `smil-stm-hl-<vertexId>`) and `<path>` elements
  per fired transition; animates via `SmilEmitter`. Static fallback is byte-identical to
  `KumlSvgRenderer.toSvg` when trace is null/empty/produces no animations.
- `ActivitySmilRenderer` — renders UML Activity diagrams with token-flow SMIL animations.
- `TraceFileLoader` — loads `TraceFile` from disk with a 5 MB size cap and schema validation.
  Wraps `SerializationException` without leaking raw file bytes.
- `StmTransitionPathResolver`, `StmStateTimelineBuilder` — build STM overlay geometry and
  animation timelines from `StateEntered` / `TransitionFired` trace entries.

**V3.1.30 — BPMN SMIL Renderer**

- `BpmnSmilRenderer` — renders BPMN process diagrams as optionally animated SVGs.
  Token `<circle>` elements follow SequenceFlow paths via `<animateMotion>`. Gateway
  highlights use `<animate attributeName="fill">` (ADR-0014: never `<animateColor>`).
  Task activation uses `<animate attributeName="stroke-width">` pulse. Start/end events
  dim via `<animate attributeName="opacity">`. Static fallback is byte-identical.
- `BpmnFlowPathResolver`, `BpmnTokenTimelineBuilder` — build edge paths and animation
  timelines from `TokenPlaced` / `TokenConsumed` / `DecisionTaken` trace entries.
- `BpmnAnimationContext` — color/speed tuning with CSS-color allowlist injection protection.

**V3.1.29 — SMIL Timeline API**

- `SmilTimeline` / `SmilEmitter` / `SmilTimelineBuilder` — core SMIL timeline model and
  SVG injection infrastructure. All animations are injected before `</svg>`.
- ADR-0014: `<animateColor>` is never emitted — `SmilAnimation.Fill` emits
  `<animate attributeName="fill" …/>` instead (deprecated in SVG 1.2, removed in SVG 2.0).
- ADR-0015: opacity-pulse for `TransitionFired` overlays — overlay rects/paths appear at
  `opacity=0` and are revealed via `<animate attributeName="opacity">` triggered at the
  correct `begin` time.
- `StaticSnapshotMode.STRIPPED` — `SmilEmitter.inject(svg, timeline, STRIPPED)` removes all
  SMIL elements from the SVG and suppresses injection of new ones; safe for PNG rendering.
- `SpeedFactor` — type-safe speed multiplier; applied by `SmilTimeline.scaledBy(factor)`.
  The emitter is speed-neutral — callers pre-scale the timeline before calling inject.

## [0.19.2] — 2026-06-25

### Fixed

**JetBrains Plugin — Marketplace Verifier (second pass)**

The 0.19.1 verifier run revealed that two of the previous replacements had merely
traded one finding for another: `PluginManager.getPluginByClass(Class)` is itself
`@ApiStatus.Internal` in the 2026.2 EAP, and `createSingleFileDescriptor()` is
deprecated there. Both are now resolved without depending on a moving target.

- **Internal API fully eliminated**: `KumlScriptDefinitionsSource` no longer touches the
  IntelliJ `PluginManager`/`PluginManagerCore` API at all. The plugin's bundled DSL
  classpath is resolved purely through the JDK — `Class.getResource(<own .class>)` yields a
  `jar:` URL into the plugin's `lib/` directory, whose sibling jars are the kUML model + DSL
  modules. This is immune to future platform reclassifications of plugin-lookup APIs.
- Replaced deprecated `FileChooserDescriptorFactory.createSingleFileDescriptor()` with the
  documented `FileChooserDescriptor(true, false, false, false, false, false)` constructor
  plus `.withTitle()` / `.withDescription()` in `KumlPreviewConfigurable`.

### Known accepted warnings

- `ScriptDefinitionsSource` is reported as a deprecated interface by the verifier. It is the
  **only** script-definition extension point that works in both K1 and K2 mode; the suggested
  replacement (`scriptDefinitionsProvider`) fails to instantiate in K2 mode. Suppressed at the
  source until JetBrains ships a stable K2-compatible replacement. This is a warning, not a
  compatibility problem.

## [0.19.1] — 2026-06-25

### Fixed

**JetBrains Plugin — Marketplace API Compliance (first pass)**

- Replaced deprecated-for-removal `TextFieldWithBrowseButton.addBrowseFolderListener(String, String, Project, FileChooserDescriptor)`
  with the non-deprecated 2-arg `addBrowseFolderListener(Project?, FileChooserDescriptor)` form in
  `KumlPreviewConfigurable`.
- Fixed deprecated `Document.addDocumentListener(DocumentListener)` call in `KumlSplitEditorProvider`:
  the editor wrapper (`KumlSplitEditorWrapper`) is now passed as a `Disposable` parent so the listener
  is automatically removed when the editor is closed — no more potential memory leak.
- (Superseded by 0.19.2) Initial attempts at the internal-API and file-chooser findings still relied
  on `PluginManager.getPluginByClass()` and `createSingleFileDescriptor()`, both flagged by the
  2026.2 EAP verifier.

## [0.19.0] — 2026-06-25

### Added

**JetBrains Plugin — Scroll Pane for the SVG Preview**

- The preview area in the split editor is now embedded in a `JScrollPane`. Horizontal
  and vertical scrollbars appear automatically whenever a diagram exceeds the visible
  viewport — no more clipping of large communication, class, or SysML diagrams.
- **Zoom model revised**: All fit/zoom actions (Fit to Window, Fit Width, Fit Height,
  100%, Zoom In/Out) now control the preferred size of the `JSVGCanvas` instead of the
  Batik rendering transform. Batik scales the SVG automatically to the actual canvas
  size — the result is identical but scroll-compatible.
- After each new render, `svgNaturalSize` (native pixel dimensions of the SVG) is read
  from the freshly parsed `SVGDocument` and used for fit-width/height calculations.
- **Hand-drag interactor**: Left-click + drag pans the view (hand cursor on hover, grab
  cursor while dragging). Mouse wheel scrolls vertically; Ctrl+mouse wheel zooms using
  the same preferred-size mechanism as the toolbar buttons. Batik's built-in interactors
  (Zoom, Pan, Rotate, Reset, ImageZoom) are disabled as they operate on the Batik
  rendering transform and would be incompatible with the scroll pane model.
- Two new unit tests in `KumlPreviewPanelBatikTest` (now 10 tests): `scrollPane` is
  accessible without throwing; `svgNaturalSize` is initially `null`.

**JetBrains Marketplace Publishing**

- Plugin published to JetBrains Marketplace as `dev.kuml.ide`.
- `organizationId="kuml"` and `email="info@kuml.dev"` added to vendor descriptor.
- Plugin signing configured via `intellijPlatform.signing {}` (certificate chain + private key via CI secrets).
- Automated Marketplace upload added to `release.yml` as Job 7 (`publish-jetbrains-plugin`), triggered on every `v*.*.*` tag push.

### Fixed

**Renderer:**

- Outer UML diagram frame rendered for all 33 diagram types.

## [0.18.0] — 2026-06-24

### Added

**Chain Adapters — CosmWasm, Substrate, ink!**

- **`kuml-runtime-chain-cosmos`**: Chain adapter for CosmWasm and Substrate RPC. Enables
  kUML diagrams to be generated directly from live on-chain state via CosmWasm smart
  contract queries and Substrate RPC endpoints. Supports contract introspection, state
  diffing, and architecture diagrams derived from deployed contract schemas.
- **`kuml-runtime-chain-wasm`**: Chain adapter for ink! smart contracts with ABI parsing
  and SCALE codec support. Decodes ink! ABI metadata into kUML metamodel elements,
  enabling automatic class and interaction diagrams from deployed ink! contracts on
  Substrate-based chains.

**User Journey / Service Blueprint**

- **`kuml-metamodel-blueprint`**: New metamodel module introducing `Actor`, `Phase`,
  `Step`, `Touchpoint`, `Emotion`, and `BackstageAction` as first-class metamodel
  elements for User Journey Maps and full Service Blueprints.
- **`blueprint{}` DSL**: Kotlin builder DSL for composing journey maps and service
  blueprints inline in `.kuml.kts` scripts. Supports nested phase/step/touchpoint
  hierarchies, emotion curves, and frontstage/backstage lane separation.
- **SVG renderer — Journey Map + Full Blueprint**: Two new SVG render modes:
  `JourneyMapRenderer` (swimlane layout with emotion curve overlay) and
  `FullBlueprintRenderer` (five-lane layout: Actor / Frontstage / Backstage /
  Support Processes / Physical Evidence). Both integrate with the existing
  `KumlSvgRenderer` dispatch pipeline.
- **CLI integration**: `kuml render --type blueprint` and `kuml render --type journey`
  route to the new renderers. Output format flags (`--svg`, `--png`, `--pdf`) are
  fully supported.
- **`BlueprintConstraintChecker`**: Validation pass that enforces blueprint well-formedness
  rules (every step must belong to a phase, touchpoints must reference a declared actor,
  emotion values clamped to −2 … +2). Errors are reported via the standard
  `KumlConstraintViolation` pipeline.
- **PdV Mitglieder-Journey example**: End-to-end example blueprint modelling the
  Partei der Vernunft member onboarding journey from first contact through active
  membership. Included in `kuml-vault-examples-tests` as a smoke-test fixture.
- **LaTeX/TikZ renderer**: `BlueprintTikzRenderer` emits a compilable `.tex` file
  using TikZ/PGF for high-quality print output. Swim lanes are rendered as TikZ
  matrices; the emotion curve uses a `\draw` spline. Requires a local LaTeX installation;
  invoked via `kuml render --type blueprint --format tex`.

## [0.17.0] — 2026-06-23

### Added

**Plugin Ecosystem — V3.1.9–V3.1.14**

- **AST-based TypeScript reverse engineering** (`V3.1.9`): replaced the regex-based
  TypeScript parser in `kuml-plugin-api` with a full AST parser. Correctly handles
  nested generics, union/intersection types, decorators, and type aliases that tripped
  up the old approach.
- **`kuml plugin init` scaffolding command** (`V3.1.10`): new CLI sub-command that
  generates a ready-to-publish plugin project from one of five category templates
  (Theme / Renderer / Layout / Codegen / Reverse). Produces `build.gradle.kts`,
  `plugin.json`, stub source file, and README from the `kuml-dev/plugin-template` repo.
- **Update-check and upgrade commands + Desktop badge** (`V3.1.11`): `kuml plugin update`
  polls the registry for newer versions of installed plugins; `kuml plugin upgrade <id>`
  downloads and hot-swaps the new JAR. The Desktop Plugin Manager shows an orange badge
  on the toolbar when updates are available.
- **Ratings, reviews, and download statistics** (`V3.1.12`): the registry schema now
  carries per-plugin aggregate ratings (0–5 stars), review snippets, and a download
  counter. The Desktop Plugin Manager and `kuml plugin search` output display this data.
- **Screenshot gallery in plugin marketplace** (`V3.1.13`): plugins can declare up to
  five screenshot URLs in `plugin.json`; the Desktop Plugin Manager renders them in a
  scrollable carousel in the detail pane.
- **Signature-key rotation with multi-key registry** (`V3.1.14`): the plugin signing
  infrastructure now supports a registry of active public keys identified by `kid`.
  Old signatures remain verifiable during a configurable rotation window; the CLI
  and Desktop warn if a plugin was signed with a key outside the active window.

**AI Assistant — V3.1.15–V3.1.20**

- **`KumlLlmProviderSpi` with ServiceLoader discovery and runtime tree-shaking** (`V3.1.15`):
  custom LLM provider backends are now registered via `ServiceLoader`. Providers not
  referenced in `kuml.config.kts` are excluded from the GraalVM Native Image, keeping
  binary size minimal.
- **`KumlToolSetFactory` SPI with ServiceLoader discovery** (`V3.1.16`): external modules
  can contribute named `@Tool` sets to the AI agent without modifying `kuml-ai-core`.
  The CLI and MCP bridge discover and load tool sets at startup via `ServiceLoader`.
- **`kuml ai bench` sub-command and live provider pricing** (`V3.1.17`): runs the
  existing 10-task LLM benchmark against any registered provider, streams per-task
  pass/fail and token counts to stdout, and displays live cost estimates using
  provider-reported pricing. `--budget <USD>` aborts the run if the estimate exceeds
  the cap.
- **Multi-agent orchestration with UML/C4/SysML 2 specialist routing** (`V3.1.18`):
  the AI agent now spins up specialist sub-agents for each modelling language (UML,
  C4, SysML 2, BPMN) and routes diagram generation requests to the appropriate
  specialist. An orchestrator agent merges partial results and resolves cross-language
  references.
- **Compliance audit log** (`V3.1.19`): every AI-generated model edit is appended to
  a structured JSONL audit log (`~/.kuml/audit.jsonl`) containing timestamp, provider,
  model ID, prompt hash, patch diff, and the agent's reasoning summary. The log is
  append-only; `kuml ai audit tail` streams recent entries.
- **Master-password vault, CodeGenAiTools, and Koog 1.40.0 upgrade** (`V3.1.20`):
  the API-key store is now encrypted with AES-256-GCM behind a user-supplied master
  password (PBKDF2/SHA-256, 310 000 iterations). New `CodeGenAiTools` `@Tool` set
  lets the AI agent invoke `kuml generate` and `kuml reverse` inline during a session.
  Koog dependency bumped from 0.7.3 to 1.40.0 (streaming improvements, tool-call
  parallelism, native-image compatibility).

**Distribution**

- **Chocolatey packaging for Windows** (`choco install kuml`): self-contained NuGet
  package bundles a Java 21 runtime; no JDK required on the target machine. Published
  automatically by the release CI workflow. First-time submissions enter Chocolatey
  community moderation (typically 1–3 days).

### Fixed

**State Machine Renderer**

- **Composite states render substates inside parent box**: sub-states were previously
  laid out outside or overlapping the composite-state boundary; `layoutAsCompound` now
  correctly nests the inner ELK graph inside the parent node with proper padding.
- **Composite-state overflow eliminated via compound-frame propagation**: title-band
  height and compound-frame dimensions are now propagated through the layout pipeline
  so deeply nested state machines no longer overflow their enclosing box.
- **Node/edge spacing increased for legibility**: default ELK node-node and edge-edge
  spacing in state machine diagrams raised to prevent transition labels from colliding
  on graphs with dense outgoing edges.

**Deployment Diagram**

- **Nested `UmlNode` children and artifacts rendered**: nested nodes (e.g., an
  `ExecutionEnvironment` inside a `Device`) and deployed `UmlArtifact` elements were
  silently dropped by the renderer; they are now drawn recursively with correct
  indentation and dashed-border styling.

**Internal Block Diagram (IBD)**

- **Boundary-port squares on IBD Part-Usage boxes**: flow ports on the boundary of
  Part-Usage boxes in SysML 2 IBDs were rendered without their characteristic solid
  square indicator; the square is now drawn at the correct anchor point.

**BPMN Renderer**

- **Event symbols clipped by circle boundary**: event-type symbols (message envelope,
  timer clock, error lightning bolt, etc.) were rendered with coordinates relative to
  the SVG origin rather than the event circle centre, causing them to appear outside
  or half-clipped. Symbol coordinates are now offset by the circle's `cx`/`cy`.

**Composite-Structure Diagram**

- **`provides`/`requires` interface edges synthesized** (supersedes v0.16.1 hotfix):
  `CompositeStructureDiagramBuilder` now calls `synthesizeInterfaceRelationships()` at
  build time, matching the behaviour already present in `ComponentDiagramBuilder`.
  `UmlInterfaceRealization` and `UmlDependency(name = "use")` are emitted for each
  `provides`/`requires` declaration whose interface appears as a node in the diagram.
- **Orthogonal routing for internal connectors** (supersedes v0.16.2 hotfix):
  `UmlComponentSvg.drawInternalConnectors()` now emits `<polyline>` elements with
  gap-routing (vertical bridge through the inter-component gap) for opposite-side
  port pairs and U-form routing for same-side port pairs, replacing the diagonal
  `<line>` that cut through component boxes.

## [0.16.2] — 2026-06-23

### Fixed

**Composite Structure: assembly connector routed diagonally through component boxes** (`UmlComponentSvg`)

Internal connectors in Composite Structure diagrams were rendered as plain `<line>`
elements — without orthogonal routing. An assembly connector from a RIGHT-side port
(e.g. `Validator::out`) to a LEFT-side port (e.g. `Persistence::in`) on vertically
stacked components produced a diagonal line that cut through both component rectangles.

- `drawInternalConnectors()` now draws `<polyline fill="none">` instead of `<line>`.
- `resolvePortCenter()` replaced by `resolvePortAnchor()` — additionally returns
  `isLeft` and `compId` for routing.
- New `buildInternalRoute()`: detects the port sides and selects:
  - **Same side** → U-shape: shared corridor outside both boxes.
  - **Opposite sides** → gap routing: the vertical bridge runs through the gap between
    the component boxes (no intersection through rectangles). When horizontal overlap
    exists (no gap): simple midpoint as fallback.

## [0.16.1] — 2026-06-23

### Fixed

**Composite Structure diagram: `provides`/`requires` edges were missing** (`CompositeStructureDiagramBuilder`)

`compositeStructureDiagram { }` showed interfaces as standalone nodes without any
visual connection to the components that provide or require them.
`ComponentDiagramBuilder` already had `synthesizeInterfaceRelationships()`;
`CompositeStructureDiagramBuilder` did not.

- `synthesizeInterfaceRelationships()` added to `CompositeStructureDiagramBuilder.build()`:
  emits `UmlInterfaceRealization` for `provides(iface)` and
  `UmlDependency(name = "use")` for `requires(iface)`, provided the interface exists as
  a node in the diagram and the relationship has not already been declared explicitly.
  Nested parts are visited recursively.
- `addRelationship()` now also accepts `UmlInterfaceRealization` explicitly
  (for manual declarations).

## [0.16.0] — 2026-06-22

### BPMN 2.0 — New Modelling Language (V3.1.1–V3.1.8)

Full BPMN 2.0 support as a standalone metamodel (pure-Kotlin metamodel, analogous to
SysML 2). Covers Process, Collaboration, CLI integration, XML I/O, and the LaTeX
renderer. Choreography and Conversation will follow in V3.2.

**Metamodel (`kuml-metamodel-bpmn`) — V3.1.1+V3.1.4**

- Sealed interface hierarchy: `BpmnElement` → `BpmnFlowElement` → `BpmnFlowNode`
- `BpmnEvent` (event-matrix design): single class with `(EventPosition × EventDefinition × EventBehaviour)` triplet + `init{}` guards (13 EventDefinitions, 3 Positions, 2 Behaviours)
- `BpmnGateway`: 5 types (EXCLUSIVE, INCLUSIVE, PARALLEL, EVENT_BASED, COMPLEX)
- `BpmnTask`, `BpmnSubProcess` (expanded/collapsed/transactional/triggeredByEvent), `BpmnCallActivity`, `LoopCharacteristics` (Standard + MultiInstance)
- `SequenceFlow` as Pattern-A edge: on the model with `sourceRef`/`targetRef` + `conditionExpression`
- `BpmnDataObject`, `BpmnDataStore`, `DataAssociation`
- `BpmnProcess` with `elementById()`
- Collaboration types: `BpmnCollaboration`, `BpmnParticipant` (Pool), `BpmnLane` (nested via `childLanes`, lane references FlowNodes via `flowNodeRefs`), `MessageFlow` (Pattern A)
- `BpmnModel` + `ProcessDiagram` + `CollaborationDiagram` as `BpmnDiagram sealed interface`

**DSL (`@BpmnDsl`) — V3.1.2+V3.1.4**

- `bpmnModel { }` — top-level builder function
- `ProcessBuilder`: `startEvent()`, `endEvent()`, `intermediateEvent()`, `boundaryEvent()`, `task()`, `subProcess { }`, `callActivity()`, `gateway()`, `dataObject()`, `dataAssociation()`, `sequenceFlow()`
- Infix syntax: `"sourceId" flowsTo "targetId"`
- Auto-IDs: `{processId}_{type}_{counter}` (deterministic)
- `CollaborationBuilder`: `pool { }`, `blackBoxPool()`, `messageFlow()`
- `PoolBuilder`: `lane { }`, `process()`; `LaneBuilder`: `contains()`, nested `lane { }`

**SVG Renderer (`kuml-io-svg`) — V3.1.3+V3.1.5**

- All OMG BPMN symbols: Events (thin ring START, double ring INTERMEDIATE, thick ring END), Gateways (diamond + 5 type symbols), Tasks (rounded rectangles + 7 type markers), SubProcess (`+` marker), CallActivity (bold border)
- Loop/multi-instance markers (↻ / ≡ / ‖)
- `BpmnSequenceFlowSvg`: filled arrowhead, default slash, condition label, marker ID sanitised via regex
- Boundary events: `stroke-dasharray` for non-interrupting, `attachedToRef` positioning
- Event symbols for all 13 `EventDefinition` types (catching = outline, throwing = filled)
- `BpmnPoolSvg`: swimlane frame + rotated title band (horizontal left, vertical top)
- `BpmnLaneSvg`: lane dividers + lane titles, recursive for nested lanes
- `BpmnMessageFlowSvg`: dashed line + open arrowhead + circle at source
- `BpmnLayoutBridge`: FlowNodes → LayoutGraph (Task 120×60, Gateway 50×50, Event 36×36, DataObject 40×55); Pools as container nodes; Boundary Events as child nodes

**CLI Integration, Constraint Checker, Vault Examples — V3.1.6**

- `BpmnConstraintChecker` with 7 rules: missing start/end events (WARNING), unknown SequenceFlow references (ERROR), XOR/OR gateway without defaultFlow (WARNING), invalid BoundaryEvent attachedToRef (ERROR), MessageFlow source == target (ERROR), Participant.processRef pointing to a non-existent process (ERROR)
- CLI recognises `BpmnModel` as the script return type and invokes the BPMN renderer
- Constraint violations are surfaced as warnings in the CLI output
- 3 new Vault examples: Order Fulfillment (Process), Document Review (SubProcess+Loop), Customer-Supplier (Collaboration, 2 Pools)

**BPMN 2.0 XML Import/Export (`kuml-io-bpmn`) — V3.1.7**

- New module `kuml-io-bpmn` (analogous to `kuml-io-arxml`)
- `BpmnXmlExporter`: BPMN 2.0 XML without JAXB (Kotlin stdlib), namespace `http://www.omg.org/spec/BPMN/20100524/MODEL`; all FlowNode types, SequenceFlow + ConditionExpression, Collaboration
- `BpmnXmlImporter`: namespace-aware DOM parser, XXE protection (`disallow-doctype-decl` + external entities disabled), unknown tags ignored
- `BpmnXml`: convenience API (`export()`, `import()`)
- Roundtrip consistency: Export → Import → same ids/names/types

**LaTeX/TikZ Renderer (`kuml-io-latex`) — V3.1.8**

- `BpmnLatexRenderer`: ProcessDiagram + CollaborationDiagram as TikZ output
- 15 new TikZ styles: `kuml-bpmn-start/end/intermediate/boundary`, `kuml-bpmn-gateway`, `kuml-bpmn-task/subprocess/callactivity`, `kuml-bpmn-pool/lane/pool-header/lane-header`, `kuml-bpmn-flow/msgflow`
- Event symbols via LaTeX math characters (`\bowtie`, `\bullet`, `\lightning`, `\triangle` etc.)
- Gateway type symbols (`\times`, `+`, `\bigcirc` etc.)
- Pool header with `\rotatebox{90}`
- LaTeX injection protection: all model fields passed through `LatexEscape.escape()`
- TikZ ID sanitising: `[^a-zA-Z0-9]` → `_`
- 3 new `KumlLatexRenderer.toLatex()` overloads for BPMN

### C4 LaTeX/TikZ Renderer (`kuml-io-latex`) — V3.1

- `C4LatexRenderer`: all 6 C4 element types (Person, System, Container, Component, ExternalSystem, DeploymentNode) for all 5 C4 diagram types as TikZ output; 10 new TikZ styles (`kuml-c4-person`, `kuml-c4-system` etc.); monochrome/white as default, overridable

### Composite Structure Renderer

- **Nested parts rendering fix**: `UmlComponent.nestedComponents` are now taken into account by `UmlContentSizeProvider` and `UmlComponentSvg` — composite components previously rendered as an empty box. Recursive rendering of nested parts as stacked boxes; port clearance indents parts when the parent has boundary ports.
- **Port-to-part connectors**: Delegation connectors (boundary port → inner part port) and assembly connectors (part port → part port) in Composite Structure diagrams. Connector routing is handled entirely in the SVG renderer (without ELK); `UmlLayoutBridge` filters these connectors out of the ELK graph. Guards: cycle/depth protection, connector cap (500).

### UML Association Decoration

- Role names are now rendered together with the multiplicity at each association end (`UmlEdgesSvg`) — role names were previously missing from the output. Regression test against the Order Domain example.

### BPMN Process Renderer Fix

- BPMN **Process** diagrams rendered as an empty canvas through the CLI/web pipeline when the DSL used `diagram(name, processId)` without an explicit `include()` block. Three stacked root causes fixed: empty `elementIds` = "show all elements" (convention matching `Sysml2LayoutBridge`), expanded sub-process children + inner flows now correctly included, new recursive `BpmnProcess.renderableElements()` helper for the renderer index (CLI + Web).

### Examples & Documentation

- **Named parameter sweep**: all DSL examples (`kuml-examples`, Vault examples, handbook snippets) migrated to named parameters — consistent with kUML's LLM-first design principle.
- 3 new BPMN Vault examples (Order Fulfillment, Sub-Process Loop, Customer-Supplier Collaboration) added to the CI smoke tests (now 33 examples).
- READMEs + Antora handbook extended with BPMN 2.0 DSL and XML I/O reference.

## [0.15.0] — 2026-06-21

### Blockchain-backed Models — Chain-Adapter-Linie (V3.0.1–6, V3.0.20–21)

Full blockchain integration for kUML: any `.kuml.kts` model can now be anchored
on-chain (Ethereum/L2, Sui, Aptos), its canonical hash stored in a smart contract
slot, its history replayed from chain events, and its authorship proved via
EIP-712 signatures. Covers EVM + Move (Sui/Aptos) out of the box, extensible
to any chain via the `KumlChainAdapter` SPI.

**`kuml-runtime-chain-api` — Chain-Adapter SPI + ModelHasher (V3.0.1)**
- New module (pure Kotlin, GraalVM-Native-Image-compatible).
- `KumlChainAdapter` interface: `connect()`, `subscribe(): Flow<ChainEvent>`,
  `replay(fromBlock)`, `blockClock(): BlockClock`.
- `ModelHasher`: `canonicalize()` (CRLF→LF, tab→spaces, blank-line removal),
  `hashCanonical()` (SHA-256), `hashTransitive()` (cycle-safe import traversal).
- `kuml fmt --canonical` — new flag that writes the normalised canonical form
  instead of the standard formatted output.
- `KumlBackedContractSpec.V1` + ABI-JSON resource + `ContractTestVector.STANDARD_VECTORS`
  (chain-agnostic contract specification, V3.0.3).
- `MultiChainAdapter` + `ConflictResolver` (EarliestBlock / PriorityChain / FirstObserved)
  for deterministic multi-chain event merge with configurable conflict resolution (V3.0.21).

**`kuml-runtime-chain-evm` — Ethereum / L2 Adapter (V3.0.2)**
- JVM-only adapter using raw `java.net.http.HttpClient` — no Web3j.
- `EvmChainAdapter`: `eth_getLogs`-based event replay, finality-aware `EvmBlockClock`,
  SSRF-protected URL validation (http/https + RFC 1918 + APIPA + ::1 blocklist).
- `AbiCodec`: pure-Kotlin Keccak-256 (no Bouncy Castle).
- `Eip712Verifier`: EIP-712 typed-data `domainSeparator` + `hashStruct` + `ecrecover`
  via `java.security.Signature` with null/all-ones protection.

**`kuml-runtime-chain-move` — Sui + Aptos Move-VM Adapters (V3.0.20)**
- New JVM-only module, no native Move SDK.
- `SuiChainAdapter`: `suix_queryEvents` + `sui_getObject` + checkpoint-based `BlockClock`;
  Base64-BCS payload decoding; `SuiRpcUrlValidator` SSRF guard.
- `AptosChainAdapter`: `/v1/accounts/.../events` + `.../resource` REST API;
  `event.data` JSON → UTF-8 `payloadAbi`; `AptosUrlValidator`.
- `MoveAddress`: value class, 0x + 64 hex with leading-zero normalisation.

**EIP-712 Model Signatures + `*.kuml.kts.sig` (V3.0.5)**
- `ModelSigner.sign(modelSource, privateKeyHex)`: EIP-712 TypedData over
  `ModelCommit { modelHash: bytes32, timestamp: uint256 }` via secp256k1 ECDSA.
- `ModelSigner.recover()`: ecrecover → EIP-55 checksummed Ethereum address.
- `Eip712Verifier.verifyModelSignature()`: Boolean.
- Signature-malleability guard (s ≤ secp256k1 half-N, EIP-2); null/all-ones protection.

**`kuml chain` CLI subcommands (V3.0.4–5)**
- `kuml chain connect --rpc URL --contract ADDR`: shows `ContractIdentity` from chain.
- `kuml chain verify --rpc URL --contract ADDR <model.kuml.kts>`: hash-match check;
  exit 0 = match, exit 50 = `CHAIN_HASH_MISMATCH`.
- `kuml chain events --rpc URL --contract ADDR [--from-block N] [--limit N]`.
- `kuml chain sign <model.kuml.kts> --private-key <hex>`: writes `<model>.kuml.kts.sig`.
- `kuml chain verify-sig <model.kuml.kts> [--expected-signer ADDR]`:
  exit 0 = valid, exit 52 = `CHAIN_INVALID_SIGNATURE`, exit 53 = `CHAIN_SIGNER_MISMATCH`.

**DAP Constitution Showcase (V3.0.6)**
- `kuml-examples/dap/` — three `.kuml.kts` scripts modelling a DAP governance constitution:
  class diagram (`VerfassungsArtikel` + `Abstimmung` + OCL guards), article lifecycle STM
  (8 states, OCL-guarded transitions), amendment lifecycle STM.
- Two event-trace JSON fixtures: happy path (→ IN_KRAFT) and quorum-fail (→ ABGELEHNT).
- 14-test showcase (`DapConstitutionShowcaseTest`) demonstrating hash, signing, and
  offline `EvmChainAdapter` round-trip against a `MockRpcServer`.

### Plugin Registry — `kuml plugin search` (V3.0.18+)

- `kuml plugin search [query]` — browses `plugins.kuml.dev` registry with optional keyword
  filter. Prints id, version, type, description, and homepage for each match.
- `kuml plugin search --type <category>` — filter by plugin type (theme, renderer, layout,
  codegen, reverse).
- `PluginRegistryClient` uses the live `https://plugins.kuml.dev/index.json` feed.

## [0.14.0] — 2026-06-17

### Structurizr Migration Showcase (V3.0.19)

Adds the canonical **Big Bank Plc** reference architecture (Simon Brown,
https://structurizr.com/share/36141) as a committed DSL fixture and a
comprehensive showcase for `kuml import --format structurizr`.

**Test fixture (`bigbankplc.dsl`)**
- Full workspace with 3 persons (Personal Banking Customer, Customer Service
  Staff, Back Office Staff), 4 software systems (Internet Banking System,
  Mainframe Banking System, E-mail System, ATM), 5 containers inside the
  Internet Banking System, 6 components inside API Application, and 31
  top-level relationships across all elements.
- Covers all four Structurizr view types: System Landscape, System Context,
  Container, Component.
- Uses only `//` line comments — the `StructurizrDslParser` tokeniser
  handles only line comments, not `/* */` block comments.

**19 new tests (`StructurizrBigBankPlcShowcaseTest`)**
- Workspace metadata: name, description.
- Element counts: 3 persons, ≥ 7 top-level elements, Internet Banking System
  with exactly 5 containers, API Application with ≥ 5 components.
- Relationship coverage: ≥ 10 relationships; customer → internetBankingSystem
  present.
- View structure: 4 views; SystemContext and Container views reference the
  correct system identifier.
- `KumlDslGenerator` roundtrip: non-empty output, `c4Model` entry point,
  no Structurizr-specific `workspace` keyword in output.

**Handbook page (`showcases/structurizr-migration.adoc`)**
- Step-by-step walkthrough: source workspace summary, `kuml import` command,
  generated kUML C4 DSL excerpt, feature-gap table (tags, styles, deployment
  environments, dynamic views), rendering and next-steps sections.
- Added to the handbook sidebar navigation under *Showcases*.

## [0.13.0] — 2026-06-17

### Plugin Ecosystem

A full **plugin SPI + loader + CLI + signature verification + desktop UI** stack
allowing third-party extensions to add themes, renderers, layout engines, codegen
engines, and reverse engines without modifying the core codebase.

**`kuml-plugin-api` SPI Module Group (V3.0.27)**

Six new independently-versionable SPI modules define stable binary contracts:

- `kuml-plugin-api-core`: `PluginDescriptor`, `PluginVersion`, `KumlVersionRange`,
  `PluginCapability`, `PluginPermission`, `KumlPlugin` — the root extension-point
  interface implemented by all plugin categories.
- `kuml-plugin-api-theme`: `KumlThemePlugin` — implement to ship custom themes.
- `kuml-plugin-api-renderer`: `KumlRendererPlugin` + `RendererCapabilities` — for
  new output formats (PDF, SVG variants, …).
- `kuml-plugin-api-layout`: `KumlLayoutPlugin` — alternate layout engines.
- `kuml-plugin-api-codegen`: `KumlCodegenPlugin` — source-code generation targets.
- `kuml-plugin-api-reverse`: `KumlReversePlugin` — new reverse-engineering engines.

Built-in themes, codegen engines and reverse engines are migrated to implement
the new SPI. Binary-compatibility guard via `japicmp` enforced in CI.

**`kuml-plugin-loader` (V3.0.28)**

- `kuml-plugin.json` manifest schema (`schemaVersion`, `id`, `name`, `version`,
  `kumlVersionRange`, `extensions[]`, `permissions[]`, `maintainer`,
  `licenseSpdx`, `signature`).
- `PluginManifestParser` with Jackson + JSON Schema validation.
- `PluginLoader`: scans `~/.kuml/plugins/`, `$KUML_HOME/plugins/`, and classpath;
  enforces `kumlVersionRange` on load.
- Isolated `URLClassLoader` per plugin (parent = `kuml-plugin-api` class-loader,
  not the application class-loader — prevents version conflicts).
- `kumlVersionRange` parser: Maven-style range syntax (`[1.0,2.0)`, `>=3.0.27`).
- Plugin lifecycle: `onLoad()` / `onUnload()` hooks called by the loader.

**`kuml plugin` CLI + Permission Enforcement (V3.0.29)**

- New top-level `kuml plugin` subcommand group:
  - `list [--installed|--available]`
  - `install <id|maven-coords|jar-path>`
  - `remove <id>`
  - `info <id>`
  - `permissions <id>`
  - `reload`
- `PluginPermissionEnforcer` (`require` / `has` / `withPermission`); integrates the
  V2.0.40 Sandbox for Codegen/Reverse plugins.
- Permission model: `render.read-resources`, `fs.read`, `fs.write`,
  `network.http`, `process.exec`.
- New exit codes: `PLUGIN_NOT_FOUND=40`, `PLUGIN_VERSION_INCOMPATIBLE=41`,
  `PLUGIN_PERMISSION_DENIED=42`, `PLUGIN_SIGNATURE_INVALID=43`.

**Signature Verification + Registry Client (V3.0.30)**

- `PluginSignatureVerifier`: EdDSA (Ed25519) via Java 21 built-in
  `java.security`; verifies `sha256(jar-bytes)` against the plugin's registered
  public key in the registry.
- `PluginRegistryClient`: Java `HttpClient` fetch of
  `plugins.kuml.dev/plugins/index.json`; `PluginRegistryIndex` data class.
- `kuml plugin install` defaults to `--verify-signature=true`; opt-out via
  `--skip-signature-check` shows a prominent warning banner.

**Desktop Plugin Manager UI (V3.0.31 — extends V3.0.13)**

- `PluginManagerPane` extended from 3 to 5 tabs: **Theme / Renderer / Layout /
  Codegen / Reverse**.
- New **Browse Registry** tab: fetches `PluginRegistryIndex`, lists available
  plugins with a one-click Install button.
- Install flow: Click → `PermissionsDialog` (human-readable permission
  explanations) → Confirm → progress bar → hot-reload.
- Plugin detail card: manifest metadata, signature status (✅/⚠️/❌),
  maintainer, public-key fingerprint.

**Five Reference Plugins (`kuml-plugin-examples`) (V3.0.32)**

- `plugin-theme-pdv`: PdV Branding theme — Aureolin / Biscay / Ucla-Gold
  palette, Inter typography, Light + Dark variants.
- `plugin-renderer-pdf`: PDF renderer via Apache PDFBox 3.x; all UML / SysML 2 /
  C4 diagram types → single-page vector PDF.
- `plugin-layout-elk-bridge`: Eclipse Layout Kernel bridge (`elk-alg-layered`,
  `elk-alg-mrtree`, `elk-alg-radial`); selectable via `layout: elk-layered`
  frontmatter.
- `plugin-codegen-typescript`: UML Class / Interface / Enum → TypeScript `.ts`
  skeletons with JSDoc; requires `fs.write` permission.
- `plugin-reverse-typescript`: regex-based TypeScript → UML reverse engine;
  `kuml reverse --format typescript`; requires `fs.read` permission.

### Theme Overhaul

- **`KumlColors.nodeFill`**: new colour slot separating canvas background from
  node fill colour, preventing node-on-node bleed in themes where canvas ≠ white.
- **`ElegantTheme` redesign**: editorial-classical aesthetic — cream canvas, dark
  slate nodes, amber accent; now visually distinct from the kUML brand theme.
- **`KumlBrandTheme`**: updated to pure white canvas; logo-colour elements carry
  the brand identity on a neutral field.
- **`SvgDocument`**: background rendering updated to honour the new
  `nodeFill` / `canvasBackground` split.
- **Vault-examples test suite**: all examples are now rendered in every registered
  theme (`plain`, `kuml`, `elegant`, `playful`) with per-theme outputs stored under
  `build/sample-output/vault-examples/<theme>/`.

## [0.12.0] — 2026-06-16

### Reverse Engineering (`kuml reverse`)

New end-to-end **Source → UML** pipeline via three new modules under
`kuml-codegen/`. Java sources are parsed with JavaParser, Kotlin sources with
`kotlin-compiler-embeddable` PSI, and a new `kuml reverse` CLI command wraps
both engines to emit `.kuml.kts` scripts via `UmlModelDslPrinter`.

**Java → UML via JavaParser (`kuml-codegen-reverse-java`)**
- `JavaReverseEngine` maps Java source trees to `UmlClass`, `UmlInterface`,
  `UmlEnum`, `UmlOperation`, `UmlProperty`, `UmlAssociation`, and
  `UmlGeneralization` model elements.
- Visibility, multiplicity (`1`, `0..1`, `*`), generic type parameters,
  method signatures, and field types are preserved in the output model.
- 30 new tests: mapper unit tests + three end-to-end corpora (bank, library,
  edge-case class hierarchies).

**Kotlin → UML via Kotlin Compiler PSI (`kuml-codegen-reverse-kotlin`)**
- `KotlinReverseEngine` uses `kotlin-compiler-embeddable` to parse `.kt` source
  files and map them to the same `kuml-codegen-reverse-api` output model.
- Handles `data class`, `sealed class`, `object`, `companion object`, `interface`,
  `enum class`, nullable types (`?`), and standard visibility modifiers.
- ~30 new tests covering the full Kotlin type hierarchy and nullability.

**`kuml reverse` CLI (`kuml-codegen-reverse-api` + CLI integration)**
- New `kuml-codegen-reverse-api` module: language-agnostic `KumlReverseEngine`
  interface, `ReverseRequest` / `ReverseResult` DTOs, `ReverseDiagnostic`
  (ERROR/WARN/INFO), and `ReverseEngineRegistry` (`ServiceLoader` wrapper with
  `byId`, `all`, `detectLanguage`). Native-Image-compatible, publishable on
  Maven Central.
- `kuml reverse <dir> [--lang java|kotlin|auto] [--transformer <id>]
  [--out-dir <path>]` — new top-level CLI subcommand wired into `KumlCli`.
- `--lang auto` heuristically detects language from file extensions.
- `UmlModelDslPrinter` serialises the in-memory `UmlModel` back to a formatted
  `.kuml.kts` script.
- ~12 new CLI integration tests.

### Desktop App (`kuml-desktop`)

**File I/O & Persistence (V3.0.12)**
- OS-native Open/Save/Save-As dialogs via `JFileChooser` (AWT fallback on Linux).
- MRU recent-files list (max 10 entries) persisted across restarts.
- Atomic JSON `app-settings.json` written to the OS-appropriate config directory
  (`XDG_CONFIG_HOME` on Linux, `~/Library/Application Support` on macOS,
  `%APPDATA%` on Windows).
- Theme (`LIGHT`/`DARK`) and UI language (`DE`/`EN`) survive restarts.
- Window geometry (size + position) restored on next launch.
- ~75 new tests covering dialog state, settings serialisation, and MRU logic.

**Plugin Manager UI (V3.0.13)**
- New `PluginManagerDialog` — `DialogWindow` with three tabs: **Themes**,
  **Transformers**, and **Reverse Engines**.
- `ServiceLoader`-based introspection: lists all registered `KumlTheme`,
  `KumlTransformer`, and `KumlReverseEngine` implementations at runtime.
- Hot-reload: activating a theme or transformer from the dialog applies it
  immediately to the current editor/preview pane without restart.
- ~10 new tests (tab rendering, ServiceLoader mock, hot-reload state machine).

**jpackage Distribution (V3.0.14)**
- `kuml-packaging` Gradle module produces native installers via `jpackage`:
  DMG (macOS), MSI (Windows), DEB + RPM (Linux).
- macOS `CFBundleShortVersionString` requires first component ≥ 1; pre-1.0
  versions map `0.x.y → x.y.0` automatically (e.g. `0.12.0 → 12.0.0`).
- `kuml-packaging` Exec tasks use `object : Action<Task>` + plain-String vals
  to satisfy Gradle 9 Configuration Cache serialisation rules.
- ~12 new tests (version-mapping, DMG path computation, multi-OS matrix).

### AI Assistant (`kuml-ai`)

Koog-based multi-LLM AI assistant embedded in the Desktop editor, with a
secure key vault, DSL-builder tool suite, MCP bridge, streaming UI, patch
apply flow, privacy mode, and Langfuse telemetry.

**Koog Integration & Multi-LLM Executor (V3.0.22)**
- New `kuml-ai/kuml-ai-core` module: `KumlAiExecutor` wraps JetBrains Koog
  `KoogAgent` and dispatches to OpenAI, Anthropic, Google (Gemini), or a local
  Ollama endpoint based on `AiProvider` configuration.
- `ApiKeyVault`: platform-aware secure storage — macOS Keychain, Windows
  Credential Manager, plain-file fallback (Linux / CI).
- `AiSettings` JSON config stored alongside `app-settings.json`; editable via
  the new Settings dialog.
- ~37 new tests (executor dispatch, key-vault platform matrix, settings
  serialisation).

**DSL Builder Tools (V3.0.23)**
- New `kuml-ai/kuml-ai-tools` module: `@Tool`-annotated Kotlin functions
  covering the full kUML DSL surface:
  - **Context tools**: `getScript`, `getLastSvg`, `getLastError`, `listOpenFiles`.
  - **UML tools**: `addClass`, `addInterface`, `addEnum`, `addAssociation`,
    `addGeneralization`, `addOperation`, `addProperty`.
  - **C4 tools**: `addPerson`, `addSystem`, `addContainer`, `addComponent`,
    `addC4Relation`.
  - **SysML 2 tools**: `addBlock`, `addPort`, `addConnector`, `addFlow`,
    `addState`, `addTransition`.
  - **Inspection tools**: `validateScript`, `renderPreview`, `listDiagramTypes`.
- `McpBridge`: exposes all tools as an MCP server so external agents (Claude,
  Copilot, Cursor, etc.) can drive the editor via the Model Context Protocol.
- `AgentEditingContext`: snapshot-based undo/redo aware state carrier for
  tool-call sequences.
- `AnyKumlModel` sealed union + `ModelPatch` sealed hierarchy + `DeepCopy`.
- 91 new tests (context, UML, C4, SysML 2, inspection, MCP, registry).

**AI Panel UI (V3.0.24)**
- `AiPanel` composable: streaming chat UI embedded in the Desktop right sidebar.
- `ToolTraceCard`: expandable card showing tool name, input JSON, output JSON,
  and timing for each tool call in the current conversation turn.
- `PricingTable`: real-time token cost estimate per provider/model.
- `TokenUsageTracker`: cumulative input/output/cache token counters per session.
- `ConversationStore`: persists the full conversation history (messages +
  tool traces) in JSON alongside `app-settings.json`.
- ~32 new tests (streaming state machine, trace serialisation, token arithmetic).

**Patch Apply & Sandbox (V3.0.25)**
- `PatchApplyEngine`: captures a pre-session snapshot of the open script, then
  applies `ModelPatch` operations one-by-one with rollback on failure.
- `PatchValidator`: structural checks (`StructuralPatchChecks`), type checks
  (`TypeCheckPatchChecks`), and a `RenderSmokeCheck` that fast-renders the
  patched model before accepting.
- `PatchPreviewDialog`: shows diff between current and patched script; Accept /
  Reject buttons commit or discard the pending patch.
- `ModelMutationRouter`: routes `ModelPatch` to the correct DSL builder mutator.
- `AiTraceSink` injection for OTLP-compatible trace capture per patch step.
- ~25 new tests (validator, patch engine, dialog state, trace serialisation).

**Ollama Local Mode, Privacy Mode & Langfuse Telemetry (V3.0.26)**
- Ollama local-mode showcase: auto-detects a running Ollama daemon at
  `http://localhost:11434` and lists available models in the Settings dialog.
- Privacy mode: when enabled, no script content or diagram data is sent to
  remote LLM providers; only Ollama local calls are allowed.
- Langfuse integration: optional observability backend — each AI conversation
  turn is traced as a `LangfuseSpan` with token counts, latency, and tool
  call metadata. Opt-in via `AiSettings.langfuseEnabled`.
- ~30 new tests (Ollama discovery, privacy-mode gate, Langfuse span builder).

### XMI Import/Export (`kuml-io-emf`)

Full bidirectional XMI/EMF round-trip for UML models, with CLI integration
and compatibility filters for Enterprise Architect and Papyrus.

**EMF/UML2 Module Skeleton (V3.0.15)**
- New `kuml-io-emf` Gradle module bootstraps Eclipse EMF + UML2 via the
  `org.eclipse.emf.ecore` / `org.eclipse.uml2.uml` Maven artifacts (JVM-only,
  reflection-guarded so Native Image builds are unaffected).
- MVP converters: `EmfToUmlConverter` and `UmlToEmfConverter` with round-trip
  coverage for `UmlClass` ↔ EMF `Class`.
- ~15 new tests.

**Bidirectional UML ↔ EMF Conversion (V3.0.16)**
- Full classifier support: `Class`, `Interface`, `Enum` (with literals).
- Full feature support: `Property` (attribute + association end), `Operation`
  (with `Parameter` in/out/return), static/abstract modifiers.
- Full relationship support: `Association` (unidirectional + bidirectional),
  `Generalization`, `InterfaceRealization`, `Dependency`.
- Package namespacing: nested `UmlPackage` structures map to EMF `Package`
  hierarchies and back.
- 54 new tests (39 net-new on top of V3.0.15), covering the full classifier /
  feature / relationship matrix.

**XMI CLI Integration (V3.0.17)**
- `kuml import --format xmi <file>` reads an XMI/UML2 file and emits a
  `.kuml.kts` script.
- `kuml export --format xmi <file>` renders a `.kuml.kts` script and writes
  an XMI/UML2 file.
- `XmiReader` / `XmiWriter` wrap the EMF resource layer; both handle multi-root
  XMI documents.
- `XmiToolFilter`: post-processing pass that strips tool-specific artefacts:
  EA `EAStub` prefix removal, Papyrus `_x` encoding normalisation, VP
  version-prefix stripping.
- Sample XMI files for EA, Papyrus, and plain EMF/UML2 checked into
  `kuml-io-emf/src/test/resources/`.
- `ExitCodes.FORMAT_NOT_AVAILABLE = 24` — returned when XMI support is
  unavailable (Native Image build without the EMF shim).
- ~75 new tests (21 net-new), including round-trip tests for all three XMI
  flavours.

### Infrastructure

**Vault Examples CI Tests (`kuml-tests/kuml-vault-examples-tests`)**
- New test module `kuml-tests/kuml-vault-examples-tests` with 31 Kotest specs
  that render all 30 active Vault example diagrams as SVG + PNG.
- CI-safe: examples are committed as Classpath resources under
  `src/test/resources/vault-examples/` — no direct Vault file-system access,
  no absolute paths. Loads via `getResourceAsStream`.
- Gradle input hashing: any change to a resource file automatically invalidates
  the test cache and triggers a re-run (`@InputFiles` on the resource directory).
- `afterSpec` hook writes a rendered index (`build/sample-output/vault-examples-index.md`)
  listing every example with its render time and output file paths — avoids the
  Gradle Configuration Cache serialisation issues that `CustomTask` lambdas would
  cause.
- Sync tooling: `scripts/sync-vault-examples.sh` mirrors Vault `.md` files →
  Classpath resources and extracts ` ```kuml ` blocks →
  `kuml.dev/playground-sources/*.kuml.kts`. `scripts/watch-vault-examples.sh`
  wraps `fswatch` for continuous syncing during edit sessions.

## [0.11.0] — 2026-06-15

### Renderer & Layout Improvements

A broad pass on the SVG renderer and layout bridge driven by visual defects
surfaced in the vault example notes (`03 Bereiche/kUML/Beispiele/*`). Each
fix is paired with new bridge or SVG-renderer tests so the geometry stays
stable on the next refactor.

**SysML 2 SEQ — Create-Message Visual Anchoring + Guard-Text Repositioning**
- `MessageKind.Create` arrow tip now lands on the target lifeline's head
  box corner. `renderLifelineHead` gains an optional `createOffsetY` parameter;
  the SEQ driver in `KumlSvgRenderer.toSvg(SeqDiagram, …)` shifts each Create
  target's head box (plus stereotype, name, dashed time-axis start) down by
  `(createSeqNo + 1) * SEQ_MESSAGE_ROW_HEIGHT`. `bounds.origin.y` stays uniform
  so message Y references remain consistent across all lifelines.
- Combined-Fragment operand guard labels now use `anchor="end"` at
  `minLifelineX - 4f`; text grows leftward into the frame's left padding zone.
  `sysml2SeqFragmentLeftPad(fragment)` computes
  `max(FRAGMENT_PADDING_H, longestGuardWidth + 12)` per fragment; SEQ canvas
  padding bumps to the max so the frame left edge is never clipped.
  Right padding stays at `FRAGMENT_PADDING_H` (asymmetric frame).
- New `internal const SYSML2_SEQ_MESSAGE_ROW_HEIGHT` mirrors the bridge
  constant so `kuml-io-svg` need not depend on `kuml-layout-bridge`.

**SEQ Fragment-Frame asymmetric outsets (UML + SysML 2)**
- `FRAGMENT_TOP_OUTSET = 24f` / `FRAGMENT_BOTTOM_OUTSET = 8f`: the frame now
  reaches into the corridor *above* the first contained arrow by 24 px but
  only 8 px below the last. Roots in the asymmetric message-label baseline
  geometry (labels sit 4 px above the arrow line; free corridor between two
  arrows is centered at `arrow_n + 8`, not `arrow_n + 16`). Stops adjacent
  messages from being swallowed by the frame.
- `drawLabelWithWhiteBackground()` helper draws message/self-call/guard
  labels with a white rect underlay so frame strokes never cut through text.
- `FRAGMENT_PADDING` raised 8 → 24 px (UML + SysML 2).

**SysML 2 STM — Connection-aware node sizing**
- New `Sysml2LayoutBridge.stmContentAwareSizeProvider(model, diagram,
  layoutDirection)` mirrors the V0.10 UML heuristic: each visible transition
  on a state adds 14 px to the docking side (capped at 112 px). Self-loops
  count twice. Pseudo-states (Initial/Final) stay at 24×24 px. Vertical
  layouts grow box width; horizontal layouts grow box height. Six new bridge
  tests pin the cap, the self-loop doubling, and the V2.0.9 backwards-compat
  single-arg overload.
- Fixes the Traffic-Light example where `Red` had 4 incident transitions and
  `timer60s` / `timer5s` / `powerOff` labels stacked on the same pixel row.

**Activity + Interaction-Overview pseudo-node edge clipping**
- New `Sysml2ActivityEdgeClipper` snaps every routed edge endpoint to the
  actual visible shape boundary: diamonds for Decision/Merge, fixed-radius
  circles for Initial/Final/FlowFinal, rectangles for Action/Object/Bar.
  ELK's bounding-box endpoints overshoot non-rectangular shapes, producing
  "floating" arrows. Applies to Activity diagrams and Interaction-Overview
  diagrams (same shape vocabulary + same `UmlActivityEdge` type).

**UML Component diagrams — Port edge clipping + Contracts**
- New `ComponentPortEdgeClipper` clips required/provided-interface lollipops
  and socket arcs at the component port boundary so connector arrowheads land
  on the port symbol, not on the component body.
- New `UmlComponentContracts` SVG layer renders required/provided-interface
  contracts as separate lollipop/socket shapes attached to ports.
- `ComponentDiagramBuilder` gains DSL extensions for declaring ports and
  contracts inline.

**UML Package diagrams — Edge endpoint snapping**
- ELK anchors inter-package edges at the compound-node outer boundary (top
  of the folder-tab area). The tab is narrower than the body, so arrowheads
  often landed in the empty "notch" between tab end and body start. Post-
  processing now snaps every package-dependency route to a Direct line that
  enters/exits the body rectangle (`y = groupOrigin + tabH`), which is
  always full-width.
- New flat `(id → KumlElement)` index recurses into `UmlPackage.members`
  so classes/interfaces declared inside `packageOf { … }` reach the SVG
  dispatcher.

**C4 — Deployment, Interaction, and description wrapping**
- New `C4DeploymentNodeSvg`: deployment-node boxes with technology stereotype
  + nested-container rendering.
- New `C4InteractionSvg`: dynamic-diagram interaction rendering with numbered
  call-sequence labels.
- New `C4DescriptionWrap`: greedy word-wrap helper that respects max-width
  per C4 element type so descriptions don't overflow the box.
- New `C4ContentSizeProvider` mirrors the UML connection-aware heuristic for
  C4 boxes — Person, SoftwareSystem, Container, Component, DeploymentNode
  grow with their connection count.

**SysML 2 par edges (Block/Internal-Block diagrams)**
- New `Sysml2ParEdgeLabelSvgTest` + `Sysml2EdgeRendererStackIndexTest`:
  multi-port connector labels stack at deterministic offsets instead of
  overprinting.

**Activity partition lane-gap fix**
- Activity diagrams with partitions now maintain a readable gap between lanes;
  previously, adjacent action boxes could butt directly against the lane separator.

**Shared rendering utilities**
- New `SvgInlineArrow` produces SVG `<defs>`-free inline arrowheads so
  arrows can be embedded into HTML excerpts (handbook, docs) without losing
  their markers.
- New `TextWrap` (in `kuml-layout-api`) — language-agnostic word-wrap
  primitive shared by all C4/UML/SysML-2 size providers.

### Examples

- `c4/checkout-dynamic` — C4 dynamic diagram with numbered interactions,
  exercises the new `C4InteractionSvg` + `C4DescriptionWrap` paths.

### CLI / Desktop / Gradle / Web Pipeline Alignment

- `kuml-cli/RenderPipeline`, `kuml-gradle/GradlePipeline`,
  `kuml-desktop/DesktopRenderPipeline`, and `kuml-web/WebRenderPipeline`
  all now wire through the new edge clippers + size providers, so every
  embedding path produces the same SVG output for the same source.

## [0.10.0] — 2026-06-15

### kuml-desktop — Desktop Editor with Live Preview (Track C: V3.0.10 + V3.0.11)

New standalone **kuml-desktop** module: a Swing/Compose Multiplatform Desktop
application that bundles the kUML render pipeline and lets you edit `.kuml.kts`
scripts with syntax highlighting and watch the SVG re-render in real time — no
Ktor server required.

**New module: `kuml-desktop`**
- `Main.kt` — Compose `application { Window { MainWindow(state) } }` with macOS
  properties (`apple.laf.useScreenMenuBar`, `apple.awt.application.name`)
- `AppState.kt` — Compose state-holder: `script`, `lastSvg`, `lastError`,
  `theme`, `language`, `isRendering`
- `MainWindow.kt` — native `MenuBar {}` (Datei / Bearbeiten / Ansicht / Hilfe)
  + Row layout with editor left / preview right
- `editor/EditorPane.kt` — RSyntaxTextArea with Kotlin syntax highlighting,
  code folding, line numbers via `SwingPanel`
- `preview/PreviewPane.kt` — `JSVGCanvas` (Apache Batik) + `CircularProgressIndicator`
  + error Card overlay
- `render/DesktopRenderPipeline.kt` — standalone SVG pipeline (all 8 diagram
  types: UML Class/Sequence/State/Activity/UseCase/Component, C4, SysML 2);
  no Ktor dependency
- `render/DesktopRenderController.kt` — 300 ms debounce via Kotlin Coroutines;
  cancels in-flight render on next keystroke
- `render/DesktopEngineInit.kt` — idempotent ELK + Grid + ThemeRegistry setup
- `i18n/Strings.kt` — DE/EN data class with `forLanguage()` factory
- 34 new tests (AppState 4 + Strings 4 + DesktopRenderController 6 +
  DesktopRenderPipeline 16 + DesktopRenderResult 4)

### Renderer Improvements (V2.x)

- **Connection-aware node sizing** (`UmlContentSizeProvider`): node boxes grow
  with the number of connected edges — 12–16 px per edge per side, capped.
  Prevents edge-label and multiplicity stacking on hub classes (e.g. PZB
  `BankUsers` with 20+ FKs). Horizontal growth for top/bottom edges, vertical
  growth for left/right edges.
- **`SelfLoopRouter`** (new): replaces ELK's flat 10-px self-loop with a
  visible C-shaped arc so FK self-references (e.g. `UserPosts.parent →
  UserPosts`) remain legible.
- **`EdgeLabelGeometry`** (new): dedicated geometry helpers for accurate
  edge-label placement alongside the new routing.
- **Sequence diagram z-order fix** (`KumlSvgRenderer`): combined fragments
  (alt/opt/loop) are now rendered into the nodes layer before lifeline heads
  and dashed verticals, so fragment frames never overpaint the time axes.
- **ELK integration**: `ElkGraphBuilder`, `HintsMapper`, `ResultMapper` updated
  to support the new sizing hints and routing metadata.

### DSL & Examples

- `ContainerDiagramBuilder`: C4 container DSL extensions
- `ClassDiagramBuilder`: UML class DSL extensions
- `LayoutMetadataKeys`: new metadata key constants
- New example: **PZB database schema** (`pzb/pzb-database-schema.kuml.kts`) —
  a real-world association-heavy schema that stress-tests the connection-aware
  sizing heuristic

## [0.9.0] — 2026-06-14

### Reverse Engineering — Track B complete (V3.0.7 + V3.0.8 + V3.0.9)

New end-to-end **Source → UML** pipeline. Java sources are parsed with
JavaParser (V3.0.7), Kotlin sources with `kotlin-compiler-embeddable` PSI
(V3.0.8), and a new `kuml reverse` CLI command (V3.0.9) wraps both engines
and emits a `*.kuml.kts` script via a new `UmlModelDslPrinter`.

End-to-end smoke: `kuml reverse kuml-core-model/src/main/kotlin --lang kotlin`
turns 10 production Kotlin files into a 267-line `*.kuml.kts` script in
~550 ms.

**New modules:**
- `kuml-codegen/kuml-codegen-reverse-api` (V3.0.7) — language-agnostic
  `KumlReverseEngine` interface, `ReverseRequest` / `ReverseResult` DTOs,
  `ReverseDiagnostic` (ERROR/WARN/INFO), `ReverseEngineRegistry`
  (`ServiceLoader` wrapper with `byId`, `all`, `detectLanguage`). Pure
  Kotlin, Native-Image-compatible, publishable.
- `kuml-codegen/kuml-codegen-reverse-java` (V3.0.7) — JavaParser-based
  Java source engine (id = `"java"`). 30 tests, three end-to-end corpora
  (`bank`, `library`, `edge`).
- `kuml-codegen/kuml-codegen-reverse-kotlin` (V3.0.8) — Kotlin PSI
  engine (id = `"kotlin"`) on top of `kotlin-compiler-embeddable` (K2).
  15 mappers (class, interface, object, enumeration, property, function,
  parameter, type resolver, visibility, multiplicity, generalization,
  association, data-class classifier, sealed-hierarchy, stereotype). 34
  tests including a real-world snapshot test against `kuml-core-model`.

**Kotlin → UML mapping coverage** (full table in
`kuml-codegen-reverse-kotlin/README.adoc`):
- `class` / `abstract` / `data` / `sealed` / `value` / `inner` → `UmlClass`
  with corresponding stereotypes.
- `interface` / `fun interface` / `sealed interface` → `UmlInterface`.
- `enum class` → `UmlEnumeration` with literals.
- `object` / `companion object` → `UmlClass <<object>> [<<companion>>]`.
- Properties (`val`/`var`/`lateinit`/`const`/`by lazy`) → `UmlProperty`
  with `isReadOnly` and stereotypes.
- Functions with `suspend` / `inline` / `operator` / `infix` / `tailrec` /
  `extension` stereotypes; primary and secondary constructors as
  `UmlOperation <<constructor>>`.
- Supertype edges → `UmlGeneralization` or `UmlInterfaceRealization`.
- Properties whose type resolves to an internal classifier become a
  `UmlAssociation` in addition to the attribute.
- Multiplicity inference: `List`/`Set`/`Flow`/`Array<T>` → `0..*`,
  `T?` → `0..1`, otherwise `1..1`.
- Top-level `fun` / `val` / `typealias` emit informational diagnostics
  (`REV-K-011` / `REV-K-012` / `REV-K-013`) and are skipped — UML has no
  free-floating functions.

**Diagnostic codes:**
- `REV-CORE-001` (ERROR — no source files) / `REV-CORE-002` (WARN — parse
  failure) — engine-agnostic.
- `REV-J-NNN` — Java engine (e.g. `REV-J-003` for `Map<K,V>` skip,
  `REV-J-011` for anonymous inner classes).
- `REV-K-NNN` — Kotlin engine (e.g. `REV-K-020` for nested classifiers
  emitted as top-level, `REV-K-030` for enum-entry bodies dropped,
  `REV-K-050` for supertypes outside the source set).

**New CLI:** `kuml reverse <source-dir>` (V3.0.9).
- `--lang java|kotlin|auto` — `auto` uses `ReverseEngineRegistry.detectLanguage()`
  (≥ 60 % file-extension majority).
- `--output <file>` — write the generated `*.kuml.kts`; defaults to stdout.
- `--include "<glob>"` / `--exclude "<glob>"` (repeatable) — file filters.
- `--model-name <name>` — name of the generated model.
- `--list-engines` — print available reverse engines and exit.
- `--verbose-diagnostics` — print every WARN/INFO on stderr (default:
  one-line summary).
- The engines are wired into `kuml-cli` via `runtimeOnly` — they live in
  the Fat-JAR / `distTar` / `runtimeZip` distributions but stay out of
  the CLI's compile classpath.

**New `UmlModelDslPrinter`** (`kuml-cli/src/main/kotlin/dev/kuml/cli/reverse/`):
- Deterministic output order: enums → interfaces → classes →
  generalizations → realizations → associations.
- Multiplicity printed only when ≠ default (`1..1`).
- Stereotypes emitted as `stereotypes = listOf("data", …)`.
- Idempotent in spirit to `kuml fmt`.

**New CLI exit codes:**
- `14` — `REVERSE_ENGINE_NOT_FOUND` (`--lang foo` unknown).
- `15` — `REVERSE_ANALYSIS_FAILED` (engine returned ERROR diagnostics).
- `16` — `REVERSE_NO_SOURCES` (no `.java`/`.kt` files in directory).

**Notes:**
- Both engines are **JVM-only**. The Kotlin engine pulls in
  `kotlin-compiler-embeddable` (~50 MB). Both are excluded from the
  GraalVM Native Image build but bundled with the JVM distribution.
- 82 new tests across the three waves, all green.
  `./gradlew check` BUILD SUCCESSFUL (608 actionable tasks).

## [0.8.0] — 2026-06-11

### Renderer Validation 2026-06-11 — 15 of 17 visual defects fixed

Systematic visual inspection of all 25 sample PNGs under
`kuml-cli/build/sample-output/examples/` found 17 defects across
ports, content-aware sizing and specific renderer bugs. Squash commit
covers eight sub-waves. Backup branch `backup/pre-squash-renderer-2026-06-11`
holds the granular history.

**Category A — Port Rendering:**
- `UmlComponentSvg.renderUmlComponent` now renders `element.ports` as
  12×12 black-filled squares on the component border (UML 2.x
  notation), alternating left/right, with port-name labels inside.
- New CSS classes `.kuml-port` and `.kuml-port-label` in
  `SvgDocument`.
- `xmlEscapeContent()` added to `SvgBuilder` and wired into
  `UmlClassSvg` / `UmlComponentSvg` / `UmlInterfaceSvg` /
  `StereotypeHelper` to fix angle-bracket escaping in operation
  signatures like `List<Order>` that previously broke SVG parsing.

**Category B — Content-aware sizing:**
- New `UmlContentSizeProvider` in `kuml-layout-bridge` measures
  `UmlClass`, `UmlInterface` and `UmlComponent` width/height from
  title, stereotype header and feature compartments, replacing the
  fixed `160 × 80` default that truncated feature text.
- New `Sysml2LayoutBridge.parContentAwareSizeProvider()` for
  ConstraintDefinition height in PAR diagrams.
- Wired into `RenderPipeline.renderUml()` and the SysML 2 `ParDiagram`
  branch.

**Category C — Specific renderer bugs:**
- `.kuml-collaboration` CSS rule added (SoaML black-ellipse fix).
- `Sysml2SequenceSvg`: SEQ self-call label now left-anchored to avoid
  canvas clipping; ALT-fragment separator-Y uses `max(naturalSep,
  prevEndSeqNo+1)` so guard labels of empty operands don't overlap
  the previous operand's last message.
- `UmlEdgesSvg.renderUmlAssociation` now renders source/target
  multiplicity labels (e.g. `0..*`), skipping trivial "1".
- Activity-Partitions (Swimlanes) made visible: id-collision-safe
  kind-typed lookups in `Sysml2LayoutBridge`, action nodes parented
  under ELK root (not their group), `ResultMapper.buildGroupLayouts`
  computes group bounds post-layout from member node positions.
- `C4LayoutBridge.isChildOfGroup` checks the structural parent
  reference (`C4Container.system` / `C4Component.container`) instead
  of treating every non-anchor diagram element as a child; anchor
  element no longer rendered as a regular node inside its own
  boundary.
- `StereotypeHelper.headerLabel` combines `appliedStereotypes` and
  the plain `UmlNamedElement.stereotypes: List<String>` so DSLs like
  `stereotypes += "service"` show up.
- `StmDiagram` branch in `RenderPipeline.renderSysml2()` uses tuned
  `LayoutHints` (nodeToNode=80, edgeToEdge=28, groupPadding=24) to
  reduce edge-label crowding in state machines.

**Known deferred (separate polish wave):**
- `c4/landscape.png` person-description / edge-label overlap (ELK
  label-placement strategy).
- `autosar/autosar-engine-control.png` and `autosar-runnable.png`
  port-label-vs-title overlap when ELK enlarges the box beyond
  content needs.

Full validation note: `[[04 Ressourcen/Diagramm Layout/Renderer-Validierung 2026-06-11]]`.

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

### Executable Behaviour Widget (V2.0.43) — MVP

New Compose Desktop widget `kuml-widget/kuml-widget-compose` for live
in-IDE STM behaviour inspection.

- `BehaviourWidgetState` holds model + runtime + instance + trace + scrub
  position; `HighlightHelpers.replayActiveVertices` derives the currently
  active vertex set from `StateEntered` / `StateExited` events.
- `EditPolicy` sealed class (`None` / `GuardsOnly` / `FullStructural`)
  governs which model edits the widget surfaces.
- `kuml-io-svg`: `SvgRenderOptions` gains `highlightVertexIds`,
  `highlightStrokeColor`, `highlightStrokeWidthPx`,
  `highlightRingOffsetPx`; `KumlSvgRenderer.renderUmlStateDiagram`
  injects highlight-ring rects around active vertices.

### Activity-Trace-Replay Stufe B (V2.0.42)

`kuml-runtime-trace` gains an activity-diagram replay path complementing
the V2.0.39 STM replay.

- `TraceFlavourDetector` classifies traces as `STM` / `ACTIVITY` / `EMPTY`
  / `MIXED`.
- `ActivityContextFromTrace` extracts decision-path diagnostics; the
  `ActivityTraceReplayer` runs `ActivityRuntime.start()`+`run()`
  deterministically and diffs against the original via `TraceDiff`.
- `ActivityReplayReport` exposes `isMatch`, `diff`, `finalClock`,
  `eventContext` and verbose output.
- The existing `TraceReplayer` was refactored to use
  `TraceFlavourDetector` (no behaviour change).

### JetBrains Autocomplete + Rename Refactoring (V2.0.41)

IntelliJ-side ergonomics for the `*.kuml.kts` DSL.

- `KumlCompletionItems` (pure Kotlin) defines 38 DSL completion items
  grouped by `ENTRY` / `UML` / `SYSML2` / `C4` / `SHARED` with insert
  text, tail hints and descriptions.
- `KumlCompletionContributor` (IntelliJ extension point) injects items
  in `*.kuml.kts` files; the insert handler replaces the typed prefix
  and positions the caret inside the lambda body.
- `KumlRenameExtractor` (pure Kotlin) finds all rename candidates for a
  DSL identifier; powers the IntelliJ rename refactoring action.

### Sandbox Guarantees (V2.0.40)

New module `kuml-runtime/kuml-runtime-sandbox` provides bounded,
isolated execution for guards and effects.

- `SandboxPolicy`: `guardTimeoutMs`, `maxVariableCount`,
  `maxStringLength`, `allowedFunctions` whitelist; ships `Strict` and
  `Permissive` presets.
- `SandboxException`: sealed hierarchy
  (`DisallowedFunction`, `VariableLimitExceeded`, `StringLengthExceeded`,
  `TooManyEffects`, `ExpressionTooDeep`, `ParseFailure`,
  `ReservedVariableName`).
- `BuiltInFunctions`: `log.*`, `math.*`, `str.*`, `list.*`, `map.*`,
  `convert.*` fully implemented.
- `EffectExecutor` runs `KumlEffect` sequences against
  `instance.variables`, enforcing the function whitelist and
  variable / string / effect / depth limits.
- `TimeLimitedGuardEvaluator` wraps the V2.0.36 evaluator with a
  configurable timeout and surfaces sandbox exceptions cleanly.
- `SandboxValidator` lints models against a policy at validation time
  rather than at run time.

### Runtime-Trace + STM Trace-Replay + OTLP-JSON export (V2.0.39)

New module `kuml-runtime/kuml-runtime-trace`.

- `TraceReplayer` re-executes a state machine from recorded events and
  diffs the replay against the original.
- `OtlpExporter` converts a `TraceFile` to OpenTelemetry OTLP-JSON
  without an external OTel dependency.
- `OtlpModel` (`@Serializable` data classes for OTLP resource / scope /
  spans / events / attributes), `OtlpIds` (deterministic FNV-1a span /
  trace IDs for golden-file tests), `OtlpJson` (dedicated `Json`
  instance without `classDiscriminator`, OTel-collector safe).
- `EventsFromTrace` extracts the canonical `Event` sequence from a
  `TraceFile` and filters synthetic events.

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
