# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.33.0] — 2026-07-13

### Added

**ERM mapping profile: `kotlinObjectName` tag for the UML `«Entity»` stereotype**

The ERM Kotlin DSL already let a model override the generated Kotlin identifier for
an Exposed `Table` object (`EntityBuilder.kotlinObjectName(name)`, shipped in v0.32.0)
— but the UML mapping profile (`ermMappingProfile`, used by every class-diagram author
who annotates entities with `«Entity»(tableName = ..., schema = ...)`) had no
equivalent tag, so the override was unreachable from the DSL surface most models
actually use. Closes that DSL-completeness gap (ADR-0017): `«Entity»` now accepts an
optional `kotlinObjectName` tag, and `UmlToErmTransformer` reads it and propagates it
through `ErmMetadataKeys`/`MutableErmEntity` into the generated Exposed artifacts —
identical effect to the ERM-DSL path, just reachable from UML class diagrams too.

Found and closed during a real second MDA retrofit attempt against Lapis Cloud
(2026-07-13): every one of its 34 hand-written Exposed `Table` objects uses an
`XxxTable` naming convention that the mechanical PascalCase default can't produce,
and the project's models are authored in UML via `ermMappingProfile`, not the ERM DSL
directly.

## [0.32.0] — 2026-07-13

### Added

**Gradle plugin publishing: `dev.kuml` wired for the Gradle Plugin Portal + Maven Central (ADR-0016 retrofit gap)**

The `dev.kuml` Gradle plugin (`kuml-gradle-plugin`) is now wired for publication to both
the Gradle Plugin Portal and Maven Central instead of remaining local-build-only.
`com.gradle.plugin-publish` reuses the `pluginMaven` + marker publications that
`java-gradle-plugin`/`maven-publish` already create, `signAllPublications()` is now gated
on a configured signing key so `publishToMavenLocal` keeps working without GPG credentials,
and a new gated `publish-gradle-plugin` CI job follows the same
detect-token/skip-gracefully pattern as the JetBrains plugin publish step. **The actual
Gradle Plugin Portal publish still requires `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET`
repository secrets, which are not yet configured** — the CI job skips gracefully until
they are set. `install.adoc`/`tooling/gradle.adoc` were corrected to stop claiming an
existing publication and now document the `publishToMavenLocal` workaround.

**ERM: enum column support across the UML→ERM→Exposed/SQL pipeline (ADR-0016 retrofit gap)**

`UmlToErmTransformer` now maps a UML enumeration-typed property to a new
`ErmDataType.Enum`, closing a gap where every enum attribute silently degraded to
`Varchar` with a comment. `ErmSqlEmitter`/`ErmSqlTypeMapper` render it as
`VARCHAR(longest literal)` plus a `CHECK (col IN (...))` constraint on every SQL dialect,
and `ErmExposedEmitter` generates a companion Kotlin enum class referenced via
`enumerationByName<T>(...)`. Human-readable literals (e.g. `"In Progress"`) that aren't
valid Kotlin identifiers are sanitized into PascalCase constant names, backed by a
constructor `dbValue` field + `Table.customEnumeration(...)` so the physical column keeps
storing the original literal.

**ERM: overridable Kotlin object names for generated Exposed tables (ADR-0016 retrofit gap)**

Generated Exposed `Table` objects were named by mechanically PascalCasing the entity name
(`member` → `Member`), breaking retrofits whose existing code expects a different
identifier (e.g. `MemberTable`). A new `EntityBuilder.kotlinObjectName(name)` DSL method
(backed by `ErmMetadataKeys.KOTLIN_OBJECT_NAME`) lets a model override just the generated
Kotlin identifier — the physical `Table("...")` string literal still always follows the
entity name. The override is validated as a Kotlin identifier and propagates correctly
into generated foreign-key `reference()` calls; collision detection still fires against
other overrides and generated enum object names.

### Fixed

**`ErmExposedEmitter` retargeted at the Exposed 1.x package layout (ADR-0016 retrofit gap)**

Generated Exposed code emitted imports and `reference()`/`optReference()` calls against
Exposed's pre-1.0 package paths (`org.jetbrains.exposed.sql.*`), which no longer resolve
against Exposed 1.3.1's `org.jetbrains.exposed.v1.*` layout — generated code failed to
compile against the current Exposed stack. Imports now target
`org.jetbrains.exposed.v1.core.*`, and `reference()`/`optReference()` pass the target
*column* rather than the target table, matching Exposed 1.3.1's `Table.reference()`
overload for plain (non-`IdTable`) tables. UUID columns keep rendering via `javaUUID(...)`
rather than Exposed 1.x's own `uuid()`, preserving the emitter's documented type-mapping
contract.

_Context: all four items above close the concrete gaps found during a real MDA retrofit
attempt against the Lapis Cloud project on 2026-07-12/13 (ADR-0016, UML-to-Exposed
pipeline). A second real retrofit attempt against Lapis Cloud with these fixes has not
yet been run._

## [0.31.0] — 2026-07-11

### Added

**Knowledge Workspaces: `kuml-workspace` core module + OKF type vocabulary 16 → 29 (ADR-0011 FT-2, V3.6.1)**

Wave 1 of V3.6.1 extracts the OKF workspace core (`Frontmatter`, `OkfDocument`/`OkfWorkspace`,
`OkfType`, `OkfValidator`, `WorkspaceScanner`) out of `kuml-cli` into a new
`kuml-docs:kuml-workspace` module with an explicit public API, making the workspace model
reusable outside the CLI (Desktop viewer, future `kuml-okf-types` artifact). The OkfType `type:`
vocabulary grows from 16 to 29 entries — 8 SysML 2, 4 BPMN, and 1 Service Blueprint diagram type,
each `requiresKumlBlock=true` — with `since`/`description` properties and a
`VOCABULARY_VERSION` constant, backed by a machine-readable contract at
`src/main/resources/okf/kuml-okf-vocabulary.json` kept in lockstep with the enum by a bijective
parity test. The tolerant mode-only TOML regex is replaced by a hand-rolled
`WorkspaceMarkerParser` covering the full `[workspace]`/`[okf]` marker subset (mode, name,
kuml-version, version, vocabulary, strict). New `kuml workspace validate --strict-vocabulary`
escalates an unrecognised `type:` (OKF-W-002) from WARNING to ERROR, with a Levenshtein-based
did-you-mean suggestion for near-miss type values. `WorkspaceCommand`/`WorkspaceRenderer` stay in
`kuml-cli` and only change their imports. A new "Knowledge Workspaces" handbook page documents
the marker format, the full vocabulary table, and the OKF-* finding codes.

**`kuml workspace init` scaffold + shared CLI scaffold engine (ADR-0011 FT-4, V3.6.2)**

New `kuml workspace init --mode knowledge|engineering --name <name>` scaffolds a fresh OKF
workspace: `knowledge` mode (the default) writes a 5-file starter set — `.kuml-workspace.toml`,
`index.md` (`type: KumlWorkspace`), an `Article`, a `UmlClassDiagram` with a minimal example
diagram, and a `Glossary` — that passes `kuml workspace validate` with zero findings and
renders cleanly; `engineering` mode writes a bare `.kuml-workspace.toml`, a starter
`<slug>.kuml.kts` script, and a `.gitignore`. The `{{var}}` template engine and file-writing
loop previously private to `kuml plugin init` were extracted into a shared
`dev.kuml.cli.scaffold.Scaffolder` engine so both commands render templates and enforce the
non-empty-target-dir `--force` guard the same way; `PluginScaffolder` now delegates to it with
no behavior change. Also fixes a code-injection-adjacent bug caught during review: the
user-supplied workspace name was interpolated unescaped into generated Kotlin/kuml script
content; the scaffold now properly escapes it for Kotlin string-literal context.

**Fictional sample-association Knowledge Workspace demo + rot-guard test (V3.6.3)**

FT-3 of V3.6.3 adds a self-contained, fictional Bürgerverein e.V. OKF knowledge workspace under
`kuml-examples` as a scan+validate+render demo of the `kuml-workspace` core (V3.6.1). The new
`sample-association-charter/` ships a `.kuml-workspace.toml` marker plus `articles/`, `concepts/`,
`glossary/`, and `models/` documents covering a fictional association's charter, membership
lifecycle, organs, and dispute resolution — three OKF models (class + state diagrams) embedded as
` ```kuml``` ` blocks. A new `SampleAssociationWorkspaceTest` + `WorkspaceExampleRenderer` scans
the workspace with `WorkspaceScanner`, validates it with `OkfValidator`, extracts diagrams via
`KumlScriptHost`/`DiagramExtractor`, and renders them through the layout/theme pipeline as a
rot-guard against regressions in the workspace core or renderer pipeline.

**kUML Desktop: read-only Knowledge Workspace viewer (V3.6.4)**

Adds "File → Open Workspace..." to kUML Desktop: a directory chooser that mode-detects via
`WorkspaceScanner` (`kuml-docs:kuml-workspace`) and opens either a three-pane Knowledge Workspace
view (document tree | rendered Markdown | live SVG preview for kuml-blocks, reusing
`DesktopRenderPipeline`) or, for Engineering workspaces, a symmetric file tree that loads a
selected `.kuml.kts` into the existing single-file editor. Unknown-mode directories show a
chooser dialog per the ADR-0011 fallback rule. Markdown rendering uses a minimal custom renderer
(`org.jetbrains:markdown`-based) rather than the originally proposed
multiplatform-markdown-renderer dependency. ERM diagrams in a Knowledge Workspace show a clear
"not yet supported in Desktop" message instead of crashing. Internal document links navigate the
tree selection via a new `WorkspaceLinkHandler` seam (external links open the system browser).

### Security

**kUML Desktop Knowledge Workspace viewer: mandatory trust dialog before any kuml-block evaluation**

Opening a Knowledge Workspace in kUML Desktop can evaluate `kuml-block` scripts from disk. A
mandatory "Trust this workspace?" dialog (`WorkspaceTrust`, `TrustDialog`) now gates all
kuml-block evaluation on first open of a workspace root, with the decision persisted per path in
`AppSettings` so it isn't re-asked. No kuml-block can be rendered before trust is confirmed.

## [0.30.1] — 2026-07-11

### Fixed

**ERM Chen notation: cardinality label collisions at hub entities**

Chen-notation `1`/`N` cardinality labels rendered incorrectly in the SVG
renderer: the source-side along-edge offset direction was inverted, landing
labels inside the entity box on top of its title; labels had no perpendicular
offset, so they straddled the connector line; and they lacked the halo pass
already used by every other ERM edge label, making them unreadable wherever
they crossed a line. Cardinality labels now route through a new shared
`renderErmCardinalityLabel` helper with deterministic per-hub stacking, so
labels converging on the same hub entity (multiple relationships meeting at
one entity) fan apart instead of overlapping, plus a bounded clearance guard
against residual title overlap. A new regression test covers title clearance,
off-line perpendicular offset, hub fan-out separation, and render
determinism.

## [0.30.0] — 2026-07-11

### Added

**ERM SQL/Exposed code generators: PostGIS geometry + TimescaleDB hypertable hooks (ADR-0016 §2.3)**

The ERM SQL and Exposed code generators can now emit PostGIS geometry columns
and TimescaleDB hypertables without a new `ErmDataType` variant or a runtime
plugin SPI. A new `CustomTypeHooks` extension point recognizes
`ErmDataType.Custom` raw strings shaped like
`geometry(Point|LineString|Polygon|Geometry[,SRID])` through an anchored,
keyword-whitelisted, SRID-length-capped regex; `ErmSqlTypeMapper` normalizes
matching columns to their canonical Postgres type (other SQL dialects are
unaffected), and `ErmExposedEmitter` renders a dependency-free
`geometry(name, sqlType)` column call backed by a new `PostGisColumnTypes.kt`
support file. Separately, a new `EntityBuilder.hypertable(timeColumn,
chunkInterval?)` DSL method (backed by `ErmMetadataKeys.HYPERTABLE`) marks an
entity as a TimescaleDB hypertable: `ErmSqlEmitter` emits a `SELECT
create_hypertable(...)` call right after `CREATE TABLE` on Postgres, while the
Exposed emitter adds an explanatory comment only, since Exposed has no native
equivalent. The free-text `chunkInterval` value is whitelist-validated before
being interpolated into SQL. Out of scope for this wave: a general GIS type
system, a ServiceLoader/plugin SPI, automatic hypertable inference, and a
UML «Hypertable» stereotype/profile authoring path.

