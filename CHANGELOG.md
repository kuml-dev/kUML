# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.16.0] — 2026-06-22

### BPMN 2.0 — Neue Modellierungssprache (V3.1.1–V3.1.8)

Vollständige BPMN 2.0-Unterstützung als eigenständiges Metamodell
(eigenständiges pure-Kotlin-Metamodell, analog zu SysML 2).
Deckt Process, Collaboration, CLI-Integration, XML-IO und LaTeX-Renderer ab.
Choreography und Conversation folgen in V3.2.

**Metamodell (`kuml-metamodel-bpmn`) — V3.1.1+V3.1.4**

- Sealed-Interface-Hierarchie: `BpmnElement` → `BpmnFlowElement` → `BpmnFlowNode`
- `BpmnEvent` (Event-Matrix-Design): einzelne Klasse mit `(EventPosition × EventDefinition × EventBehaviour)`-Triplet + `init{}`-Guards (13 EventDefinitions, 3 Positions, 2 Behaviours)
- `BpmnGateway`: 5 Typen (EXCLUSIVE, INCLUSIVE, PARALLEL, EVENT_BASED, COMPLEX)
- `BpmnTask`, `BpmnSubProcess` (expanded/collapsed/transactional/triggeredByEvent), `BpmnCallActivity`, `LoopCharacteristics` (Standard + MultiInstance)
- `SequenceFlow` als Pattern-A-Edge: auf dem Modell mit `sourceRef`/`targetRef` + `conditionExpression`
- `BpmnDataObject`, `BpmnDataStore`, `DataAssociation`
- `BpmnProcess` mit `elementById()`
- Collaboration-Typen: `BpmnCollaboration`, `BpmnParticipant` (Pool), `BpmnLane` (verschachtelt via `childLanes`, Lane referenziert FlowNodes via `flowNodeRefs`), `MessageFlow` (Pattern A)
- `BpmnModel` + `ProcessDiagram` + `CollaborationDiagram` als `BpmnDiagram sealed interface`

**DSL (`@BpmnDsl`) — V3.1.2+V3.1.4**

- `bpmnModel { }` — Top-Level-Builder-Funktion
- `ProcessBuilder`: `startEvent()`, `endEvent()`, `intermediateEvent()`, `boundaryEvent()`, `task()`, `subProcess { }`, `callActivity()`, `gateway()`, `dataObject()`, `dataAssociation()`, `sequenceFlow()`
- Infix-Syntax: `"sourceId" flowsTo "targetId"`
- Auto-IDs: `{processId}_{type}_{counter}` (deterministisch)
- `CollaborationBuilder`: `pool { }`, `blackBoxPool()`, `messageFlow()`
- `PoolBuilder`: `lane { }`, `process()`; `LaneBuilder`: `contains()`, verschachtelte `lane { }`

**SVG-Renderer (`kuml-io-svg`) — V3.1.3+V3.1.5**

- Alle OMG-BPMN-Symbole: Events (dünner Ring START, Doppelring INTERMEDIATE, dicker Ring END), Gateways (Diamant + 5 Typ-Symbole), Tasks (abgerundete Rechtecke + 7 Typ-Marker), SubProcess (`+`-Marker), CallActivity (fetter Rahmen)
- Loop-/MultiInstance-Marker (↻ / ≡ / ‖)
- `BpmnSequenceFlowSvg`: gefüllter Pfeilkopf, Default-Slash, Condition-Label, Marker-ID per Regex sanitized
- Boundary-Events: `stroke-dasharray` für non-interrupting, `attachedToRef`-Positionierung
- Event-Symbole für alle 13 `EventDefinition`-Typen (catching=outline, throwing=filled)
- `BpmnPoolSvg`: Swimlane-Rahmen + gedrehtes Titel-Band (horizontal links, vertikal oben)
- `BpmnLaneSvg`: Lane-Trennlinien + Lane-Titel, rekursiv für verschachtelte Lanes
- `BpmnMessageFlowSvg`: gestrichelte Linie + offener Pfeilkopf + Kreis am Source
- `BpmnLayoutBridge`: FlowNodes → LayoutGraph (Task 120×60, Gateway 50×50, Event 36×36, DataObject 40×55); Pools als Container-Nodes; Boundary-Events als Child-Nodes

**CLI-Integration, Constraint-Checker, Vault-Beispiele — V3.1.6**

- `BpmnConstraintChecker` mit 7 Regeln: fehlende Start-/EndEvents (WARNING), unbekannte SequenceFlow-Referenzen (ERROR), XOR/OR-Gateway ohne defaultFlow (WARNING), ungültige BoundaryEvent-attachedToRef (ERROR), MessageFlow source == target (ERROR), Participant.processRef auf nicht-existierenden Prozess (ERROR)
- CLI erkennt `BpmnModel` als Skript-Rückgabetyp und ruft BPMN-Renderer auf
- Constraint-Violations werden als Warnings in CLI-Output integriert
- 3 neue Vault-Beispiele: Order Fulfillment (Process), Document Review (SubProcess+Loop), Customer-Supplier (Collaboration 2 Pools)

**BPMN 2.0 XML Import/Export (`kuml-io-bpmn`) — V3.1.7**

