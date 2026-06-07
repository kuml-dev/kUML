# CLAUDE.md — kUML Repository

## Projektüberblick

**kUML** ist ein Modellierungswerkzeug, das drei gleichberechtigte Modellierungssprachen als type-safe Kotlin-DSL ausdrückt: **UML 2.x**, **SysML 2** (auf KerML) und **C4**. Dazu kommt ein OCL-Subset für Constraints. Das Projekt folgt dem Prinzip der Model Driven Architecture (MDA): Modelle werden stufenweise transformiert (Design → Implementation → Deployment) und daraus Code generiert.

Das zentrale Alleinstellungsmerkmal: kUML ist **das erste UML-Werkzeug, das bewusst für die LLM-Ära entworfen wurde** — Kotlin-Syntax, semantische Validierung, strukturierte Fehler (JSON), kanonischer Formatter, MCP-Server.

Das Metamodell ist **pure Kotlin** (`sealed`/`data class`), keine EMF-Abhängigkeit im Kern. XMI-Roundtrip mit klassischen UML-Werkzeugen läuft über ein optionales Modul `kuml-io-emf`.

- **Sprache:** Kotlin (JVM)
- **Build:** Gradle Kotlin DSL (Multimodul)
- **Lizenz:** Apache 2.0
- **Dokumentation:** AsciiDoc (`.adoc`) — **kein Markdown** für Projektdokumentation
- **Obsidian-Vault-Notizen:** [[03 Bereiche/kUML/Übersicht]]
- **Architektur-Entscheidungen:** [[03 Bereiche/kUML/ADR/ADR-0001 EMF aus dem Kern entfernen]], [[03 Bereiche/kUML/ADR/ADR-0002 kuml Format durch kts mit Implicit Imports ersetzen]], [[03 Bereiche/kUML/ADR/ADR-0003 SysML 2 als eigenständiges Metamodell]], [[03 Bereiche/kUML/ADR/ADR-0005 C4 als First-Class-Modellierungssprache]]

---

## Tech-Stack

| Bereich | Technologie |
|---|---|
| Sprache | Kotlin (JVM) |
| Build | Gradle Kotlin DSL |
| Metamodell | Pure Kotlin (`sealed`/`data class`) — kein EMF im Kern |
| Eingabeformat | Kotlin Scripting (`*.kuml.kts` mit `KumlScriptDefinition` + `defaultImports`) |
| Serialisierung | `kotlinx.serialization` |
| OCL | Eigener OCL-Subset-Interpreter auf dem Kotlin-Modell |
| Rendering | Kuiver (Kotlin/Compose) + ELK (Layout) |
| Native | GraalVM Native Image für die CLI |
| XMI (optional) | `kuml-io-emf` über Eclipse UML2 — separat geladen, nicht im Kern |
| C4-Brücke | `kuml-io-structurizr` — Roundtrip mit Structurizr DSL |
| Web-UI | Ktor + KVision + Kilua RPC |
| Desktop-UI | Kotlin Compose Multiplatform |
| CLI | Kotlinx CLI |
| MCP-Server | Ktor (Stdio + SSE Transport) |
| LLM-Integration | Eigenes `LlmBackend`-Interface (Anthropic, OpenAI, Ollama) |
| Tests (Unit/Integration) | Kotlin Test + JUnit 5 + Kotest + AssertJ |
| Tests (Web-UI) | Playwright (JVM) — Headless-Browser-Tests für `kuml-web` |
| Tests (Desktop-UI) | Compose UI Testing (`org.jetbrains.compose.ui:ui-test-junit4`) |
| Tests (IDE-Plugin) | IntelliJ Platform Test Framework (`LightCodeInsightFixtureTestCase`) |

---

## Modulstruktur