**ERM: additive-only schema-diff SQL migrations (ADR-0016 deferred item)**

A new `kuml generate --sql-migration --from <old.kuml.kts> --to <new.kuml.kts>
--version <v> --description <desc>` CLI mode computes the delta between two
ERM model snapshots and writes a single Flyway-named migration file
containing only safe, additive DDL (`CREATE TABLE`, `ALTER TABLE ... ADD
COLUMN`, `CREATE INDEX`, `CREATE VIEW`, `ALTER TABLE ... ADD CONSTRAINT ...
CHECK`). Any destructive or ambiguous change — dropped or renamed entities or
columns, type or primary-key changes, and the like — makes generation refuse
with the complete list of blockers reported at once, instead of a
fix-one-rerun-repeat loop. The new `ErmSchemaDiffEmitter` reuses
`ErmSqlEmitter`'s existing per-element DDL fragment renderers rather than
duplicating identifier-safety or rendering logic.

### Fixed

**ERM: compartment divider position, self-loop routing, and per-notation SVG frame labels**

Entity/view compartment dividers (Martin, Bachman, IDEF1X, and the view
query-preview divider) were positioned at the midpoint of their gap and
visually cut through the first row of the next compartment; dividers now sit
at the top of the gap instead. ERM self-referencing relationships (e.g. a
`Category` entity's "subcategory of" self-FK) now route through the same
self-loop router already used by UML/C4 diagrams instead of ELK's cramped raw
route, fixing role-label collisions in Martin/Bachman/IDEF1X notation. SVG
frame labels also now report the specific notation (`erm/martin`,
`erm/bachman`, `erm/chen`, `erm/idef1x`) instead of a shared, ambiguous `erm`.
The widened FK-hub layout spacing used for dense ERM entities is now a single
shared constant (`ErmLayoutBridge.WIDENED_SPACING_HINTS`) referenced by every
production render path — CLI `render` and `dump-json`, the web playground, and
Asciidoctor handbook rendering — closing a gap where `dump-json` used to
diverge from `render` for Chen/IDEF1X scripts.

## [0.29.0] — 2026-07-10

### Added

**`kuml-language-server`: editor-agnostic LSP server (Wave 2–3)**

A new `kuml-language-server` module implements an LSP4J-based Language Server
Protocol server for kUML, giving any LSP-capable editor real-time diagnostics
and autocomplete instead of relying solely on CLI round-trips. Diagnostics are
produced by invoking the kUML CLI as a subprocess and translating its
validation output into LSP diagnostics with correct document ranges
(`DiagnosticsRunner`, `RangeMapping`). Completion serves the full 38-item
`KumlCompletionItems` catalogue via `CompletionMapper`, with per-group
`CompletionItemKind`s, snippet-style insert text with sequential tab stops,
and lazily-filled documentation on `completionItem/resolve`. The
editor-agnostic completion/rename/diagnostics/CLI-locator logic was extracted
from `kuml-jetbrains-plugin` into a new pure-Kotlin `kuml-lang-support`
module (no IntelliJ Platform dependency) so the JetBrains plugin and the LSP
server share one implementation instead of diverging.

**VS Code extension: LSP client + live-preview webview**

The VS Code extension now locates and launches the kUML language server via
an LSP client, and adds a live-preview webview panel with sanitized SVG
rendering — editor-integrated diagnostics and completion plus an in-editor
diagram preview, instead of relying solely on CLI round-trips.

**Interactive OCL guard editing (state-machine transitions)**

Transition guards (OCL boolean expressions) can now be edited live, both
programmatically and through a dedicated editor widget:
- `OclSyntax` is a new public facade for editor tooling (syntax highlight,
  live type-check) that validates an OCL expression against a scope without
  throwing, returning a structured `OclCheckResult` with position info for
  diagnostics. `OclParser`'s unbounded recursive-descent entry points now
  share a recursion-depth guard so pathological input (deeply nested parens
  or unary/`not` chains) fails cleanly instead of risking a
  `StackOverflowError` that would bypass the post-parse
  `MAX_NESTING_DEPTH` check.
- Transitions can be marked `protected` (`UmlTransition.isProtected`, a new
  `protected` DSL flag on `TransitionBuilder`), and a new
  `EditPolicy.guardEditGate(transition)` decision function
  (`Denied`/`Allowed`/`RequiresConfirmation`) in `kuml-widget-compose` makes
  protected transitions always demand confirmation before a guard edit is
  applied, preventing accidental silent edits to explicitly locked guards.
- `ModelPatch.ChangeGuard` + `applyPatch` (runtime) let a transition's OCL
  guard be edited on a running `StateMachineInstance` without in-place
  mutation: the new guard is statically type-checked, protected transitions
  are gated behind explicit confirmation, and a new `MigrationPolicy.onPatch`
  structural hook is consulted before atomically rebuilding a fresh instance
  over the patched model — closing the gap between static model authoring
  and live runtime edits.
- `OclGuardEditor` is a new Compose Multiplatform text field with live
  syntax highlighting for OCL guard expressions (`OclHighlightTransformation`
  + a dedicated `OclHighlightColors` scheme), wired into `BehaviourWidget`
  via a `ChangeGuard` bridge so edits flow through `BehaviourWidgetState`
  into `ModelPatch` application. Covered by new Compose UI robot tests
  (`ComposeUiTestSupport`, stable `EditorTestTags`) for the guard-editing
  flows in `OclGuardEditor` and `ControlPanel`.

**`kuml-web`: drag-and-drop node placement for class diagrams (V3.2 Waves 1–4)**

Class-diagram nodes in the web preview pane can now be dragged to a new grid
position instead of only round-tripping through the DSL editor:
- `LayoutHintService` + `POST /api/layout/hint` persists a dropped element's
  grid cell back into the DSL script (`UmlModelDslPrinter` relocated into
  `kuml-core-dsl` so both the CLI `reverse` command and the web layer share
  it).
- `RenderResponse` gained per-node hit-test boxes (`NodeBox`) and an
  approximate uniform grid (`GridGeometry`), extracted from the same
  `LayoutResult` used by the SVG renderer, so a client can hit-test nodes and
  resolve pointer drops to grid cells (`GridCellResolver`) without
  re-implementing layout math. Grid extraction is currently limited to class
  diagrams.
- The web UI preview pane gained a pointer-events-based drag gesture
  (`DragController`: pointer down/move/up/cancel via event delegation) that
  maps screen pixels to SVG viewBox user-space, mirrors the server-side
  `GridCellResolver`, and applies the drop through the existing CodeMirror
  editor + re-render flow.
- UX polish: hover/grabbing cursor affordance, an occupied-cell snap-preview
  highlight, and a distinct auto-dismissing error banner for drag-and-drop
  placement errors so transient drop errors no longer read as persistent
  render failures.

**`kuml-web`: ERM diagrams render end-to-end (all four notations)**

`ExtractedDiagram.Erm` is now wired through `WebRenderPipeline` (ELK layout
via the `Erm`/`ErmChen`/`ErmIdef1x` layout bridges, SVG/PNG output),
replacing the V3.4.1 stub that hard-errored on every ERM script. The
`/api/render` request gained an optional notation override
(`martin`/`bachman`/`chen`/`idef1x`) and `/api/examples` gained an ERM
example; ERROR-severity constraint violations block the render while
WARNINGs are ignored, mirroring the CLI's non-blocking treatment.

### Fixed

**ERM: self-loop relationship-name/role label collisions**

Self-referential ERM relationships (e.g. `Category -> Category`) always
rendered the relationship-name label with `text-anchor="middle"` and pushed
both role labels the same direction, so on tight self-loop routes the name
label straddled the entity border and the two role labels converged on it.
A shared, route/direction-aware `ErmEdgeLabels` helper (with a `perpBias`
param so self-loop role labels diverge from the shared name label) is now
used by all three ERM notations (Martin, Bachman, IDEF1X), consolidating
previously triplicated label-rendering logic.

## [0.28.0] — 2026-07-09

### Added

**Security: resource bounds (DoS guards) for the execution-free DSL interpreter**

The execution-free DSL interpreter in `kuml-core-script` never compiles or runs
bytecode, so it carries no RCE risk — but without bounds a *pathological* input
could still hurt the host process (memory exhaustion during lexing, a
`StackOverflowError` escaping the recursive-descent parser, or CPU burn on a huge
flat input). It now enforces four resource bounds: an input-size cap, a parse
recursion-depth guard, a wall-clock timeout, and `StackOverflowError`
containment. Limits are configured via the new `InterpreterLimits` value type
(defaults 100 000 chars / depth 64 / 5 s) and applied through a new
backward-compatible `evaluate(source, fileName, limits)` overload; the existing
`evaluate(source, fileName)` signature is unchanged and now delegates to
`InterpreterLimits.DEFAULT`. Over-limit inputs are rejected cheaply as an
`EvaluatedScript.Failure` without an exception escaping the evaluator.

### Fixed

**BPMN Choreography: branch edges crossed intervening tasks + condition labels slid under the target box**

`ChoreographyGridLayout.routeFlow` produced its vertical jog at the horizontal
midpoint regardless of intervening nodes, so a gateway spine-branch that skips a
cross-lane task (e.g. `gw → Lieferung` over `Nachbestellen` in the "37 BPMN
Choreography" example) ran straight through that task's box. Routing is now
obstacle-aware: when the default route would cut through another node's bounds,
the jog moves into the clear gap right after the source so the long horizontal
run happens on the *target* lane instead. Separately, `BpmnChoreoSvg`'s
sequence-flow condition/name label was anchored at the array midpoint (the corner
kink at the target's edge) with only a +4px offset, so labels like "nicht auf
Lager" slid under the target task; it now uses `EdgeLabelGeometry.midAnchor` +
`renderEdgeLabelWithHalo` (the same longest-segment, haloed placement as message
flows and C4/UML edges).

**Edge stereotype labels (`«FK»` etc.) rendered without background contrast**

`StereotypeHelper.renderEdgeStereotype()` emitted a single `<text class="kuml-stereotype">`
for association/generalization/realization/dependency/connector display-label stereotypes,
so the edge polyline underneath visually cut through the italic glyphs (most visible on
short vertical edges, e.g. the `«FK»` label in the "38 UML Profil – Exposed" example).
Edge role/name labels already used a two-pass halo technique (`kuml-edge-label-halo` +
`kuml-edge-label`); `renderEdgeStereotype` now does the same via a new `kuml-stereotype-halo`
CSS class, drawn before the visible copy. In-box header/feature stereotypes (`renderHeader`,
`featureStereotypeTspan`) are unaffected — they never sit on a line.

## [0.27.0] — 2026-07-09

### Added

**ERM (Entity-Relationship Modelling) as a seventh first-class metamodel (V3.4.1)**

New standalone module `kuml-metamodel-erm`: `Entity`/`Attribute`/`Relationship`/`View`/
`Index`/`CheckConstraint` model plus a notation-aware `ermModel { }` DSL (Martin/Crow's-Foot,
Bachman, Chen, IDEF1X as a single `notation` property on the model). Depends only on
`kuml-core-model` — no reuse of `kuml-metamodel-uml`. `ErmConstraintChecker` ships 19
structural rules (primary/foreign key, view, index, check-constraint validation, plus the
category/discriminator rules added in V3.4.5). Wired into `kuml validate` (new `"erm"`
category in the existing `"structural"` JSON section), the MCP validate tool, and the example
catalog.

**Four ERM notations as SVG renderers (V3.4.2–V3.4.5)**

- **Martin/Crow's-Foot (V3.4.2)** — `ErmMartinSvg`, `ErmMartinEdgeSvg`, `ErmSizing`,
  `ErmViewSvg`, a dedicated `ErmContentSizeProvider`/`ErmLayoutBridge` (ELK-based, analogous
  to the UML class-diagram layout path), wired into `KumlSvgRenderer`, the desktop/web/
  Asciidoctor render pipelines, and the Vault example renderer.
- **Bachman (V3.4.3)** — arrow-and-circle notation sharing layout and entity/view rendering
  with Martin via a new common `renderErm` body; only the relationship-edge glyph
  (`ErmBachmanEdgeSvg`) differs.
- **Chen (V3.4.4)** — classic entity-rectangle / relationship-diamond / attribute-ellipse
  rendering with its own sizing and layout bridge (`ErmChenSvg`, `ErmChenSizing`,
  `ErmChenLayoutBridge`).
- **IDEF1X (V3.4.5)** — identifying/non-identifying relationship lines, rounded corners for
  dependent entities, and a new `ErmCategory` element (supertype/subtype hierarchies with a
  discriminator circle and completeness line), backed by constraint rules 16–19.

Notation is selected in the DSL (`notation = MARTIN`) with a CLI override
(`kuml render --notation <martin|bachman|chen|idef1x>`).

**M2M transformation UML → ERM (V3.4.6)**

New module `kuml-transform-uml-to-erm` (`UmlToErmTransformer`): classes become entities,
associations become foreign keys or join tables, including root-to-leaf resolution of
`JOINED` inheritance and collision-safe FK column naming from association-end roles. Ships
with a companion profile module `kuml-profile-erm` (`ErmMappingProfile`) for mapping
overrides (inheritance strategy, junction-table/FK names), plus `UmlToErmScriptTransformer` /
`ErmScriptRenderer` to emit the ERM model directly as a runnable Kotlin Exposed script.

**ERM → Kotlin Exposed (V3.4.8)**

Shared engine `ErmToExposedTransformer` / `ErmExposedGenerator` (`ErmExposedEmitter`) reachable
through three entry points: `erm-to-exposed` (direct M2M), the `exposed` codegen plugin
(ERM-first via `kuml generate`), and `UmlToExposedViaErmScriptTransformer`
(`uml-to-exposed-via-erm`, chaining UML → ERM → Exposed via `kuml transform`). Many-to-many
associations become a real junction table with a composite primary key.

**SQL → ERM reverse engineering (V3.4.9)**

New module `kuml-codegen-reverse-sql` (JSqlParser 5.3): `PostgresErmReverseEngine` parses
PostgreSQL DDL in a two-pass pipeline (`CREATE TABLE` → entities/attributes with type mapping
and constraint parsing, then `ALTER TABLE`/`CREATE INDEX`/`CREATE VIEW` resolved against the
table index, then relationship inference for identifying/weak entities and cardinality). New
reverse SPI (`ErmReverseEngine`/`ErmReverseEngineRegistry`) in `kuml-codegen-reverse-api`
parallel to the existing UML reverse SPI. CLI: `kuml reverse <file|dir> --format sql
[--sql-dialect postgres]` via the new `ErmModelDslPrinter`, which renders the recovered model
back as `ermModel { }` DSL source. New exit code `REVERSE_SQL_PARSE_FAILED` (17).

**OKF workspaces: `kuml workspace` CLI (ADR-0011 FT-1, spike)**

New `kuml workspace` command with `info`/`validate`/`render` subcommands for Obsidian-vault
workspaces in the Open Knowledge Format: a YAML frontmatter parser, `OkfValidator`, a
`WorkspaceScanner` driven by `.kuml-workspace.toml`, and a `WorkspaceRenderer` that renders
embedded ` ```kuml ` blocks from workspace notes (with path-traversal-safe block-name
sanitization for file output).

**DSL stereotypes for attributes and associations**

`AssociationBuilder`/`ClassBuilder` can now carry stereotypes on attributes and associations;
the relationships metamodel stores them, `StereotypeHelper` renders them in SVG output, and
`UmlContentSizeProvider` accounts for them in content-aware node sizing.

**ERM Vault example + MCP catalog wiring**

New Vault example "39 ERM Martin – E-Commerce Schema" (neutral e-commerce domain: foreign
keys, self-reference, weak/identifying entity, multi-FK fan-out, views/indexes/checks),
added as a `kuml-vault-examples-tests` resource. `ExampleCatalog` gains `erm`/`martin` as a
sixth catalog language. New handbook page `erm-dsl.adoc` (all four notations live-rendered)
wired into the nav and `codegen.adoc`; README.adoc swept (intro, feature table, ERM Support
section, comparison table).

### Changed

**`kuml-gen-sql` repointed from UML stereotype matching to the ERM metamodel (V3.4.7)**

New `ErmSqlEmitter` is the single DDL-rendering engine for two equivalent input paths:
`SqlDdlGenerator` (UML direct path: `KumlDiagram` → `UmlToErmTransformer` → `ErmModel` →
`ErmSqlEmitter` → DDL) and the new `ErmSqlDdlGenerator` (ERM-first, via a new
`ErmCodeGenerator`/`ErmCodeGenRegistry` SPI in `kuml-codegen-api`). The emitter now supports
composite primary keys, indexes, views, check constraints, and referential actions (`ON
DELETE`/`ON UPDATE`) — none of which the old UML direct path could express. FK columns are
emitted inline in `CREATE TABLE` instead of via `ALTER TABLE ADD COLUMN`, many-to-many
associations produce real junction tables instead of TODO comments, and enums are emitted as
`VARCHAR` + `CHECK` instead of Postgres `CREATE TYPE` (deliberate behavior change). The Dual-
Annotations workaround from ADR-0016 (`«Table»` + `«Entity»` on the same class) is no longer
necessary now that the SQL path runs through the typed ERM model. `FlywayBaselineGenerator`
gains an ERM-first counterpart, `ErmFlywayBaselineGenerator`.

**`uml-to-exposed` / `uml-to-exposed-psm` deprecated (V3.4.8)**

Both remain fully functional; the recommended path for new UML → Exposed work is now the
ERM-based chain (`uml-to-exposed-via-erm`) introduced in the same wave.

### Fixed

- **`kuml-asciidoc`** — removed a leftover "ERM not yet supported" stub in
  `AsciidocRenderPipeline` (predating the ERM renderers of V3.4.2–V3.4.5), which blocked the
  Antora build of the new `erm-dsl.adoc` handbook page.
- **Handbook/CLI docs** — corrected the SQL generator option key from `dialect` to
  `sql-dialect` (the actual key read by `SqlDdlGenerator`/`ErmSqlDdlGenerator`) in
  `erm-dsl.adoc`, `codegen.adoc`, `cli.adoc`, and `quickstart.adoc`. The wrong key silently
  produced PostgreSQL DDL regardless of the requested dialect, since `postgres` is the
  default.

## [0.26.0] — 2026-07-07

### Added

**MDA persistence pipeline: UML → Kotlin Exposed ORM + Flyway baseline (ADR-0016)**

Adds a full model-driven-architecture path from a UML class diagram to a runnable Kotlin
Exposed persistence layer, driven by the Pepela Portal / Lapis Cloud stack (Kotlin + Exposed
ORM + PostgreSQL + Flyway). Delivered as three additive waves:

- **`uml-to-exposed` (Variante B — direct transformer)** — new module
  `kuml-codegen/kuml-codegen-m2m-exposed` with `UmlToExposedTransformer`, an M2M transformer
  (analogous to the existing `uml-to-jpa`) that emits one Kotlin Exposed `Table` object per
  `UmlClass` (`object Users : Table("users") { val id = long("id").autoIncrement(); ...;
  override val primaryKey = PrimaryKey(id) }`). Many-to-one associations become `reference()`
  foreign-key columns; many-to-many/collection sides and self-referential associations are
  intentionally skipped with an explanatory comment rather than emitting non-compiling code.

- **`uml-to-exposed-psm` (Variante A — renderable PSM)** — new profile module
  `kuml-profile-exposed` (extends `javaEeProfile`) defining `«Table»`, `«Column»`, and `«FK»`
  stereotypes, plus `UmlToExposedPsmTransformer` in the same module, which turns a raw PIM into
  a real, diagram-renderable UML model. Classes are dual-annotated (`«Table»` for PSM semantics
  and rendering, `«Entity»` for compatibility, since `kuml-gen-sql`'s stereotype detection
  matches literal names) so the PSM produces correct DDL through the existing SQL generator
  without any changes to it.

- **Flyway baseline generator** — `FlywayBaselineGenerator` (id `sql-flyway-baseline`) in
  `kuml-gen-sql`, a thin wrapper composing the existing `SqlDdlGenerator` and writing its output
  as `V<version>__<description>.sql` (Flyway's migration-file convention, default
  `V1__init.sql`) instead of the generic `schema.sql`. Works unchanged against either the raw
  PIM or the new Exposed PSM.

Deferred to a later wave: custom-type hooks for PostGIS geometry and TimescaleDB hypertables.

## [0.25.0] — 2026-07-07

### Added

**`kuml-mcp`: `kuml.examples` tool + granular per-type example resources (V3.3.1)**

LLMs working with kUML through an MCP client had no reliable way to request targeted syntax
examples for a specific diagram type — `kuml://dsl/examples` delivered all 42 bundled Vault
examples as a single blob, and many MCP clients never proactively fetch resources at all.
V3.3.1 addresses both problems with a two-part addition:

- **`kuml.examples` Tool** — a new MCP tool (Tools are proactively invoked by LLMs, unlike
  Resources) that accepts `language` (`uml` | `c4` | `sysml2` | `bpmn` | `blueprint`) and an
  optional `diagramType`, and returns the matching curated example script(s) (raw ` ```kuml `
  blocks) with a one-sentence description and the source note reference. Called with only
  `language`, it instead returns a discovery list of available diagram types for that language —
  supporting a natural "what can I draw → give me an example" flow. Invalid parameters produce
  structured `KUML-MCP-E-EXAMPLES-*` error codes with the list of valid values.

- **Granular per-type Resources** — 34 new Resources at `kuml://dsl/examples/<language>/<diagramType>`,
  all fed from the same `ExampleCatalog`. The existing aggregate Resource `kuml://dsl/examples` is
  unchanged (backward-compatible).

- **`ExampleCatalog`** — a hand-curated mapping of the 42 bundled Vault examples: 38 curated
  (classified by DSL entry point, not by German note title), 4 explicitly excluded (`00 Übersicht`
  + three SMIL animation variants). A completeness test enforces a catalog decision for every
  newly synced example, preventing silent gaps. `journey` is a `diagramType` under `blueprint`
  (both build `BlueprintModel`), not a separate language.

- **`BundledExamples`** — shared classpath-access layer (file- and jar-protocol) extracted from
  the previously private `ResourceRegistry` listing, with a defense-in-depth guard against
  path-like filenames.

Motivation from the GCR benchmark: Gemini scored 0 % first-shot on C4 and showed 64–69 %
hallucination rates on sequences — classic symptoms of missing syntax anchoring that a single
targeted few-shot example corrects. 76 tests, 0 failures, ktlint clean.

**`kuml-mcp`: granular per-language DSL reference resources (V3.3.2)**

Adds `kuml://dsl/reference/<language>` (`uml` | `sysml2` | `c4` | `bpmn`) as single-page
companions to the existing aggregate `kuml://dsl/reference` resource, mirroring the granular
per-`(language, diagramType)` example resources from V3.3.1. Lets an MCP client fetch just the
DSL reference for one diagram family instead of the full concatenated handbook text. `blueprint`
has no dedicated handbook reference page yet — out of scope for this wave.

## [0.24.6] — 2026-07-05

### Fixed

**`kuml-mcp`: every JSON-RPC response failed strict client-side validation, so the MCP
server never connected from Claude Code**

`McpServer`'s JSON encoder was configured with `encodeDefaults = true` but without
`explicitNulls = false`, so every response serialized *both* `result` and `error` — the
unused member as a literal `null` (e.g. `{"jsonrpc":"2.0","id":1,"result":{...},"error":null}`).
Strict MCP clients (Claude Code among them) validate responses against a JSON-RPC 2.0 union
schema that rejects a `result`/`error` pair appearing together at all, even when one side is
`null` — every otherwise-correct kuml-mcp response failed that validation, and the client
reported the connection itself as broken (not a per-call error) with no indication that the
payload shape was the cause. Fixed by adding `explicitNulls = false` to the encoder so only
the field that's actually set is serialized. Added a regression test
(`McpServerProtocolTest`) asserting neither key appears when unused. Verified with a manual
JSON-RPC handshake (`initialize` / `tools/list` / `tools/call` / an unknown-method error) and
a real `claude mcp list` connection, which now reports `✔ Connected` in ~4.5s instead of
timing out.

## [0.24.5] — 2026-07-05

### Fixed

**Chocolatey: `chocolateyUninstall.ps1` (kuml-desktop) could not be parsed at all**

Found during the first-ever real elevated `choco uninstall kuml-desktop` on Windows: the
script failed immediately with a PowerShell parse error ("The string is missing the
terminator: '.") before running any of its logic. Root cause: the script used a UTF-8
em-dash inside an active `Write-Warning` string, but the file had no UTF-8 BOM. Windows
PowerShell 5.1 reads BOM-less `.ps1` files using the system's ANSI codepage rather than
UTF-8, so those bytes decoded as unexpected characters and broke the tokenizer — since
PowerShell parses an entire script before executing any of it, this broke the whole file
regardless of which branch would actually have run. Fixed by replacing the em-dash with a
plain hyphen and adding a UTF-8 BOM to all four Chocolatey install/uninstall scripts as
defense-in-depth. Verified with `[System.Management.Automation.Language.Parser]::ParseFile`
(0 syntax errors) and a real elevated `choco uninstall kuml-desktop` round-trip.

**Test suite: `PluginScanPath.userPluginDir` had no test seam, silently writing into the
real `~/.kuml/plugins`**

`PluginInstallCommand`, `PluginRemoveCommand`, and `PluginUpgradeCommand` all read/write
`PluginScanPath.userPluginDir` directly, with no way to redirect it in tests (unlike
`KumlHome.base()`, which already supports a test-override system property). On a
development machine where the CLI test suite is rerun repeatedly, successful-upgrade test
cases in `PluginUpgradeCommandTest` copied their in-memory fixture JARs into the real
`~/.kuml/plugins/`, where they accumulated and were later picked up by unrelated test runs
(`PluginCommandTest`, `PluginCheckUpdatesCommandTest`), which then failed with
`ClassNotFoundException` for a class that only ever existed as an in-memory fixture. Added
the same test-seam pattern already used by `KumlHome` (`overrideUserPluginDirForTest` /
`clearTestOverride`, backed by a `kuml.plugins.dir` system property) and wired it into the
three affected test files. Test-only change; no production behaviour affected. Verified
with two consecutive full test suite runs, both green, with the real `~/.kuml/plugins`
left completely untouched.

### Also included in this release (test-only, no shipped-artifact change)

- Clikt's `CliktCommand.test(String)` testing helper tokenizes its argument with an
  escape-aware parser that treats backslash as an escape character, silently corrupting
  any Windows path passed as a single interpolated string (e.g.
  `C:\Users\...\kuml-trace-....json` arrived as `C:Users...kuml-trace-....json`). This is
  a test-only bug — production invocations are never affected, since the OS already splits
  the command line before the process starts. Converted 26 affected test files across
  `kuml-cli` to Clikt's `test(List<String>)` overload, which performs no re-tokenization.
  `:kuml-cli:test` went from 111 failing / 502 total to 0 failing on real Windows 11. (Not
  merged as a standalone release at the time — landed on `master` alongside v0.24.4's
  packaging fixes and ships in this version.)

## [0.24.4] — 2026-07-05

### Fixed

**Critical: `kuml.bat` could not start on Windows at all — Chocolatey, Homebrew-equivalent
runtime-zip download, every Windows channel affected**

This is the first release verified against a real Windows 11 host end-to-end (previously only
constructed/build-verified — no Windows machine had ever actually run `kuml.bat`). The Gradle
`application` plugin's generated Windows launcher lists the CLI's entire classpath (~300 jars —
AI providers, Compose Multiplatform, the AWS Bedrock SDK, the Kotlin compiler-embeddable, ELK,
Batik, Ktor, ...) on a single `set CLASSPATH=...` line, roughly 14.9 KB long. `cmd.exe` refuses to
read or execute any single line longer than ~8191 characters ("Die eingegebene Zeile ist zu
lang." / "The input line is too long."), so `kuml.bat` failed on every invocation before ever
reaching `java.exe` — on every Windows install of every version to date.

Splitting the assignment into many short `set CLASSPATH=%CLASSPATH%;...` appends does **not** fix
this: cmd.exe's ~8191-char ceiling also applies to the fully-expanded command it ultimately
executes, not just to literal source lines, so the launcher's closing invocation line hits the
identical limit once `%CLASSPATH%` is substituted at runtime.

Fixed with a "pathing jar" — the standard technique used by `maven-jar-plugin`'s classpath mode,
sbt-native-packager, and other JVM launchers facing the same problem: a stub jar with no class
files, just a `META-INF/MANIFEST.MF` `Class-Path:` attribute listing every real jar as a relative
filename. Per the JAR spec, manifest `Class-Path` entries resolve relative to the *containing
jar*, not the process's working directory, so the launcher now only ever needs
`-classpath kuml-windows-classpath.jar` — a single short path — and the JVM reads the rest
straight out of the manifest with no OS command-line involved, so no length limit applies.
Preserves the exact original jar order (unlike a `lib\*` wildcard, whose expansion order follows
NTFS directory enumeration and isn't guaranteed to match, which matters here because the bundled
Compose Multiplatform dependency tree intentionally carries more than one version of a handful of
jars).

Applied unconditionally to every generated Windows launcher (`kuml.bat` and `kuml-mcp.bat`),
regardless of how short its own classpath line looks: the actual failure depends on the
*expanded* line length, which varies with install path depth, not on jar count — confirmed by
reproducing the identical failure with `kuml-mcp.bat`'s much smaller (~90 jar) classpath at a
sufficiently deep install path.

Verified end-to-end on real Windows 11 without any system JDK on `PATH`/`JAVA_HOME`: `kuml
--version`, `kuml --help`, `kuml ai --help`, a full `kuml render` of
`docs/examples/kuml-getting-started.kuml.kts` (exercises the Kotlin scripting compiler, ELK
layout, and Batik SVG rendering), and a real `choco install kuml` against this release's own
published `kuml-runtime-*-windows-x86_64.zip` asset.

### Verified (no code change)

- **Windows Job Object sandbox** (`kuml-mcp` script execution cage, added in v0.23.3): closed the
  long-standing "constructed but never run on real Windows" gap. 8 new behavioural tests
  (`OsSandboxTest`) drive `WindowsJobObjectSandbox`/`OsSandbox.applyPostStart` against real running
  processes on a real Windows kernel — Job Object creation, `KILL_ON_JOB_CLOSE`,
  `JOB_OBJECT_LIMIT_PROCESS_MEMORY`, `JOB_OBJECT_LIMIT_ACTIVE_PROCESS=1` child-spawn blocking, and
  fail-closed/best-effort degradation. All 29 `OsSandboxTest` and all 22
  `SandboxSecurityAcceptanceTest` cases pass.
- **`kuml-desktop` Windows MSI** (`:kuml-desktop:packageMsi`): builds successfully on a real
  Windows host for the first time; structure validated via administrative MSI extraction (bundled
  JRE, correctly resolved classpath via jpackage's own per-jar `.cfg` mechanism, which is
  unaffected by the `cmd.exe` line-length bug above since it isn't a batch script).

### Changed

- A previously silent Windows sandbox degradation path (best-effort mode failing to install the
  Job Object cage) now logs a warning instead of failing silently, mirroring the existing Linux
  bwrap-missing warning.
- `.gitignore`: exclude `hs_err_pid*.log` — the Job Object memory-cap test intentionally drives a
  child JVM to a native OOM crash instead of a clean exit as part of verifying the cap works.

## [0.24.3] — 2026-07-05

### Fixed

**Actual root cause of the `libjli.dylib` ad-hoc-signing bug found and fixed: a missing `clean`**

v0.24.0, v0.24.1, and v0.24.2 all shipped believing signing was fixed; none of them actually were.
Root-caused via an isolated, temporary debug workflow (manual-dispatch-only, touched no release
channel) run directly against a real GitHub Actions macOS runner: `release.yml`'s "Build bundled
jlink runtime zip" step ran `./gradlew :kuml-mcp:installDist :kuml-cli:runtimeZip` without `clean`
first — the only signing-critical `./gradlew` invocation in the whole workflow that omitted it, in
violation of this project's own standing convention (always `clean` before any gradlew invocation,
adopted after past incidents of incremental-build/configuration-cache staleness). The sibling
`kuml-desktop` DMG build already ran with `clean` and was signed + notarized correctly in every
prior release attempt; the runtime-zip step, missing it, shipped ad-hoc every time. Multiple rounds
of live debug-workflow experimentation (mirroring `release.yml`'s exact two-invocation build+notarize
pattern, inspecting the signature of `libjli.dylib` both as a loose file and as packed inside the
actual zip) consistently signed correctly whenever `clean` was present and never reproduced the bug
under that condition. Added `clean` to the step; the debug workflow used to find this has been
deleted.

## [0.24.2] — 2026-07-05

### Still broken — see below

**v0.24.1's fix for the `libjli.dylib` ad-hoc-signing bug did not actually work.** The real
release build again signed only 43 of 44 Mach-O files and shipped the identical broken artifact —
confirmed by upgrading via Homebrew and hitting the same `dyld: ... different Team IDs` crash
again. This release does not claim to have fixed it; it adds real diagnostics (no more silently
swallowed read errors in the Mach-O detector, a hard build-time gate that locates
`libjli.dylib`/`libjvm.dylib` by filename and fails loudly with a full directory listing if either
is missing) so the next attempt is based on actual CI data instead of another guess. If you
installed v0.24.0 or v0.24.1 via Homebrew, `kuml`/`kuml-mcp` will not run — do not upgrade to
v0.24.2 expecting a fix; wait for the version that follows it.

## [0.24.1] — 2026-07-05

### Fixed

**Critical: Homebrew `kuml`/`kuml-mcp` failed to launch — `libjli.dylib` shipped ad-hoc-signed
despite v0.24.0's signing work**

The v0.24.0 release's `signBundledRuntime` task detected Mach-O files by shelling out to the
`file` command and pattern-matching its text output. On the actual GitHub Actions macOS release
runner, this signed only 43 of the 44 Mach-O files a local build signs — `runtime/lib/libjli.dylib`
(the exact file the original AMFI bug report named) was never recognized as Mach-O in that
environment, so it was never signed at all and shipped with its original ad-hoc signature. Every
real `brew install kuml` / `brew install --cask kuml-desktop` user hit a hard crash on launch:

```
dyld: Library not loaded: @rpath/libjli.dylib
code signature ... not valid for use in process: mapping process and mapped file
(non-platform) have different Team IDs
```

Fixed by replacing the `file`-command text match with direct magic-byte detection (reads each
file's first 4 bytes, compares against the six known Mach-O/fat-binary magic numbers) — no
external process, no version/environment drift possible. Also added a post-signing verification
gate: `signBundledRuntime` now independently re-scans the entire runtime image after signing and
fails the build if any Mach-O file — not just the ones the task believed it signed — still shows an
ad-hoc signature or missing Team ID. This closes the exact blind spot that let v0.24.0 ship broken:
a file invisible to the signing loop was previously invisible to verification too.

## [0.24.0] — 2026-07-04

### Added

**MCP script-evaluation sandbox — a nine-wave defence-in-depth overhaul**

Prior to this release the `kuml-mcp` server evaluated untrusted scripts (from `kuml.render`/
`validate`/`list_elements`/`describe`/`generate` and the `kuml.run.*` runtime tools) in-process,
in the server's own JVM, with `wholeClasspath = true` and no isolation. The only barrier was the
`KumlScriptGuard` static denylist — and even that had a hole. This release rebuilds the evaluation
path as a layered sandbox. The layers are honest about what each does and does not stop; a
consolidated `SECURITY-COVERAGE.md` matrix (attack vector × layer) ships alongside the code, and a
`SandboxSecurityAcceptanceTest` walks the full threat model (filesystem exfiltration, network
pivot, persistence, DoS, denylist bypass, process-start) end-to-end through the real evaluator.

* **`KumlScriptGuard` denylist hardening (layer 1).** Closed reflection-based bypasses: the prior
  list blocked `Class.forName`/`ProcessBuilder`/`Runtime.getRuntime` literally, but a payload
  routing through `getMethod("getRuntime").invoke(null)` or `getConstructor().newInstance()` passed
  cleanly and reached arbitrary code execution. Added patterns for `getMethod`/`getMethods`,
  `getField`, `getConstructor`/`getDeclaredConstructor`, `newInstance`, reflective `.invoke(`,
  `loadClass`, `MethodHandles`, `KCallable`, `::class.java` and `isAccessible`; added missing
  file-I/O primitives, `System.getProperties()`, `kotlin.system.exitProcess`, thread constructors,
  and a 256 KiB script-length cap before the regex scan. This remains a denylist, **not** a
  sandbox — which is why the following waves exist.

* **Child-process isolation + warm worker pool (layers 2–3).** A new `ScriptEvaluator` abstraction
  runs each untrusted script in a fresh, short-lived child JVM communicating over a minimal
  newline-delimited JSON IPC protocol, with a wall-clock timeout (default 15 s →
  `destroyForcibly()`), an `-Xmx` cap (default 256m) so a child OOM never touches the server heap,
  a fixed argv (no shell → no command injection), a cleared child environment (only `PATH`+`TMPDIR`
  restored, so server secrets are not inherited), and bounded stdout reads. The cold-start cost
  (~1.6 s/call measured on macOS) is then amortised by a `WorkerPool` of pre-started, use-once
  workers, cutting per-call overhead to roughly ~280 ms. The evaluator is **fail-closed**: an
  IPC/launch failure returns a `SANDBOX` error rather than silently re-running the script in-process
  (`KUML_MCP_SANDBOX_MODE`, default child-process; operators who cannot spawn child JVMs opt out
  explicitly with `KUML_MCP_SANDBOX_MODE=in-process`). Its value over the denylist alone is proven
  by a `while(true){}` test — not caught by any denylist — that the timeout kills while the parent
  stays responsive.

* **OS-native cages around the worker (layers 4–6, `KUML_MCP_SANDBOX_OS_ISOLATION`).**
  * **macOS** (`OsSandbox` + a bundled `kuml-worker.macos.sb` seatbelt profile via `sandbox-exec`):
    deny-by-default — network denied, file-writes confined to a per-worker temp dir, reads of
    `~/.ssh`/`~/.aws`/`~/.gnupg`/`~/.config/gcloud`/Keychains explicitly denied. Default `required`
    on macOS (launch fails closed if the cage cannot be applied). Behavioural escape tests
    (denylist-independent raw Java file-write to `$HOME`, raw-IP + DNS connect, `~/.ssh` read)
    confirm each is allowed with no sandbox and blocked by the kernel under the profile.
  * **Linux** (`bwrap`/bubblewrap): mirrors the macOS posture with `--unshare-all`, a per-worker
    read-write bind shadowing a broad root ro-bind, and no network. This wave was first written on
    a macOS machine and shipped construction-verified only; it has since been **verified on real
    Ubuntu 26.04 / kernel 7.0 hardware** using the same raw-escape methodology. That verification
    uncovered and fixed a real gap: the broad `--ro-bind / /` posture (needed for the embedded
    Kotlin compiler's scattered reads) left `~/.ssh`/`~/.aws`/`~/.gnupg`/`~/.config/gcloud`
    **readable** — the macOS profile had an explicit read-deny for these, the Linux command did not.
    Fixed by shadowing each existing secret directory with a private empty `--tmpfs` after the root
    ro-bind. Linux stays **best-effort** (not `required`): `bwrap` availability is inconsistent
    across distros/containers, and the container / no-unprivileged-userns degradation path is not
    yet verified.
  * **Windows** (`WindowsJobObjectSandbox`, JNA-bound Job Object: memory cap, active-process=1
    anti-fork-bomb, kill-on-close). **This path has never run on real Windows** — it was implemented
    on macOS with no ability to make a single Win32 call. It is default best-effort and honestly
    documents its gaps: Job Objects have **no network egress control** (not faked with UI-restriction
    flags), filesystem confinement is process/memory/kill-on-close only (no bind-mount equivalent),
    and there is a theoretical post-start race before `AssignProcessToJobObject`. Needs
    `windows-latest` CI before its default can move to `required`.

* **Restricted ClassLoader + curated classpath inside the worker (layer 7,
  `KUML_MCP_SANDBOX_CLASSLOADER`).** A second in-JVM defence behind the OS cage, fully testable on
  macOS. `SandboxClasspath` replaces `wholeClasspath = true` with a narrow jar set (`kuml-*`,
  kotlin-stdlib/-reflect, kotlinx-serialization/-io/atomicfu), so a script naming JNA or the Kotlin
  compiler fails with an unresolved-reference **compile** error before its body runs; an
  `AllowlistClassLoader` (real default-deny) sits behind it as belt-and-braces. Documented limit:
  JDK boot-layer classes (`java.*`, most `jdk.*`) are **not** filterable by a user classloader —
  neutralising their effects stays the OS cage's job. The trusted in-process CLI path is unchanged.

* **Experimental Data-DSL interpreter (layer 9, `KUML_MCP_SANDBOX_EVAL_STRATEGY=interpreter`).**
  An opt-in strategy that **interprets** the kUML DSL (`DslLexer` → `DslParser` → `DslInterpreter`
  driving the real DSL builders) instead of compiling it through the embedded Kotlin compiler, so
  RCE is structurally impossible — there is no grammar production for reflection/process/IO.
  **Deliberately partial: UML class diagrams only** (a fixed builder vocabulary and a small language
  subset — `val` bindings, positional/named args, trailing lambdas, string/int/bool literals, enum
  refs; no loops, conditionals, arithmetic, method chains, or string interpolation). All other
  diagram types are recognised by name and rejected with a "use `--eval-strategy=compiler`" message.
  The production default stays `compiler` (all containment layers active); a test proves the
  interpreter and compiler produce a byte-identical `KumlDiagram` for the supported subset.

**MP4 export for animated diagrams**

`kuml render --animated -f mp4` now encodes SMIL-animated diagrams to H.264/yuv420p MP4 via
`ffmpeg` (new `Mp4Encoder` in `kuml-io-anim`), alongside the existing APNG/WebP outputs. The
alpha-channel limitation of H.264 is documented rather than papered over.

**WASM playground: C4 / SysML 2 / BPMN / Blueprint rendering + real grid layout**

The browser-hosted Kotlin/Wasm playground previously rendered only UML class diagrams. New wasmJs
entry points (`renderC4DiagramJson`, `renderSysml2DiagramJson`, `renderBpmnDiagramJson`,
`renderBlueprintDiagramJson`) now decode and render C4, all 8 SysML 2 diagram kinds, all 4 BPMN
diagram types, and Blueprint diagrams entirely client-side. `kuml-layout-grid` was converted from a
JVM-only module to multiplatform, so `renderDiagramJsonWithGrid` computes a real multi-column,
content-sized layout instead of the previous single-row demo scaffold (ELK remains JVM-only). `kuml
dump-json` gained `--model-out` and now supports the same metamodels. All wasm entry points enforce
an 8 MiB UTF-8 payload cap before decoding untrusted browser JSON. KerML remains out of scope — no
`KumlSvgRenderer` overload exists for it on any platform, documented rather than faked.

**Chocolatey packaging for Windows**

A self-contained `kuml-mcp` launcher (`bin/kuml-mcp.bat`, patched the same way `kuml.bat` already
is) is now bundled so `kuml-mcp` runs without a system JDK on Windows and Chocolatey can shim a
`kuml-mcp` command. `kuml-desktop` is additionally published as its **own** Chocolatey package (own
package ID, MSI-based install via a new `packageMsi`/`publish-chocolatey-desktop` release job) —
the Windows counterpart to the macOS DMG/Cask channel. Neither has been exercised by a real `choco
install` yet — the MSI/WiX backend is Windows-only and both need Windows CI verification before
they can be trusted end-to-end.

**Handbook: live-rendered C4 / BPMN / SysML 2 diagrams + theme gallery**

The Antora pre-render bridge (`AsciidocRenderPipeline`) now renders C4, all 8 SysML 2 diagram
types, and all 4 BPMN diagram types inline — previously only UML and Blueprint blocks rendered and
the rest threw a "not yet supported" error. Dispatch mirrors the CLI `RenderPipeline` (same ELK
bridges, same per-kind `toSvg` overloads). A per-block `theme="..."` AsciiDoc attribute was added,
used by a new "Theme gallery" in `themes.adoc` that renders the same diagram once per built-in theme
(plain/kuml/elegant/playful). The `c4-dsl`/`bpmn-dsl`/`sysml2` pages now carry full runnable
`[source,kuml]` blocks sourced from the vault examples in place of Kotlin-only fragments, and
`uml-dsl.adoc` also documents all 8 previously-undocumented UML types (Package, Deployment,
Activity, Communication, Profile, Timing, Interaction Overview, Composite Structure).

**Apple Developer ID signing + notarization for the macOS artifacts (V3.2.25–27)**

The `kuml-desktop` DMG and the jlink-bundled JRE runtime (`kuml`/`kuml-mcp` CLI) are now signed
with a Developer ID identity under the hardened runtime and notarized (the DMG is additionally
stapled; the bare runtime zip cannot be stapled and correctly falls back to an online Gatekeeper
check). This fixes a real, previously-documented startup failure: `kuml-mcp` (and `kuml`) refused
to launch as a child of a hardened parent process (e.g. Claude Desktop) because macOS AMFI /
Library Validation rejected the bundled jlink JRE's ad-hoc-signed native libraries. A new
`signBundledRuntime` Gradle task signs every Mach-O under the runtime image bottom-up — including
native libraries **nested inside dependency jars** (JNA, sqlite-jdbc, Jansi), which notarytool
requires and which was discovered during local end-to-end verification. The hardened-runtime
entitlements are the minimum a HotSpot-JIT'd JVM needs (`allow-jit` +
`allow-unsigned-executable-memory`); a broader `disable-library-validation` entitlement was tried
and dropped after security review found it added attack surface without capability, since every
native lib is now signed with the same identity. All signing is gated on `KUML_SIGN_IDENTITY`, so
unsigned local/CI builds are unaffected. The pipeline (`release.yml`) now builds an ephemeral
signing keychain from repo secrets on both macOS runtime legs and the desktop-DMG job, with an
`apple_gate` step that makes every signing step no-op cleanly when the secrets are absent.

Honesty note: this was all verified **locally** against a real Developer ID certificate and App
Store Connect API key (signed DMG passes `spctl` + `stapler validate`; the runtime zip notarizes;
`kuml --version`, a real `classDiagram` render, and the `kuml-mcp` JSON-RPC handshake all succeed
post-signing). No real GitHub Actions run has exercised the CI wiring — that needs an actual tag
push. If the pipeline behaves as intended, **v0.24.0 should be the first release whose macOS
artifacts actually ship signed + notarized**, but that has not been proven against a real CI run.

### Changed

**Build: Gradle 10 preparation, dependency bumps, warning cleanup**

Bumped JNA (5.16.0 → 5.19.1), Compose Multiplatform (1.9.0 → 1.11.1, with material3 decoupled to
its own ref), graalvm-native (1.1.1 → 1.1.3), vanniktech-publish (0.36.0 → 0.37.0), and eclipse-emf
(2.36.0 → 2.40.0); removed a dead assertj catalog entry. Migrated the deprecated Compose plugin-DSL
string accessors to catalog refs and `TabRow` → `PrimaryTabRow`. The Problems report is now empty
with zero `@Suppress`/`-nowarn`/level downgrades — `@OptIn(ExperimentalWasmDsl)` added to the wasmJs
build scripts, redundant null checks / casts / `else` branches removed, and per-call `Json { … }`
instances hoisted into shared ones. The single remaining Gradle-10 "Project object as dependency
notation" warning originates inside the third-party GraalVM native-build-tools plugin (fixed
upstream, not yet released past 1.1.3), not in any kUML-authored build script.

**Docs: stale README facts corrected across many files**

Bumped stale `v0.20.5` version pins (Docker image, native installers, Maven Central coordinate) to
current across `README.adoc`; replaced a placeholder `0.1.0` Maven Central version — never actually
published — in ten module READMEs with the real released version; added the `kuml-wasm-playground`
module entry and marked `kuml-layout-grid` multiplatform; corrected the `kuml-packaging` entry
(jpackage installers, not GraalVM Native Image); rewrote `kuml-cli/README.adoc`'s Subcommands and
Options sections (17 missing subcommands added, `--theme` default corrected to `kuml`, and the
`--layout`/`--animated`/`--trace`/`--speed`/`--config` options plus latex/apng/webp/mp4 formats
documented); and updated the `kuml-ai-*` and `kuml-metamodel-bpmn` READMEs that still described
already-shipped features as deferred. Added `FUNDING.yml` with GitHub and PayPal links.

### Fixed

**MP4/WebP encoder: locale-dependent framerate formatting**

`"%.3f".format(fps)` used Kotlin's default-locale `String.format`, so on a non-US system (e.g.
`de_DE`) it emitted a decimal comma (`5,000`) for ffmpeg's `-framerate`, which ffmpeg's parser
rejects. Both `Mp4Encoder` and `WebpEncoder` now use `String.format(Locale.ROOT, "%.3f", fps)`,
matching the fix `SmilTimelineFrameSampler` already applies for this exact bug class. Found during a
real end-to-end MP4 verification on a German-locale machine — invisible on the English-locale
machines the feature was originally built on.

**MP4 encoder: odd frame dimensions rejected by libx264**

H.264/yuv420p requires even width and height, but kUML renders have layout-driven, frequently-odd
dimensions (a real STM render came out at 1024×3459), which made libx264 refuse with "height not
divisible by 2". `Mp4Encoder` now appends `scale=trunc(iw/2)*2:trunc(ih/2)*2` to the `-vf` chain
(the standard fix), cropping at most 1 px. WebP has no such constraint, so this is an MP4-only fix.
A new regression case covers odd (5×7) frame dimensions.

**Handbook: `--animated` CLI syntax in the SMIL Animation page**

Every example showed `--animated <trace-file>` as a single valued option, but the CLI has
`--animated` as a boolean flag plus a separate `--trace <file>` option — the documented form failed
with "got unexpected extra argument". Fixed all six examples to `--animated --trace <file>`
(confirmed while verifying MP4 export end-to-end).

### Security

**`KumlScriptGuard` bypass in the `kuml.run.*` MCP tools — guard was never invoked on the runtime
script path**

The five authoring tools ran their scripts through `KumlScriptGuard`, but the `kuml.run.*` runtime
tools evaluated scripts through `RuntimeSessionManager` on a path that **never called the guard at
all** — the denylist that was the sole barrier for authoring tools did not apply to the runtime
session path. All MCP script paths now route through a single `McpScriptEvaluator`; no direct
`KumlScriptHost.eval` call remains in `kuml-mcp` production code. This closed the immediate hole; the
sandbox overhaul in **Added** replaces the "denylist is the only barrier" posture entirely.

**Additional defence-in-depth from the full-project security audit**

EMF XMI import (`XmiReader`, `ProfileXmiImporter`) used EMF's eager `getResource(uri, true)` overload
whose default SAX parser resolves external entities and processes DOCTYPE — an XXE / billion-laughs
vector on attacker-supplied `.xmi`/`.uml` files. A shared `EmfXmlSecurity.secureLoadOptions()`
(disallow-doctype-decl, external entities off, external-DTD off) now hardens those load paths,
mirroring the existing ARXML/BPMN importer hardening. Separately, the last-resort plaintext API-key
store (`PlainJsonFallbackBackend`) now best-effort restricts its file to POSIX `0600` so other local
users cannot read stored keys (no-op on non-POSIX filesystems).

## [0.23.2] — 2026-07-03

### Fixed

**UML edge labels: halo rendering for association role names, multiplicity and dependency labels**

Labels on UML class-diagram edges — association role names (`role = "orders"`, `role = "items"`),
multiplicity labels (`0..*`, `1..*`) and dependency/connector names (`"notifies"`, `"«include»"`,
`"«extend»"`) — were emitted as a single `<text>` element without the two-pass halo that C4 and
BPMN edges already use. When the edge polyline crossed or ran directly through the label glyph
run, the stroke was visible through the text, making it hard to read — particularly in the
Order-Domain sample (`01 UML Klasse – Order Domain`) reported visually in the Obsidian plugin.

Fix: three changes in `UmlEdgesSvg.kt` and one CSS addition in `SvgDocument.kt`:

* `renderEdgeLabel` (mid-edge labels) now delegates to `renderEdgeLabelWithHalo`, emitting the
  `kuml-edge-label-halo` background pass before the visible `kuml-edge-label` text — identical to
  what C4 interaction edges and BPMN message flows already do.
* `endpointLabel` (source/target-end multiplicity and role names) now emits a `kuml-small-halo`
  background element before the `kuml-small` label text. A matching CSS class is added to
  `SvgDocument.buildDefs` with the same stroke technique as `kuml-edge-label-halo`.
* `renderUmlLink` source/target role labels are refactored to use `endpointLabel` (removing the
  inline duplication), picking up the halo for free.

The fix is Batik-safe (no `paint-order` reliance): the halo is always a separate preceding sibling
`<text>` element in z-order, identical to the Graphviz `xlabel` technique used elsewhere in kUML.

## [0.23.1] — 2026-07-03

### Added

**UML Comment / Note element**

`kuml-core-ocl`'s sibling metamodel module `kuml-metamodel-uml` gains `UmlComment` — the classic
UML note: a free-text annotation box with a folded top-right corner, optionally attached to zero
or more model elements by a dashed line (`UmlCommentLink`, modelled as a first-class
`UmlRelationship`). Available via `comment(text = ..., firstAnchor = ..., ...)` on class, sequence,
and state-machine diagram bodies — other diagram types (BPMN, SysML 2, C4, component, use case,
activity, object, deployment, package, profile, composite structure, communication, timing,
interaction-overview) do not have a `comment()` DSL function yet, a documented scope limitation
rather than an oversight. Comment body text is always emitted through the existing XML-escaping
`SvgBuilder.text()` path — pinned by dedicated XSS regression tests — since SVG output is
frequently embedded in browsers (kuml.dev playground, Obsidian previews, handbook pages).

### Fixed

**Website: v0.23.0 What's New entry and stale playground examples**

The kuml.dev release choreography step was missed for v0.23.0 — the site still showed 0.22.0 as
latest and the Playground's rendered SVGs were stale (the CI deploy runs a plain `npm run build`,
which does not re-render Playground SVGs; that step is a local, pre-commit convention). Added the
missing What's New entry (EN+DE) and re-rendered all Playground SVGs.

## [0.23.0] — 2026-07-02

### Added

**OCL: full 2.4/2.5 expression language (was: basic-constraints subset)**

`kuml-core-ocl` now implements the complete OCL iterator/expression surface instead of a small
subset: collection operations `select`/`reject`/`collect`/`iterate`/`any`/`one`/`sortedBy`/
`isUnique`/`sum`/`count`/`including`/`excluding`/`union`/`intersection`/`first`/`last`/`asSet`/
`asSequence`, `let`/`in` and `if`/`then`/`else`/`endif` expressions, and `Real` arithmetic with
correct Int/Real promotion. Association-end navigation (including opposite ends and multi-step
chains) replaces the previous hardcoded per-class property table with a metamodel-driven
accessor — no reflection, so it stays Native-Image/KMP-safe. `closure()` walks the association
graph cycle-safely. Adds type operations `oclIsTypeOf`/`oclIsKindOf`/`oclAsType`/
`oclIsUndefined`/`oclIsInvalid` against the classifier + generalization hierarchy, plus
`def:`/`pre:`/`post:`/`body:` constraint-kind stereotypes (with `result` and `@pre` snapshot
support for postconditions) and matching `invariant()`/`definition()`/`precondition()`/
`postcondition()`/`body()` DSL builders. OCL constraint validation now also covers **BPMN** and
**SysML 2** models (previously UML-only), and `KumlViolation` carries a real source position
(line/column) tracked through the lexer/parser. `kuml validate --output json` (alias
`--format`) includes the new BPMN/SysML2 sections and source positions. A new conformance test
suite exercises OMG OCL §7.4–7.6 examples plus a completed String/Integer/Real standard-library
surface (`substring`, `indexOf`, `abs`, `floor`, `round`, `max`, `min`, `mod`, `div`, …), with an
honest coverage matrix documenting what remains deliberately unsupported (e.g. `Tuple` types,
`oclType()` reflection, collection literals).

**MCP Resources — DSL knowledge as a Lehrkanal, not just a feedback loop**

`kuml-mcp` now advertises `capabilities.resources` and serves three resources so MCP clients
(Claude Desktop, Cursor, …) can load kUML's DSL knowledge automatically instead of only
learning it through render/compile-error iteration: `kuml://dsl/reference` (bundled handbook
DSL reference pages), `kuml://dsl/examples` (curated vault example scripts), and
`kuml://dsl/schema` (machine-readable builder-signature schema). `resources/list` and
`resources/read` are wired analogously to the existing `tools/list`/`tools/call` handlers.

**Distribution: `brew install kuml` now ships the MCP server, plus a Desktop Cask**

The standalone `kuml-mcp` binary is bundled into the shared `runtimeZip` artifact (own `mcp/`
subtree to avoid jar-dedup collisions with the CLI's `lib/`), so Homebrew/SDKMan! installs get a
working MCP server out of the box. The Homebrew formula symlinks `bin/kuml-mcp` with a
JSON-RPC smoke test. `kuml-desktop` gets its own Homebrew Cask (`brew install --cask
kuml-desktop`); this required adding a first-ever `kuml-desktop` DMG build+upload job to the
release workflow, since no such job existed before.

**Handbook: live-rendered `[kuml]` diagrams via a new `kuml asciidoc` bridge**

Antora runs on Asciidoctor.js, which cannot load the JVM-based `kuml-asciidoc` Asciidoctor
extension directly. Instead of skipping the integration, a new `kuml asciidoc` CLI subcommand
pre-renders `[source,kuml]` blocks to SVG before the Antora build consumes the tree
(`scripts/build-handbook.sh`), so handbook pages embed real, live-rendered example diagrams
(DSL source and image stay in the same file) rather than static screenshots. Also fixes a stale
User-Journey/Blueprint DSL example in the handbook that referenced a long-removed builder API.

**Vault examples: element-depth completeness audit**

Extended 8 existing vault example notes to cover previously-missing DSL elements/parameters
across UML, BPMN, and C4 (rather than adding new fragmented examples), with a gap matrix
documenting what was closed and what was deliberately left out. Kept in sync with the kUML repo
classpath-test-resources and the kuml.dev playground per the existing sync convention.

**Animated Diagrams section in the README**

Recovers a previously-drafted-but-never-committed showcase of the three SMIL animation examples
(UML Sequence message-dots, State Machine highlighting, BPMN token flow) directly in the
project README.

### Fixed

**Maven Central skip-guard now checks all three KMP legs, not just `-jvm`**

The release workflow's idempotency probe (used to skip `publishToMavenCentral` on a workflow
re-run) previously checked only the `-jvm` leg of a representative module. A partial publish
failure (e.g. `-jvm`/`-js` succeed but `-wasm-js` is rejected by Central's async validator)
would have made a retry falsely report "already published" and skip the entire publish step,
permanently orphaning the missing legs for that immutable version. The guard now requires all
three legs (`jvm`/`js`/`wasm-js`) to be present before skipping.

**MCP documentation corrections (D-1/D-2/D-3/D-5/D-7 from the 2026-07-01 audit)**

`README.adoc` no longer references a nonexistent `kuml.diagram.*` tool prefix (the real 5
authoring tools are `kuml.render`/`validate`/`list_elements`/`describe`/`generate`). The
Behaviour-Runtime-MCP handbook page's parameter names now match the actual tool schemas
(`sessionId`, `source`, `element`, `event`, `variables`/`forceState`, the real `run.stop`
response shape). The nonexistent `kuml ai mcp-stdio` command is replaced with the real
`kuml-mcp` binary launch path. The website's MCP feature card and a new
`authoring-mcp.adoc` handbook page now correctly describe all 10 tools (5 authoring + 5
runtime).

## [0.22.0] — 2026-07-01

### Added

**Animated APNG / WebP export (V3.2 addendum)**

New module `kuml-io-anim`. `kuml render --animated -f apng` / `-f webp` renders SMIL-animated
diagrams to a real animated raster file — no browser required. Frame extraction defaults to a
Batik clock-freeze pipeline (`BatikFrameSampler`, painting the GVT tree directly) with a
dependency-free `SmilTimelineFrameSampler` fallback (string-based SVG mutation, avoiding a JAXP
namespace-reprefixing bug that silently produced transparent frames, plus a `Locale.ROOT` fix for
German-locale float formatting, plus correct SMIL "sandwich" resolution so only the
latest-started animation among overlapping cycles wins). APNG encoding is a from-scratch
`acTL`/`fcTL`/`fdAT` assembler; WebP goes through `ffmpeg`. DoS guards: 500 frames / 30 s / 50 MB
via `FrameBudget` + `SizeLimitedByteArrayOutputStream`. Wired into the CLI (`-f apng`/`-f webp`,
requires `--animated`) and the JetBrains export dropdown (WebP greyed out when `ffmpeg` is
missing).

**BPMN Choreography — dedicated grid layout and constraint checks**

Choreography diagrams no longer reuse the generic ELK-based BPMN layout. A new
`ChoreographyGridLayout` lays out participant bands on a purpose-built grid (topological
ranking, per-column lane assignment, loop back-edge routing below all lanes), wired into the
CLI, desktop, Gradle, and web render pipelines. Adds `BpmnConstraintChecker` rules for
choreography diagrams (dangling flow refs, unreachable elements, gateway condition mismatches,
participant-band continuity). Fixes a layout bug where message-envelope glyphs on adjacent
choreography tasks could visually collide with neighboring lanes — vertical reserve is now
scoped per originating column instead of applied globally.

**Core and renderer modules migrated to Kotlin Multiplatform — jvm/js/wasmJs**

`kuml-core-model`, `kuml-metamodel-uml`, `kuml-metamodel-c4`, `kuml-profile-api`,
`kuml-core-dsl`, `kuml-core-expr`, `kuml-metamodel-kerml`, `kuml-metamodel-sysml2`,
`kuml-metamodel-bpmn`, `kuml-metamodel-blueprint`, `kuml-layout-api`, `kuml-themes-core`,
`kuml-layout-bridge`, and `kuml-io-svg` are now Kotlin Multiplatform modules targeting `jvm`,
`js`, and `wasmJs`, using `expect`/`actual` declarations (`ServiceLoader`, hex/number
formatting) and `kotlinx-atomicfu` in place of JVM-only concurrency primitives. `kuml-render-smil`
and `kuml-runtime-core` remain JVM-only, wired as `jvmMain`-only dependencies where still needed.
Maven Central publishing was extended to multi-target coordinates (`-jvm`/`-js`/`-wasm-js`) for
the migrated modules via `vanniktech`'s `KotlinMultiplatform` publication, alongside the existing
`KotlinJvm` publication for JVM-only modules (publishing config only — no production Maven
Central publish has been run yet for the new coordinates).

**Client-side WASM rendering in the kuml.dev Playground**

`KumlDiagram` (and the UML element hierarchy) is now `kotlinx.serialization`-serializable. A new
`kuml-wasm-playground` module exposes `renderDiagramJson(diagramJson, layoutJson): String`,
decoding a real `KumlDiagram` + `LayoutResult` and rendering SVG entirely in Kotlin/Wasm — no JVM,
no CLI. A new `kuml dump-json` CLI command dumps a UML script's parsed diagram + computed layout
as JSON in the exact shape the wasm entry point expects. Scope is UML-only for now (C4, BPMN,
SysML2, KerML, and Blueprint element types are not yet registered in the serializers module).
The kuml.dev Playground gained a "Render via WASM (experimental)" toggle on the UML class-diagram
example that swaps the pre-rendered SVG for a live client-side WASM render, and
`render-playground.mjs` gained a `KUML_PLAYGROUND_ENGINE=wasm` build-time mode (only active when
the configured theme is `plain`, since the wasm renderer does not yet take a theme parameter).

## [0.21.0] — 2026-06-30

### Added

**BPMN 2.0 Choreography diagrams — metamodel, DSL, and SVG renderer (V3.2.1–V3.2.2)**

New BPMN Choreography notation: `ChoreographyTask`, `ChoreographyGateway`, and `ChoreographyEvent`
nodes connected by `MessageFlow` and `ChoreographySequenceFlow`. The SVG renderer draws
ChoreographyTasks with the characteristic double border and participant bands (initiator pinned to
the top band, responder to the bottom band) per the BPMN 2.0 spec.

**BPMN 2.0 Conversation diagrams — metamodel, DSL, and SVG renderer (V3.2.3)**

New BPMN Conversation notation: `ConversationNode`, `ConversationLink`, and `CallConversation`,
rendered with hexagon shapes for the conversation nodes.

**BPMN Choreography + Conversation wired into the CLI with vault examples and tests (V3.2.4)**

Both new BPMN sub-notations are now reachable through the CLI render pipeline, backed by new vault
examples (`36 BPMN Conversation – PdV Kommunikation`, `37 BPMN Choreography – Bestellprozess`) and
render smoke tests.

**UML Sequence Diagram animation — animated message dots (SMIL)**

`kuml render --animated` now animates UML sequence diagrams: messages travel as `animateMotion`
dots along the message arrows. Built on a new `TraceEntry` model (`MessageSent` / `MessageReceived`
sealed subclasses), `SequenceMessageTimelineBuilder`, `SequenceSmilRenderer`,
`AnimatedSequenceRenderResult`, and `SequenceAnimationContext`. Includes a DoS-guard and injection
tests. New vault example `19 UML Sequence animiert – API Submit`.

**JetBrains plugin — theme selection and SVG/PNG/TeX export**

The JetBrains preview toolbar gains a theme combobox (`kuml`, `plain`, `elegant`, `playful`),
persisted via `PropertiesComponent` (key `dev.kuml.jetbrains.theme`, default `kuml`), and an export
dropdown (SVG / PNG / TeX) that auto-saves the result via a background CLI invocation with balloon
notifications (new `kUML Export` `NotificationGroup` in `plugin.xml`). Covered by
`KumlPreviewPanelThemeTest` and `KumlExportActionTest`.

### Fixed

**fix(cli): wire `SequenceSmilRenderer` into the animated render dispatch**

`RenderPipeline`'s animated dispatch now routes UML sequence diagrams to `SequenceSmilRenderer`;
previously the renderer existed but was never invoked from the CLI path.

**fix(smil): BPMN task pulse animation via overlay-rect**

BPMN task highlighting now uses a dedicated transparent overlay rect (`fill=none`, `stroke-width=0`,
`pointer-events=none`) targeting `-box-pulse` instead of `-box`, fixing browser-inconsistent SMIL
`stroke-width` animation behaviour.

### Changed

**chore: dependency updates, divider refactor, and BPMN example refresh**

Updated build dependencies, refactored divider rendering, replaced outdated `xmlEscapeText` calls,
streamlined Gradle task registration, and refreshed several BPMN vault examples.

## [0.20.5] — 2026-06-29

### Changed

**Default theme switched from `plain` to `kuml` across all render pipelines**

Rendering without an explicit `--theme` flag or `kuml.config.kts` now uses the full-featured
`kuml` house theme (brand colours, blue accents, Inter font) instead of the minimal grayscale
`plain` theme. This affects the CLI (`RenderPipeline`), Web/Server pipeline (`WebRenderPipeline`,
used by the Obsidian plugin's auto mode), the Gradle plugin (`KumlExtension` convention), and the
Desktop app (`AppSettings`, `DesktopRenderPipeline`). The Obsidian plugin's server-mode default
(`KumlServerRenderer.ts`) was aligned to `kuml` as well, so CLI and server render paths now share
the same default. Set `--theme plain` (or `theme.set("plain")`) to restore the old grayscale look.

### Fixed

**fix(bpmn): MessageFlow label overlapped by the dashed edge line**

MessageFlow labels (e.g. "Purchase Order" in the `32 BPMN Collaboration – Customer und Supplier`
vault example) were anchored at the array midpoint of the route — for an L-shaped two-pool flow
that is the bend corner, so the label was crammed 4 px next to the line and its glyphs collided
with the dashed edge. `renderBpmnMessageFlow` now reuses `EdgeLabelGeometry.midAnchor` (the same
path C4/UML edges take): the label is placed at the midpoint of the *longest* polyline segment,
offset perpendicular to it (10 px beside a vertical run, above a horizontal run), and drawn with a
background halo so it stays readable even where it crosses the line.

**fix(bpmn): event and gateway labels covered by ELK-routed edges**

Events and gateways render labels below their shape (`y + shapeH + 12 px`); ELK routed edges from
the node bounds, ending directly in the label area. Layout bounds are now inflated to include label
space (`DEFAULT_EVENT_SIZE` 36×36→36×56, `DEFAULT_GATEWAY_SIZE` 50×50→50×70) so ELK routes edges
safely below the labels. Affected the `31 BPMN Process – Sub-Process Loop` example ("Start Review",
"OK?" now fully readable).

**fix(bpmn): SVG renderer respects the active theme instead of hardcoded colours**

All BPMN node/edge renderers were suppressing the `KumlTheme` parameter and writing hardcoded
`fill="white"`/`stroke="#333"` into the SVG, so BPMN diagrams always rendered black-and-white
regardless of the selected theme — visually inconsistent next to UML diagrams. All BPMN renderers
(`BpmnActivitySvg`, `BpmnEventSvg`, `BpmnGatewaySvg`, `BpmnDataSvg`, `BpmnPoolSvg`,
`BpmnTaskMarkersSvg`, `BpmnSequenceFlowSvg`, `BpmnMessageFlowSvg`, `EdgeRendererDispatcher`) now
read fill/stroke/text/font from `theme.colors` and `theme.typography`.

**fix(c4): boundary label (name + stereotype) now rendered in Component and Container diagrams**

The C4 group rendering loop drew only a plain `<rect>` for Container/System boundaries — the 36 px
top inset reserved by `C4LayoutBridge.C4_BOUNDARY_INSETS` was never filled with text. Component
diagrams now show `[Container: <technology>]` + container name; Container diagrams show
`[Software System]` + system name at the top of the boundary box.

**fix(stm): back-edge labels repositioned to avoid node overlap and border overflow**

In a top-to-bottom UML state machine, ELK routes back-edges (e.g. Yellow→Red in the Traffic Light
STM) as a U-shape around the left side; placing the label at the longest-segment midpoint overlapped
an intermediate state and could overflow the SVG viewBox. Back-edges are now detected in
`renderUmlStateDiagram` (`source.y > target.y`) and their labels anchored at 8 % of the arc length
from the source via `EdgeLabelGeometry.anchorAt(route, 0.08f)` — in the short upward stub that exits
the source state, always inside the diagram frame.

## [0.20.4] — 2026-06-29

### Added

**BPMN Animation v3.1.33 — Variant B highlighting, infinite loop, ID-targeting fixes**

Comprehensive overhaul of BPMN animated rendering: type-specific node highlighting for tasks
(light-blue `#e3f2fd` fill) and events (light-green start / light-red end fill), gateway amber
highlight with automatic reset, infinite looping (`loopCount = LOOP_INFINITE`, practical cap
`LOOP_PRACTICAL_MAX = 200` ≈ 23 min at 1× speed), and corrected SVG ID-targeting so fill animations
actually recolour the shapes (Task-rect and Event-circles now carry unique `-box`/`-circle` IDs;
child `fill="white"` no longer blocks parent-group fill propagation). 307 SMIL animation tests, full
clean check + ktlint green.

**Vault example 35 — AUTOSAR Classic SW-Komponenten**

New AUTOSAR Classic SWC diagram with a staircase grid layout that prevents U-routes through component
boxes and frame clipping. Plus vault-example sync: examples 07/08 (BPMN/STM) gained `kuml-animated`
blocks, example 13 (Composite Structure) an outward delegation connector, example 24 (C4 Component)
completed REST and DB relationships.

## [0.20.3] — 2026-06-29

### Fixed

**fix(bpmn): edge piercing expanded SubProcess frame + event/gateway labels clipped at canvas edge**

Two visual rendering bugs in BPMN Process diagrams, both affecting the `31 BPMN Process – Sub-Process Loop`
vault example (and any BPMN diagram with expanded sub-processes or events/gateways near the canvas boundary).

#### Bug 1 — Edge routed through SubProcess frame interior

Outer `SequenceFlow` edges whose `sourceRef`/`targetRef` was the ID of an expanded `SubProcess` were
routed straight through the interior of the subprocess frame instead of connecting to its border.

Root cause: `BpmnLayoutBridge` emitted a 0×0 "phantom" `LayoutNode` *inside* the ELK compound group
for every expanded SubProcess and wired outer edges to that phantom. ELK then routed the edge to the
interior coordinate rather than the frame boundary.

Fix: phantom node removed entirely. Outer edges now resolve to the compound group boundary via the
existing ELK `groupMap` fallback in `ElkGraphBuilder.resolveEndpoint()` — the same convention already
used for C4 container/system boundaries. `expandedSubProcessGroupIds` are added to `nodeIdSet` so the
outer SequenceFlow edges are still emitted into the layout graph and ELK connects them to the compound
group border.

#### Bug 2 — Event/gateway labels and SubProcess name clipped at canvas edge

Two sub-causes:

1. **Canvas size ignored external label extents.** BPMN event and gateway labels render *below and
   outside* the node bounds (at `y + h + 12 px`). `ResultMapper.computeNormalizedCanvas()` computed
   the viewBox from node/group/edge geometry only, so labels near the bottom or sides were cut off.
   Fix: `renderBpmnProcess` now calls `bpmnLabelMargins()` before rendering to estimate per-side
   overflow, then `applyCanvasMargins()` inflates the `LayoutResult` canvas and shifts all node/group/
   edge geometry accordingly. Result: all event and gateway labels are fully visible with comfortable
   padding on all sides.

2. **Expanded SubProcess name centred vertically (collided with child nodes).** The subprocess frame
   name was drawn at the vertical centre of the frame — the same position occupied by child flow-nodes.
   BPMN convention places the expanded subprocess name at the *top* of the frame.
   Fix: `renderBpmnSubProcess` now branches on `sp.expanded`. If expanded: draw the frame without a
   label, then emit a separate `<text>` element at `y + 16 px`. If collapsed: keep the existing
   vertically centred behaviour.

#### Test alignment

- `VaultExampleRenderer.kt`: switched from `process.flowNodes + process.sequenceFlows + process.dataObjects`
  to `process.renderableElements()`, which recursively flattens expanded SubProcess children into the
  element index — matching the CLI `RenderPipeline.renderBpmn()` path exactly.
- `BpmnLayoutBridgeTest.kt`: updated assertion from `phantomNode shouldNotBe null` to
  `phantomNode shouldBe null`, with explanatory comment describing the group-boundary approach.

## [0.20.2] — 2026-06-28

### Fixed

**fix(bpmn): `kuml render --animated --trace` now works for BPMN process diagrams**

`BpmnSmilRenderer` was fully implemented and tested but never wired into the CLI render
pipeline: `RenderPipeline.renderBpmn()` did not accept `animated`/`traceFile`/`speed`
parameters, and `BpmnSmilRenderer.render()` was never called from the CLI path.

Changes:
- `RenderPipeline.renderBpmn()`: added `animated`, `traceFile`, `speed` parameters.
- Call site updated: `is ExtractedDiagram.Bpmn -> renderBpmn(…, animated, traceFile, speed)`.
- When `animated=true` and a `--trace` file is supplied, `BpmnSmilRenderer.render()` is called
  with the loaded `TraceFile` and a `BpmnAnimationContext` that honours `--speed`.
- When `animated=true` but no `--trace` is given, a clear warning is emitted and the output
  falls back to static SVG (BPMN does not synthesise a demo trace without a trace file, unlike
  STM/Activity diagrams).
- Imports: `BpmnSmilRenderer` and `BpmnAnimationContext` added to `RenderPipeline.kt`.

## [0.20.1] — 2026-06-28

### Fixed

**fix(smil): SMIL animations not playing in Chrome and Safari — xlink:href + xmlns:xlink**

Animated SVGs generated by `kuml render --animated` appeared static in Chrome, Safari and
Obsidian because `SmilEmitter` used the SVG-2 bare `href` attribute on `<animate>`,
`<animateMotion>`, `<animateTransform>` and `<set>` elements. Browser SMIL implementations
are based on SVG 1.1, which requires `xlink:href` (with the xlink namespace). Firefox, which
has the broadest SVG-2 SMIL support, played the animations correctly; all other runtimes
silently ignored them.

Changes:
- `SmilEmitter.renderElement()`: attribute renamed from `href` to `xlink:href`.
- `SmilEmitter.inject()`: now calls `ensureXlinkNamespace()` before appending the SMIL
  fragment, which inserts `xmlns:xlink="http://www.w3.org/1999/xlink"` into the `<svg>` root
  tag if not already present. Idempotent — SVGs that already carry the declaration are
  unchanged.
- `SmilEmitterTest`: `href=` assertions updated to `xlink:href=`; three new tests added:
  namespace injection, no-duplicate-namespace guard, and cross-type xlink:href assertion.

Also bumps project version from `0.19.2` (erroneously left after v0.20.0 release) to `0.20.1`.

## [0.20.0] — 2026-06-26

### Added

**V3.1.43 — M2M Bridge: BPMN Process ⇌ UML Activity**

New module `kuml-codegen/kuml-transform-bpmn-to-uml` providing a bidirectional model-to-model
bridge between BPMN Process models and UML Activity diagrams. Both share the same Petri-net /
token-flow foundation (ADR-0015), making a structurally lossless bridge possible.

New files in `kuml-codegen/kuml-transform-bpmn-to-uml/src/main/kotlin/dev/kuml/transform/bpmnuml/`:
- `BpmnToUmlActivityTransformer` — transformer id `"bpmn-to-uml-activity"`. Converts a
  `BpmnProcess` to a kUML Activity diagram script. `BpmnTask` → `ACTION`, `startEvent` →
  `INITIAL`, `endEvent` → `ACTIVITY_FINAL`, `exclusiveGateway` → `DECISION`/`MERGE`,
  `parallelGateway` → `FORK`/`JOIN`, `inclusiveGateway` → `DECISION` (best-effort, marked in
  metadata). `conditionExpression` → edge `guard`.
- `BpmnToUmlActivityMapper` — pure structural mapping; handles the central pitfall: BPMN
  gateways with both multiple-incoming AND multiple-outgoing (MIXED) are split into two UML
  nodes (MERGE→DECISION or JOIN→FORK) sharing the same `"bpmn.sourceId"` metadata for
  round-trip reconstruction.
- `UmlActivityModel` — typed intermediate holder (`name`, `nodes: List<UmlActivityNode>`,
  `edges: List<UmlActivityEdge>`) decoupling the mapper from the script renderer.
- `UmlActivityScriptRenderer` — emits `activityDiagram(name = "…") { … }` script using the
  kUML Activity DSL.
- `UmlActivityToBpmnTransformer` — transformer id `"uml-activity-to-bpmn"`. Reverses a
  `KumlDiagram` (type = ACTIVITY) back to a `BpmnProcess` script. Collapses DECISION+MERGE (and
  FORK+JOIN) pairs sharing the same `"bpmn.sourceId"` back into a single MIXED gateway.
  Fails with `TransformResult.Failure` for non-ACTIVITY diagrams.
- `UmlActivityToBpmnMapper` — reverse mapping; populates `incoming`/`outgoing` lists by
  scanning sequence flows; detects split-pair collapse via metadata.
- `BpmnProcessScriptRenderer` — emits `bpmnModel { process { … } }` scripts per kUML BPMN DSL.
- `BpmnUmlBridgeRegistry` — convenience `registerAll()` for programmatic (non-ServiceLoader)
  registration, mirrors `AutosarProfile.registerAll()` pattern.
- `BpmnToUmlActivityTransformerProvider` / `UmlActivityToBpmnTransformerProvider` — ServiceLoader
  providers registered via `META-INF/services/dev.kuml.codegen.m2m.KumlTransformerProvider`.

Modified:
- `settings.gradle.kts` — added `kuml-codegen:kuml-transform-bpmn-to-uml` include.
- `kuml-cli/build.gradle.kts` — added `implementation` dependency so ServiceLoader discovers
  both providers at runtime.
- `TransformCommand.kt` — added `--from` / `--to` options (`bpmn`/`uml-activity`) as sugar for
  `--transformer`; added BPMN dispatch branch using `DiagramExtractor.extractAny()` →
  `ExtractedDiagram.Bpmn` → `BpmnProcess` source.

Limitation: Pool/Lane → ActivityPartition is best-effort only. Lane names are recorded in node
metadata key `"uml.partition"` and emitted as Kotlin comments. The kUML UML Activity metamodel
has no PARTITION node kind.

Tests: 29 tests across 4 suites (all green):
`BpmnToUmlActivityTransformerTest` (11), `BpmnUmlRoundTripTest` (5),
`UmlActivityToBpmnTransformerTest` (8), `BpmnUmlBridgeRegistryTest` (5).

Vault example: `03 Bereiche/kUML/Beispiele/10 BPMN zu UML-Aktivität – PdV Prozess.md`.

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

### Security

**V3.1.42 — chain-evm SSRF and DoS hardening (post-review)**

- `EvmUrlValidator`: tightened private-range rejection to cover APIPA (169.254.x.x), IPv6
  `::1` loopback, and all RFC-1918 subnets; scheme whitelist enforces `http`/`https` only.
- `EvmJsonRpcClient`: added per-request HTTP timeouts (connect 5 s, read 10 s) and a
  response-size cap (4 MB) to prevent unbounded memory allocation from malicious RPC nodes.
- `ChainEvmAdapterRunner`: `takeWhile { !manager.isTerminated }` guards the infinite
  subscribe() Cold Flow so no goroutine leaks on session shutdown.
- `ExitCodes`: added `CHAIN_HASH_MISMATCH` (50) and `CHAIN_CONNECT_ERROR` (51) for
  unambiguous exit-code signalling from the EVM adapter path.

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
