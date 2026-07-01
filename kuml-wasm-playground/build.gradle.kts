plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// V3.2.9 — Scaffolding module for a browser-hosted kUML renderer.
//
// HONEST SCOPE (see CLAUDE.md kUML section "V3.2.9 Plan"): this module exposes
// ONLY the render step (KumlDiagram + LayoutResult -> SVG), which genuinely
// compiles and runs on Kotlin/Wasm today. It does NOT expose `.kuml.kts` DSL
// parsing (kuml-core-script is JVM-only, Kotlin scripting has no wasm backend)
// and it does NOT expose the ELK layout engine (kuml-layout-elk is JVM-only,
// wraps the Java library org.eclipse.elk). The `wasmJs` actual for
// LayoutEngineRegistry.loadProvidersFromClasspath() returns an empty list —
// there is currently no registered layout engine that runs in wasm.
//
// This module therefore builds a small, hand-rolled example diagram + a
// trivial grid layout directly in Kotlin (commonMain / wasmJsMain), rather
// than accepting arbitrary `.kuml.kts` source or arbitrary JSON diagram
// payloads. `KumlDiagram`/`KumlElement` in kuml-core-model are NOT
// `@Serializable` today, so a generic "post me a diagram as JSON" entry
// point is not available either without further work (tracked as V3.2.10
// follow-up, alongside porting a layout engine to commonMain/wasmJs).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(21)
    explicitApi()

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":kuml-io:kuml-io-svg"))
            implementation(project(":kuml-renderer:kuml-layout-api"))
            implementation(project(":kuml-renderer:kuml-themes-core"))
            implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
            implementation(project(":kuml-core:kuml-core-model"))
        }
    }
}