```
kuml/
├── kuml-core/
│   ├── kuml-core-model/    # Pure Kotlin Modell-Basistypen (KumlElement, KumlModel, …)
│   ├── kuml-core-dsl/      # DSL-Builder-Infrastruktur (sprachenübergreifend)
│   ├── kuml-core-script/   # Kotlin Scripting Host + KumlScriptDefinition
│   └── kuml-core-ocl/      # OCL-Subset-Interpreter auf dem Kotlin-Modell
├── kuml-metamodel/         # Modellierungssprachen — jede mit eigenem Metamodell
│   ├── kuml-metamodel-uml/      # UML 2.x — pure Kotlin
│   ├── kuml-metamodel-kerml/    # KerML — Basis für SysML 2
│   ├── kuml-metamodel-sysml2/   # SysML 2 auf KerML
│   └── kuml-metamodel-c4/       # C4-Modell — eigenständige Sprache
├── kuml-renderer/
│   ├── kuml-kuiver/        # Kuiver-basiertes SVG-Rendering
│   ├── kuml-layout/        # ELK-Anbindung, sprachenspezifische Layouts
│   └── kuml-themes/        # Theme-System & Built-in Themes
├── kuml-io/
│   ├── kuml-io-svg/        # SVG-Export, PNG-Konvertierung
│   ├── kuml-io-json/       # kotlinx.serialization Modell-Persistenz
│   ├── kuml-io-structurizr/# C4 ⇌ Structurizr DSL (Bridge)
│   └── kuml-io-emf/        # OPTIONAL: XMI ⇌ Eclipse UML2 (EA/Papyrus/MagicDraw)
├── kuml-profile/           # UML-Profile (Stereotypen, Tagged Values, OCL)
│   ├── kuml-profile-api/
│   ├── kuml-profile-soaml/     # OMG SoaML — Pilot
│   ├── kuml-profile-autosar/
│   ├── kuml-profile-javaee/
│   ├── kuml-profile-spring/
│   └── kuml-profile-openapi/
├── kuml-transform/         # M2M Modell-zu-Modell-Transformation
│   ├── kuml-transform-api/
│   ├── kuml-transform-uml-to-jpa/
│   ├── kuml-transform-uml-to-rest/
│   ├── kuml-transform-uml-to-k8s/
│   ├── kuml-transform-uml-to-docker/
│   └── kuml-transform-c4-to-uml/
├── kuml-codegen/           # M2T Code-Generierung
│   ├── kuml-codegen-api/
│   ├── kuml-gen-kotlin/
│   ├── kuml-gen-java/
│   └── kuml-gen-sql/
├── kuml-reverse/
│   ├── kuml-reverse-kotlin/
│   └── kuml-reverse-java/
├── kuml-llm/               # LLM-Integration (provider-neutral)
│   ├── kuml-llm-core/      # LlmBackend Interface
│   ├── kuml-llm-anthropic/ # Claude (Sonnet, Haiku, Opus)
│   ├── kuml-llm-openai/    # GPT-4o, o1
│   ├── kuml-llm-ollama/    # Lokale Modelle
│   ├── kuml-llm-spec/      # llms.txt + JSON-Schema Generator
│   ├── kuml-llm-describe/  # Modell → Natursprache
│   ├── kuml-llm-fewshot/   # Few-Shot-Bibliothek
│   └── kuml-llm-bench/     # LLM-Benchmark-Suite
├── kuml-mcp/               # MCP-Server (Ktor, Stdio + SSE)
├── kuml-cli/               # Kommandozeilen-Interface
├── kuml-gradle-plugin/     # Gradle-Plugin (dev.kuml)
├── kuml-web/               # Web-Interface (Ktor + KVision)
├── kuml-desktop/           # Compose Desktop UI
├── kuml-docs/
│   ├── kuml-markdown/      # Markdown kuml-Codeblock → SVG
│   └── kuml-asciidoc/      # Asciidoctor Extension
├── kuml-intellij-plugin/   # JetBrains IDE Plugin
├── kuml-tests/             # Alle Tests (Unit, Integration, System, UI)
│   ├── kuml-dsl-tests/             # Unit: alle 14 UML + SysML 2 + C4 Diagrammtypen
│   ├── kuml-ocl-tests/             # Unit: OCL-Subset-Constraint-Validierung
│   ├── kuml-formatter-tests/       # Unit: kuml fmt Idempotenz
│   ├── kuml-renderer-tests/        # Integration: SVG Snapshot-Tests
│   ├── kuml-structurizr-tests/     # Integration: C4 ⇌ Structurizr Roundtrip
│   ├── kuml-emf-tests/             # Integration: optionaler XMI-Roundtrip (kuml-io-emf)
│   ├── kuml-transform-tests/       # Integration: M2M Transformationen
│   ├── kuml-codegen-tests/         # Integration: Kotlin / Java / SQL Generatoren
│   ├── kuml-reverse-tests/         # Integration: Kotlin/Java → Modell
│   ├── kuml-mcp-tests/             # Integration: MCP-Tool-Aufrufe
│   ├── kuml-gradle-plugin-tests/   # Integration: GradleRunner-Tests
│   ├── kuml-cli-tests/             # System: CLI-Befehle end-to-end
│   ├── kuml-llm-tests/             # System: LLM-Mock + @Tag("live")
│   ├── kuml-web-tests/             # UI: Playwright Browser-Tests
│   ├── kuml-desktop-tests/         # UI: Compose UI Testing
│   └── kuml-intellij-plugin-tests/ # UI: IntelliJ Platform Test Framework
├── kuml-examples/          # Vollständige Beispielprojekte
├── kuml-packaging/         # GraalVM Native, DEB, RPM, DMG, MSI, Docker
└── docs/                   # Benutzerhandbuch (AsciiDoc + Antora)
```

> Bewusste Streichungen gegenüber frühen Entwürfen: `kuml-preprocessor` (durch Kotlin Scripting ersetzt), `kuml-profile-sysml` / `kuml-profile-c4` (SysML 2 und C4 sind eigene Metamodelle). `kuml-xmi` ist umbenannt zu `kuml-io-emf` und auf optional zurückgestuft.

---

## Wichtige Gradle-Befehle

