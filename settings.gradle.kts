pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kUML"

// ── Profiles ─────────────────────────────────────────── V1.1 ──
include(":kuml-profile")
include(":kuml-profile:kuml-profile-api")

// ── Core ─────────────────────────────────────────────── Phase 0 ──
include(
    "kuml-core:kuml-core-model",    // Phase 0 — Kotlin Metamodell-Basis
    "kuml-core:kuml-core-dsl",      // Phase 0 — DSL-Builder-Infrastruktur
    "kuml-core:kuml-core-script",   // Phase 0 — Kotlin Scripting Host
    "kuml-core:kuml-core-ocl",      // Phase 2 — OCL-Subset-Interpreter (skeleton)
)

// ── Metamodels ───────────────────────────────────────── Phase 1 ──
include(
    "kuml-metamodel:kuml-metamodel-uml",    // Phase 1 — UML 2.x (5 Diagrammtypen)
    "kuml-metamodel:kuml-metamodel-c4",     // Phase 1 — C4 (vollständig)
    // "kuml-metamodel:kuml-metamodel-kerml",  // V2 — KerML-Basis für SysML 2
    // "kuml-metamodel:kuml-metamodel-sysml2", // V2 — SysML 2 auf KerML
)

// ── Renderer ─────────────────────────────────────────── Phase 1 ──
include(
    "kuml-renderer:kuml-layout-api",     // Phase 1 — Engine-agnostische Layout-API ([[ADR-0006]])
    "kuml-renderer:kuml-layout-bridge",  // Phase 1 — Modell-zu-LayoutGraph-Bridge ([[ADR-0006]])
    "kuml-renderer:kuml-kuiver",         // Phase 1 — Kuiver-basiertes SVG-Rendering
    "kuml-renderer:kuml-layout-elk",     // Phase 1 — ELK-Adapter für die Layout-API ([[ADR-0006]])
    "kuml-renderer:kuml-themes",         // Phase 1 — Compose-Adapter-Theme-System (ADR-0006)
    "kuml-renderer:kuml-themes-core",    // Phase 1 — Framework-neutrale Theme-Daten (ADR-0006)
)

// ── Codegen ──────────────────────────────────────────── Phase 1 ──
include(
    "kuml-codegen:kuml-codegen-api",  // Phase 1 — Generator Plugin API
    "kuml-codegen:kuml-gen-kotlin",   // Phase 1 — Built-in Kotlin code generator
)

// ── I/O ──────────────────────────────────────────────── Phase 2 ──
include(
    "kuml-io:kuml-io-svg",          // Phase 2 — SVG/PNG-Export
    "kuml-io:kuml-io-png",          // Phase 1 — PNG-Export via Batik (Fat-JAR only)
    "kuml-io:kuml-io-json",         // Phase 2 — Modell-Persistenz (kotlinx.serialization)
    // "kuml-io:kuml-io-structurizr",  // V1.1 — C4 ⇌ Structurizr DSL
    // "kuml-io:kuml-io-emf",          // V2 — OPTIONAL XMI ⇌ Eclipse UML2
)

// ── LLM ──────────────────────────────────────────────── Phase 2 ──
include(
    "kuml-llm:kuml-llm-core",       // Phase 2 — LlmBackend Interface
    "kuml-llm:kuml-llm-anthropic",  // Phase 2 — Claude (Sonnet, Haiku, Opus)
    "kuml-llm:kuml-llm-spec",       // Phase 2 — llms.txt + JSON-Schema Generator
    "kuml-llm:kuml-llm-bench",      // Phase 2 — LLM-Benchmark-Suite
    // "kuml-llm:kuml-llm-openai",  // V1.1 — GPT-4o, o1
    // "kuml-llm:kuml-llm-ollama",  // V1.1 — Lokale Modelle
)

// ── MCP Server ───────────────────────────────────────── Phase 2 ──
include("kuml-mcp")                 // Phase 2 — MCP-Server (Ktor, Stdio + SSE)

// ── CLI ──────────────────────────────────────────────── Phase 2 ──
include("kuml-cli")                 // Phase 2 — Kommandozeilen-Interface

// ── Docs Integration ─────────────────────────────────── Phase 2 ──
include(
    "kuml-docs:kuml-markdown",      // Phase 2 — Markdown kuml-Codeblock → SVG
    // "kuml-docs:kuml-asciidoc",   // V1.1 — Asciidoctor Extension
)

// ── Packaging ────────────────────────────────────────── Phase 2 ──
include("kuml-packaging")           // Phase 2 — GraalVM Native, Homebrew, DEB/RPM

// ── Examples ─────────────────────────────────────────── Phase 2 ──
include("kuml-examples")            // Phase 2 — Vollständige Beispielprojekte

// ── Tests ────────────────────────────────────────────────────────
include(
    "kuml-tests:kuml-dsl-tests",        // Phase 1  — DSL Unit Tests (alle Diagrammtypen)
    "kuml-tests:kuml-ocl-tests",        // Phase 2  — OCL-Constraint-Validierung
    "kuml-tests:kuml-renderer-tests",   // Phase 1  — SVG Snapshot-Tests
    "kuml-tests:kuml-codegen-tests",    // Phase 2  — Kotlin/Java/SQL Generatoren
    "kuml-tests:kuml-mcp-tests",        // Phase 2  — MCP-Tool-Aufrufe
    "kuml-tests:kuml-cli-tests",        // Phase 2  — CLI end-to-end
    "kuml-tests:kuml-llm-tests",        // Phase 2  — LLM-Mock + @Tag("live")
)