- Neues Modul `kuml-io-bpmn` (analog zu `kuml-io-arxml`)
- `BpmnXmlExporter`: BPMN 2.0 XML ohne JAXB (Kotlin stdlib), Namespace `http://www.omg.org/spec/BPMN/20100524/MODEL`; alle FlowNode-Typen, SequenceFlow+ConditionExpression, Collaboration
- `BpmnXmlImporter`: namespace-aware DOM-Parser, XXE-Schutz (`disallow-doctype-decl` + external-entities deaktiviert), unbekannte Tags ignoriert
- `BpmnXml`: Convenience-API (`export()`, `import()`)
- Roundtrip-Konsistenz: Export → Import → gleiche id/name/Typen

**LaTeX/TikZ-Renderer (`kuml-io-latex`) — V3.1.8**

- `BpmnLatexRenderer`: ProcessDiagram + CollaborationDiagram als TikZ-Output
- 15 neue TikZ-Styles: `kuml-bpmn-start/end/intermediate/boundary`, `kuml-bpmn-gateway`, `kuml-bpmn-task/subprocess/callactivity`, `kuml-bpmn-pool/lane/pool-header/lane-header`, `kuml-bpmn-flow/msgflow`
- Event-Symbole über LaTeX-Math-Zeichen (`\bowtie`, `\bullet`, `\lightning`, `\triangle` etc.)
- Gateway-Typ-Symbole (`\times`, `+`, `\bigcirc` etc.)
- Pool-Header mit `\rotatebox{90}`
- LaTeX-Injection-Schutz: alle Modell-Felder durch `LatexEscape.escape()`
- TikZ-ID-Sanitizing: `[^a-zA-Z0-9]` → `_`
- 3 neue `KumlLatexRenderer.toLatex()`-Überladungen für BPMN

### C4 LaTeX/TikZ-Renderer (`kuml-io-latex`) — V3.1

- `C4LatexRenderer`: alle 6 C4-Elementtypen (Person, System, Container, Component, ExternalSystem, DeploymentNode) für alle 5 C4-Diagrammtypen als TikZ-Output; 10 neue TikZ-Styles (`kuml-c4-person`, `kuml-c4-system` etc.); monochrom/weiß als Default, überschreibbar

### Composite-Structure-Renderer

- **Nested-Parts-Rendering-Fix**: `UmlComponent.nestedComponents` werden jetzt von `UmlContentSizeProvider` und `UmlComponentSvg` berücksichtigt — composite Components renderten zuvor als leere Box. Rekursives Rendering verschachtelter Parts als gestapelte Boxen; Port-Clearance rückt Parts ein, wenn der Parent Boundary-Ports hat.
- **Port-zu-Part-Connectors**: Delegation-Connectors (Boundary-Port → innerer Part-Port) und Assembly-Connectors (Part-Port → Part-Port) in Composite-Structure-Diagrammen. Connector-Routing rein im SVG-Renderer (ohne ELK); `UmlLayoutBridge` filtert diese Connectors aus dem ELK-Graph. Guards: Cycle/Depth-Schutz, Connector-Cap (500).

### UML-Association-Decoration

- Rollennamen werden jetzt zusammen mit der Multiplizität an jedem Association-Ende gerendert (`UmlEdgesSvg`) — zuvor fehlten die Rollennamen im Output. Regressionstest am Order-Domain-Beispiel.

### BPMN-Process-Renderer-Fix

- BPMN-**Process**-Diagramme renderten über die CLI/Web-Pipeline als leeres Canvas, wenn das DSL `diagram(name, processId)` ohne expliziten `include()`-Block verwendet wurde. Drei gestapelte Ursachen behoben: leeres `elementIds` = „alle Elemente anzeigen" (Konvention wie `Sysml2LayoutBridge`), expandierte Sub-Process-Kinder + innere Flows korrekt einbezogen, neuer rekursiver `BpmnProcess.renderableElements()`-Helfer für den Renderer-Index (CLI + Web).

### Beispiele & Dokumentation

- **Named-Parameter-Sweep**: alle DSL-Beispiele (`kuml-examples`, Vault-Beispiele, Handbuch-Snippets) auf benannte Parameter umgestellt — konsistent mit kUMLs LLM-first-Designprinzip.
- 3 neue BPMN-Vault-Beispiele (Order Fulfillment, Sub-Process Loop, Customer-Supplier Collaboration) in die CI-Smoke-Tests aufgenommen (jetzt 33 Beispiele).
- READMEs + Antora-Handbuch um BPMN-2.0-DSL- und XML-I/O-Referenz erweitert.

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
- Activity-Diagramme mit Partitionen behalten jetzt einen lesbaren Spalt
  zwischen Lanes; vorher konnten benachbarte Action-Boxen direkt an die
  Lane-Trennlinie anschlagen.

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

### Renderer-Validierung 2026-06-11 — 15 of 17 visual defects fixed

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

### JetBrains Autovervollständigung + Rename Refactoring (V2.0.41)

IntelliJ-side ergonomics for the `*.kuml.kts` DSL.

- `KumlCompletionItems` (pure Kotlin) defines 38 DSL completion items
  grouped by `ENTRY` / `UML` / `SYSML2` / `C4` / `SHARED` with insert
  text, tail hints and descriptions.
- `KumlCompletionContributor` (IntelliJ extension point) injects items
  in `*.kuml.kts` files; the insert handler replaces the typed prefix
  and positions the caret inside the lambda body.
- `KumlRenameExtractor` (pure Kotlin) finds all rename candidates for a
  DSL identifier; powers the IntelliJ rename refactoring action.

### Sandbox-Garantien (V2.0.40)

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