```bash
# Gesamtes Projekt bauen
./gradlew build

# Nur Tests ausführen
./gradlew test

# Ein Modul bauen
./gradlew :kuml-core:kuml-core-dsl:build

# Tests für ein Modul
./gradlew :kuml-tests:kuml-dsl-tests:test

# Snapshot-Tests aktualisieren (SVG-Referenzen)
./gradlew :kuml-tests:kuml-renderer-tests:test -PupdateSnapshots

# CLI lokal ausführen
./gradlew :kuml-cli:run --args="render examples/hello.kuml.kts --format svg"

# Alle Beispiele rendern (Smoke-Test)
./gradlew :kuml-examples:kumlRender

# UI-Tests (Web, Desktop, IDE-Plugin)
./gradlew :kuml-tests:kuml-web-tests:test
./gradlew :kuml-tests:kuml-desktop-tests:test
./gradlew :kuml-tests:kuml-intellij-plugin-tests:test

# Nur Live-LLM-Tests ausführen (erfordert ANTHROPIC_API_KEY)
./gradlew :kuml-tests:kuml-llm-tests:test -Dgroups=live

# Alle Tests außer @Tag("live")
./gradlew test -DexcludeTags=live

# Code formatieren
./gradlew ktlintFormat

# Abhängigkeiten prüfen
./gradlew dependencyUpdates
```

---

## Sprachkonventionen

| Bereich | Sprache |
|---|---|
| Klassen-, Methoden-, Variablennamen | **Englisch** |
| Kommentare und KDoc | **Englisch** |
| Dokumentation (README.adoc, CONTRIBUTING.adoc, Handbuch) | **Englisch** |
| Commit-Messages, PR-Titel und -Beschreibungen | **Englisch** |
| Fehler-`message`-Felder im JSON-Output | **Englisch** |
| UI-Texte (Labels, Tooltips, Fehlermeldungen) | **Englisch** primär — Deutsch via `de`-Lokalisierung |
| Snapshot-Test-Methodennamen | **Englisch** |

**UI-Lokalisierung:** Alle UI-Strings (Web-UI, Desktop, IDE-Plugin) werden über ein i18n-Framework ausgeliefert. Keine hart codierten Strings im Quellcode. `en` ist Pflicht, `de` ist die erste Übersetzung.

---

## Git-/Repository-Konventionen

> [!important] Default-Branch: **`master`** — niemals `main`
> In **allen** kUML-Repos (`kuml-dev/kuml`, `kuml-dev/homebrew-kuml` und alle künftigen) ist der Default-Branch `master`. Workflows, Doku, Skripte und Examples verwenden ausschließlich `master`.
>
> Konkret:
> - GitHub Actions `on.push.branches` / `on.pull_request.branches` → `[ master ]`
> - `git push origin master`, `git checkout master` in Doku
> - Release-Workflows triggern auf Tags (`v*.*.*`) — die Branch-Frage entfällt dort ohnehin
> - Neue Repos: nach `git init` direkt `git branch -m main master` und `gh repo edit --default-branch master`

> [!important] Keine Commit-SHAs in READMEs
> READMEs nennen **niemals** einen Commit-SHA als „Aktualitätsstempel" (`Commit: \`fc1a8ba\`.`). Solche Stempel sind ab der nächsten Änderung am Modul falsch und vermitteln eine Aktualität, die die Datei nicht garantieren kann.
>
> - Quelle der Wahrheit für Modul-Stand: `git log <pfad>` und das Inhaltsverzeichnis der `.adoc`-Datei.
> - Wenn ein Modul-README einen Bezug zu einem bestimmten Commit braucht (z. B. „ADR-konform seit …"), nenne stattdessen das **Datum** oder den **Versions-Tag** (`Seit v0.1.0`), nicht den SHA.

### Release-Choreographie (kUML ↔ kuml.dev synchron)

Bei **jedem neuen kUML-Release** (Tag `v*.*.*` auf `kuml-dev/kUML`) gehört dazu ein
synchrones Update der kuml.dev-Webseite:

1. **CHANGELOG-Eintrag** in `CHANGELOG.md` für die neue Version, vor dem Push des Tags.
2. **`src/pages/whats-new.astro`** (EN) und **`src/pages/de/aktuelles.astro`** (DE) auf
   kuml.dev bekommen einen neuen Eintrag oben — gleicher Inhalt wie der CHANGELOG-Block,
   aber Marketing-tauglich aufbereitet (bürgernah, Features statt Commits).
3. **`src/pages/features.astro`** und die Vergleichs-Matrix `src/pages/comparison.astro`
   werden um neue Features ergänzt, sodass kuml.dev nie hinter dem aktuellen Code zurück
   bleibt.
4. **Build-Smoke-Test**: `npm run build` muss clean durchlaufen.
5. **Commit-Format auf kuml.dev**:
   `Sync site with kUML vX.Y.Z (<kurzbeschreibung der hauptneuerung>)`

Die Webseite **muss vor dem nächsten Release wieder aktuell sein** — kein Drift
zwischen Code und Marketing-Surface.

---

## Kotlin-Coding-Konventionen

### 1. Named Parameters — PFLICHT

In **allen** Funktions- und Konstruktoraufrufen sind benannte Parameter zu verwenden, wo die API dies erlaubt. Dies ist besonders wichtig für die DSL, da LLMs zuverlässigeren Code generieren, wenn Parameternamen explizit sind.

```kotlin
// ✅ Korrekt
diagram(
    name = "Systemübersicht",
    type = DiagramType.CLASS,
)

classOf("Order") {
    attribute(name = "id", type = UUID, visibility = PRIVATE)
    operation(name = "confirm", visibility = PUBLIC, returns = Unit::class)
}

association(source = Order::class, target = OrderItem::class) {
    aggregation = COMPOSITE
    source { multiplicity = "1" }
    target { multiplicity = "1..*" }
}

// ❌ Vermeiden
diagram("Systemübersicht", DiagramType.CLASS)
attribute("id", UUID, PRIVATE)
```

### 2. Visibility Modifier — immer explizit in der DSL

```kotlin
// ✅ Korrekt — explizit
classOf("User") {
    visibility = PUBLIC
    attribute(name = "id", type = UUID, visibility = PRIVATE)
}

// ❌ Vermeiden — implizit
classOf("User") {
    attribute(name = "id", type = UUID)
}
```

### 3. Immutabilität bevorzugen

```kotlin
// ✅ val bevorzugen — Modelle sind immutable Kotlin-Hierarchien
val model: KumlModel = diagram(name = "Test") { classOf("User") }.toModel()

// data classes für Datencontainer
data class DiagramError(
    val code: String,
    val line: Int,
    val column: Int,
    val message: String,
    val suggestion: String? = null,
)
```

### 4. Kotlin-Idiome

- `when` statt `if-else if`-Ketten
- `?.let`, `?:`, `!!` nur wenn wirklich nötig (NPE vermeiden)
- Extension Functions für DSL-Builder bevorzugen
- Keine Java-Collection-API wenn Kotlin-Stdlib verfügbar

### 5. Fehlerbehandlung

Alle Fehler (Skript-Compiler, Typchecker, OCL-Subset, optionaler XMI) müssen **strukturiert** ausgegeben werden — niemals plain text:

```kotlin
data class KumlError(
    val code: String,           // z.B. "KUML-E-201"
    val severity: Severity,     // ERROR, WARNING, INFO
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,
    val message: String,
    val expected: List<String> = emptyList(),
    val suggestion: String? = null,
    val docUrl: String? = null,
)
```

Jeder CLI-Befehl unterstützt `--output json` für maschinenlesbare Fehlerausgabe.

---

## DSL-Konventionen

### Top-Level-Schlüsselwörter (konsistent, vorhersagbar)

**UML 2.x** (`diagram { … }`)

| Konzept | Schlüsselwort |
|---|---|
| Klasse | `classOf` |
| Interface | `interfaceOf` |
| Enum | `enumOf` |
| Abstrakte Klasse | `classOf` + `isAbstract = true` |
| Assoziation | `association` |
| Komposition | `association` + `aggregation = COMPOSITE` |
| Aggregation | `association` + `aggregation = SHARED` |
| Abhängigkeit | `dependency` |
| Realisierung | `realization` |

**SysML 2** (`sysml2Model { … }`)

| Konzept | Schlüsselwort |
|---|---|
| Part-Definition | `partDef` |
| Part-Usage | `part` |
| Port-Definition | `portDef` |
| Port-Usage | `port` |
| Attribut | `attribute` |
| Requirement | `requirementDef` |
| Constraint | `constraintDef` |

**C4** (`c4Model { … }`)

| Konzept | Schlüsselwort |
|---|---|
| Person | `person` |
| Software-System | `softwareSystem` |
| Container | `container` |
| Component | `component` |
| Beziehung | `relationship` |

### Kanonische Form

Es gibt genau **eine** idiomatische Schreibweise pro Konzept. `kuml fmt` erzwingt sie. Der Formatter ist idempotent: `fmt(fmt(x)) == fmt(x)`.

### Dateiformat

```
*.kt        → Vollständige Kotlin-Datei mit expliziten Imports
*.kts       → Kotlin-Skript mit expliziten Imports
*.kuml.kts  → Kotlin-Skript mit KumlScriptDefinition + defaultImports — keine Imports nötig (empfohlen)
```

> Das frühere Format `*.kuml` mit eigenem Preprocessor entfällt — siehe [[03 Bereiche/kUML/ADR/ADR-0002 kuml Format durch kts mit Implicit Imports ersetzen]].

---

## Architektur-Entscheidungen (frühe Weichenstellungen)

Diese Entscheidungen wirken quer durch alle Module — nie nachträglich ändern ohne alle Betroffenen zu aktualisieren. Formelle ADRs liegen im Vault unter `03 Bereiche/kUML/ADR/`.

### ✅ ADR-0001: Pure Kotlin-Metamodell, kein EMF im Kern

Das Metamodell ist eine pure Kotlin-Hierarchie (`sealed`/`data class`), keine EMF-Typen in der öffentlichen API. EMF/XMI ist auf das optionale Modul `kuml-io-emf` zurückgezogen. Details: [[03 Bereiche/kUML/ADR/ADR-0001 EMF aus dem Kern entfernen]].

### ✅ ADR-0002: `*.kuml.kts` statt eigenem Preprocessor

Eingabeformat ist Kotlin Scripting mit eigener `ScriptDefinition` und `defaultImports`. Kein eigener Preprocessor, keine temporären `.kt`-Dateien. Details: [[03 Bereiche/kUML/ADR/ADR-0002 kuml Format durch kts mit Implicit Imports ersetzen]].

### ✅ ADR-0003: SysML 2 als eigenständiges Metamodell

SysML 2 ist kein UML-Profil mehr, sondern ein eigenes Metamodell auf KerML-Basis (`kuml-metamodel-sysml2`). Details: [[03 Bereiche/kUML/ADR/ADR-0003 SysML 2 als eigenständiges Metamodell]].

### ✅ ADR-0005: C4 als First-Class-Modellierungssprache

C4 ist eine eigene Sprache mit eigenem Metamodell (`kuml-metamodel-c4`) und eigener DSL (`c4Model { … }`), kein UML-Profil. Migration aus/nach Structurizr DSL über `kuml-io-structurizr`. Details: [[03 Bereiche/kUML/ADR/ADR-0005 C4 als First-Class-Modellierungssprache]].

### ✅ ADR-0004: Scope-Reduktion für V1

V1 ist radikal geschnitten: **UML 2.x (5 Diagrammtypen)** + **C4 (vollständig)** + OCL-Subset + CLI + Markdown-Code-Block + `kuml-gen-kotlin` + MCP-Server + Maven Central + Homebrew. SysML 2, M2M, Reverse Engineering, Web/Desktop-UI, IDE-Plugin (voll), XMI-Roundtrip sind V1.1 oder V2. Details: [[03 Bereiche/kUML/ADR/ADR-0004 Scope Reduktion fuer V1]].

### ✅ Entschieden: Strukturierte Fehler (Phase 1)

Fehler sind JSON-serialisierbar von Anfang an. Kein späteres Umbauen. Gilt für den Skript-Compiler, Typchecker, OCL-Subset-Interpreter und das optionale `kuml-io-emf`.

### ✅ Entschieden: Kanonischer Formatter

`kuml fmt` wird idempotent implementiert. Keine alternativen Schreibweisen zulassen.

### ✅ Entschieden: Provider-neutrales LLM-Backend

`LlmBackend`-Interface in `kuml-llm-core`. Keine eingebaute Präferenz. Implementierungen: `kuml-llm-anthropic`, `kuml-llm-openai`, `kuml-llm-ollama`.

### ✅ Entschieden: Apache 2.0 Lizenz

Gilt für gesamten Quellcode, alle Bibliotheken und Artefakte.

### ✅ Entschieden: AsciiDoc für alle Dokumentation

`README.adoc`, API-Docs, `CONTRIBUTING.adoc`, `CHANGELOG.adoc`, Handbuch (Antora). **Kein Markdown** für Projektdokumentation.

### ✅ Entschieden: Ktor + KVision + Kilua RPC für Web-UI

Nicht Compose Web, nicht HTMX. Kilua RPC übernimmt die typsichere Kommunikation zwischen Ktor-Backend und KVision-Frontend — kein manuelles REST/JSON-Mapping.

### ✅ Entschieden: GraalVM Native Image für die CLI

GraalVM Native Image für CLI — Ziel < 100 ms Startzeit. Reflection-Konfiguration ausschließlich für den Kotlin Scripting Host und `kotlinx.serialization`. Das optionale `kuml-io-emf` wird **nicht** in die Native Image gebaut.

---

## Test-Konventionen

```kotlin
// Test class naming
class ClassDiagramTest                // UML structural diagram
class SequenceDiagramTest             // UML behavioral diagram
class SysML2PartDefinitionTest        // SysML 2
class C4ContainerDiagramTest          // C4

// Test method names in English (backtick syntax for readability)
@Test
fun `minimal class diagram builds a KumlModel`() { }

@Test
fun `inheritance and interfaces are modeled correctly`() { }

@Test
fun `invalid multiplicity is reported as KUML-E-xxx`() { }

@Test
fun `c4 container diagram renders all containers of a system`() { }
```

### Test-Frameworks

- **Kotlin Test + JUnit 5** — Unit Tests
- **Kotest** — Property-Based Testing, Matchers
- **AssertJ** — SVG/XML-Assertions
- **Snapshot Testing** — SVG-Ausgaben gegen Referenzen (`src/test/resources/snapshots/`)
- **Playwright (JVM)** — Browser-Tests für `kuml-web` (Ktor + KVision)
- **Compose UI Testing** — Headless-Desktop-Tests für `kuml-desktop`
- **IntelliJ Platform Test Framework** — Plugin-Tests (Highlighting, Completion, Annotator, Preview)

### UI-Test-Konventionen

```kotlin
// Web-UI (Playwright) — data-testid-Attribute für stabile Selektoren
page.locator("[data-testid='kuml-editor']").fill(code)
page.waitForSelector("[data-testid='kuml-preview'] svg")

// Compose Desktop — semantische Tags
onNodeWithTag("kuml-editor").performTextInput(code)
mainClock.advanceTimeBy(400)   // Debounce-Zeit überbrücken

// IntelliJ Plugin — JUnit-3-Konvention (Methoden beginnen mit "test ")
// Klassen erben von LightCodeInsightFixtureTestCase
fun `test completion offers attribute in class body`() { ... }

// Live-Tests (echte LLM-Calls, CI-Flag)
@Test
@Tag("live")
fun `anthropic backend returns valid kuml`() { ... }
// Ausführen: ./gradlew test -Dgroups=live
// Ausschließen: ./gradlew test -DexcludeTags=live   ← Standard-CI
```

### `data-testid`-Attribute (Web-UI)

Alle interaktiven UI-Elemente in `kuml-web` erhalten ein `data-testid`-Attribut — nie auf CSS-Klassen oder XPath testen:

| Element | `data-testid` |
|---|---|
| Editor-Textbereich | `kuml-editor` |
| SVG-Vorschau | `kuml-preview` |
| Fehleranzeige | `error-panel` |
| Theme-Auswahl | `theme-select` |
| Sprachwechsel (UML / SysML 2 / C4) | `language-select` |
| SVG-Download | `download-svg` |
| Structurizr-Export (C4) | `download-structurizr` |
| XMI-Download (nur wenn `kuml-io-emf` aktiv) | `download-xmi` |

### Compose-Tags (Desktop-UI)

Alle Compose-Komponenten in `kuml-desktop` erhalten `Modifier.testTag("...")`:

| Komponente | `testTag` |
|---|---|
| Editor | `kuml-editor` |
| Vorschau | `kuml-preview` |
| Toolbar | `toolbar` |
| Fehler-Panel | `error-panel` |
| Theme-Dropdown | `theme-dropdown` |
| Datei-Öffnen | `open-file-button` |
| Speichern | `save-button` |
| Plugin-Manager | `plugin-manager-button` |

### Snapshot-Tests aktualisieren

Snapshots nur aktualisieren wenn das Rendering-Ergebnis bewusst geändert wird:

```bash
./gradlew :kuml-tests:kuml-renderer-tests:test -PupdateSnapshots
```

---

## MCP-Server (`kuml-mcp`)

Der MCP-Server läuft via Stdio oder SSE. Tools:

| Tool | Funktion |
|---|---|
| `kuml.validate(code)` | Strukturierte Fehlerliste (JSON) |
| `kuml.render(code, format)` | SVG / PNG / ASCII inline |
| `kuml.describe(code)` | Natursprachliche Beschreibung |
| `kuml.list_elements(code)` | Alle Klassen/Beziehungen |
| `kuml.suggest(partial)` | Autovervollständigung |
| `kuml.diff(a, b)` | Semantisches Modell-Diff |
| `kuml.transform(code, transformer)` | M2M-Transformation |
| `kuml.generate(code, plugin)` | Code-Generator |

Lokaler Start für Entwicklung:

```bash
./gradlew :kuml-mcp:run
```

---

## LLM-Konfiguration (`kuml.config.kts`)

```kotlin
ai {
    provider = "anthropic"          // oder "openai", "ollama"
    model = "claude-sonnet-4-7"
    apiKey = env("ANTHROPIC_API_KEY")
    maxIterations = 3               // Self-correction tries
    contextFiles = listOf("llms-full.txt", "examples/")
}
```

---

## Aktuelle Phase

Roadmap: **Phase 0 — Setup** (Woche 1–2)

- ✅ GitHub Organisation angelegt: https://github.com/kuml-dev
- ✅ GitHub Repository angelegt: https://github.com/kuml-dev/kUML (`git@github.com:kuml-dev/kUML.git`)
- ✅ Gradle-Multimodul-Projekt eingerichtet (Kotlin 2.1.21, Gradle 9.5.1, JVM 21, Version Catalog, ktlint)
- ✅ CI/CD (GitHub Actions — `.github/workflows/ci.yml`)
- ✅ Pure Kotlin Metamodell-Basis (`kuml-core-model`) implementiert
- ✅ Kotlin Scripting Host (`kuml-core-script`) mit `KumlScriptDefinition` aufgesetzt
- ✅ Hello-World: `diagram(name = "Test") { }` in `*.kuml.kts` läuft — 4/4 Tests grün
- Gradle-Multimodul-Projekt einrichten
- CI/CD (GitHub Actions)
- Pure Kotlin Metamodell-Basis (`kuml-core-model`) skizzieren
- Kotlin Scripting Host (`kuml-core-script`) mit `KumlScriptDefinition` aufsetzen
- Erstes Hello-World: `diagram(name = "Test") { }` in einer `*.kuml.kts`-Datei compiliert

**Phase 0 abgeschlossen ✅** (2026-05-28)

Nächste Phase: **Phase 1 — Kern: UML-DSL + Rendering** (Klassendiagramme → SVG)

Vollständige Roadmap: siehe Vault [[03 Bereiche/kUML/Roadmap]]

---

## Branding & Logos

Logo-Quelldateien liegen im Vault unter `03 Bereiche/kUML/Design/` und müssen beim Repository-Setup nach `docs/images/` kopiert werden:

| Vault-Datei | Repo-Pfad | Verwendung |
|---|---|---|
| `Gemini_16x9.png` | `docs/images/kuml-banner.png` | Root-README-Header (Banner, 16:9) |
| `Gemini_1x1.png` | `docs/images/kuml-logo.png` | Modul-READMEs, GitHub-Profil (Quadrat) |
| `Gemini-Einfach_1x1.png` | `docs/images/kuml-logo-simple.png` | Vereinfachte Variante, kleine Größen, Favicons |

AsciiDoc-Einbindung im **Root-README** (Banner oben):

```asciidoc
image::docs/images/kuml-banner.png[kUML — Kotlin UML Modelling,link=https://kuml.dev]
```

AsciiDoc-Einbindung in **Modul-READMEs** (kleines Icon mit Titel):

```asciidoc
image:../../docs/images/kuml-logo-simple.png[kUML,width=64,role=left] *kuml-core-dsl*
```

> Hinweis: Der Pfad `../../docs/images/` ist relativ zum Modul-Unterordner. Im Root-README entfällt das `../../`.

---

## Dokumentation schreiben

- Alle README-Dateien: `README.adoc` (AsciiDoc)
- API-Dokumentation: KDoc in Kotlin-Quellcode
- Benutzerhandbuch: `docs/` (Antora + AsciiDoc)
- Changelog: Conventional Commits → automatisch generiert
- `llms.txt` und `llms-full.txt`: automatisch generiert aus DSL-Schema via `kuml-llm-spec`

Vorlage für das **Root-README** (`README.adoc`):

```asciidoc
= kUML
:toc:
:toc-placement: preamble
:icons: font

image::docs/images/kuml-banner.png[kUML Banner,link=https://kuml.dev]

*kUML* ist ein Modellierungswerkzeug, das UML 2.x, SysML 2 und C4 als
type-safe Kotlin-DSL ausdrückt — das erste UML-Werkzeug, das bewusst
für die LLM-Ära entworfen wurde.

image:https://img.shields.io/maven-central/v/dev.kuml/kuml-core[Maven Central]
image:https://img.shields.io/github/license/kuml-dev/kUML[Apache 2.0]

== Quick Start

[source,kotlin]
----
// hello.kuml.kts
diagram(name = "Hello kUML", type = DiagramType.CLASS) {
    classOf("User") {
        attribute(name = "id", type = UUID, visibility = PRIVATE)
        operation(name = "greet", visibility = PUBLIC, returns = String::class)
    }
}
----

[source,bash]
----
kuml render hello.kuml.kts --format svg
----

== Lizenz

Apache 2.0 — siehe link:LICENSE[LICENSE]
```

Vorlage für ein **Modul-README** (z. B. `kuml-core-dsl/README.adoc`):

```asciidoc
= kuml-core-dsl
:toc:

image:../../docs/images/kuml-logo-simple.png[kUML,width=48,role=left]

Kotlin-DSL-Builder für kUML. Sprachenübergreifende Infrastruktur — die konkreten
DSLs für UML 2.x, SysML 2 und C4 leben in den `kuml-metamodel-*`-Modulen.

== Installation

[source,kotlin]
----
dependencies {
    implementation("dev.kuml:kuml-core-dsl:1.0.0")
    implementation("dev.kuml:kuml-metamodel-uml:1.0.0")   // optional
    implementation("dev.kuml:kuml-metamodel-c4:1.0.0")    // optional
    implementation("dev.kuml:kuml-metamodel-sysml2:1.0.0")// optional
}
----
```

---

## Claude Code Skills

### Empfohlene Skills (nach Priorität)

| Priorität | Skill | Zweck |
|---|---|---|
| 🥇 | **`/init`** | Als erstes ausführen — erstellt/aktualisiert diese CLAUDE.md mit aktuellem Codebase-Kontext |
| 🥇 | **Context7** (MCP) | Aktuelle Doku für Kotlin Scripting, Gradle DSL, Ktor, KVision, Kilua RPC, ELK, Structurizr DSL — Trainingsdaten sind veraltet. Eclipse UML2 nur relevant, wenn `kuml-io-emf` berührt wird. |
| 🥈 | **`/claude-api`** | Für `kuml-llm-anthropic/` und `kuml ai`-Kommandos — triggert automatisch bei Anthropic-SDK-Imports |
| 🥈 | **`/review`** | PR-Reviews: DSL-API-Design, Plugin-Contracts, Kotlin-Idiome |
| 🥈 | **`/security-review`** | MCP-Server (Stdio/SSE, externe Inputs) und API-Key-Handling in `kuml.config.kts` |
| 🥉 | **`/simplify`** | Code-Qualität nach schnellen Implementierungsphasen |
| 🥉 | **Mermaid Chart** (MCP) | Für LLM-Benchmark (F10): Mermaid-Diagramme direkt rendern zum Qualitätsvergleich |

### Installation

#### MCP-Server (Context7, Mermaid Chart)

```bash
# Context7 — aktuelle Bibliotheksdoku
claude mcp add context7 -- npx -y @upstash/context7-mcp

# Mermaid Chart
claude mcp add mermaid -- npx -y @mermaid-chart/mcp
```

Alternativ manuell in `~/.claude/claude.json`:

```json
{
  "mcpServers": {
    "context7": {
      "command": "npx",
      "args": ["-y", "@upstash/context7-mcp"]
    }
  }
}
```

#### Custom Skills / Slash Commands

Skills sind Markdown-Dateien in `.claude/commands/` (projekt-lokal) oder `~/.claude/commands/` (global):

```bash
# Projektverzeichnis vorbereiten
mkdir -p .claude/commands

# Skill-Pack klonen (global)
git clone https://github.com/kepano/obsidian-skills ~/.claude/commands/
```

#### kUML-spezifische Slash Commands (bereits eingerichtet)

Projektspezifische Commands in `.claude/commands/` — prüfen DSL-Konventionen, generieren Tests und Fehler nach kUML-Schema:

| Priorität | Command | Zweck | Roadmap-Phase |
|---|---|---|---|
| 🥇 | `/dsl-check` | DSL-Konsistenz: Named Params, kanonische Form, Sichtbarkeit | Phase 1+ |
| 🥇 | `/kuml-error` | Strukturierte Fehler mit korrekten KUML-E-xxx Codes | Phase 1+ |
| 🥇 | `/gen-test` | Test-Boilerplate nach kUML-Konventionen generieren | Phase 1–8 |
| 🥈 | `/adoc` | AsciiDoc-Dokumentation schreiben (nie Markdown!) | Phase 0+ |
| 🥈 | `/emf` | Eclipse EMF Guidance für `kuml-io-emf` (optional) | Phase 0–3 |
| 🥉 | `/mcp-review` | Sicherheits-Review für MCP-Tool-Implementierungen | Phase 6.5 |

#### Allgemeine Workflow-Commands (bereits eingerichtet)

Universelle Entwicklungs-Commands aus offiziellen Claude Code Plugins — in `.claude/commands/` eingecheckt, für alle Repo-Kloner sofort verfügbar:

| Priorität | Command | Zweck | Wann verwenden |
|---|---|---|---|
| 🥇 | `/feature-dev` | 7-Phasen-Feature-Entwicklung mit Multi-Agenten (Exploration → Architektur → Review) | Neue Roadmap-Features implementieren |
| 🥇 | `/code-review` | GitHub-PR-Review: 5 parallele Agenten + Confidence-Scoring (≥80 = real) | Nach PR-Erstellung, automatisch kommentiert |
| 🥇 | `/commit` | Git-Commit mit automatisch generierter Commit-Message | Schneller Commit auf aktuellem Branch |
| 🥈 | `/commit-push-pr` | Branch → Commit → Push → PR in einem Schritt | Feature fertig, PR direkt aufmachen |
| 🥈 | `/review-pr` | Lokales Pre-PR-Review: Tests, Types, Error-Handling, Simplification | Vor dem PR-Erstellen, ohne GitHub |
| 🥉 | `/clean-gone` | Verwaiste lokale Branches löschen (nach remote-Merge) | Regelmäßige Branch-Hygiene |

#### Reihenfolge beim ersten Öffnen des Repos

```bash
# 1. Im Repo-Root
cd ~/workspace/kuml

# 2. CLAUDE.md initialisieren (oder aktualisieren)
/init

# 3. Context7 prüfen
/use-mcp context7

# 4. Ersten DSL-Code schreiben → /dsl-check
# 5. Ersten Test schreiben → /gen-test
# 6. Feature entwickeln → /feature-dev "Klassendiagramm-DSL für Phase 1"
```

---

## Verwandte Vault-Notizen

- [[03 Bereiche/kUML/Übersicht]]
- [[03 Bereiche/kUML/Architektur]]
- [[03 Bereiche/kUML/DSL und Dateiformate]]
- [[03 Bereiche/kUML/LLM Eignung und Vibe Coding]]
- [[03 Bereiche/kUML/Plugin-API Design]]
- [[03 Bereiche/kUML/Tests und Beispiele]]
- [[03 Bereiche/kUML/Roadmap]]
