plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// V3.2.9/V3.2.10 — Scaffolding module for a browser-hosted kUML renderer.
//
// HONEST SCOPE (see CLAUDE.md kUML section "V3.2.10 Plan"): this module exposes
// the render step (KumlDiagram + LayoutResult -> SVG), which genuinely
// compiles and runs on Kotlin/Wasm today. It does NOT expose `.kuml.kts` DSL
// parsing (kuml-core-script is JVM-only, Kotlin scripting has no wasm backend)
// and it does NOT expose the ELK layout engine (kuml-layout-elk is JVM-only,
// wraps the Java library org.eclipse.elk). The `wasmJs` actual for
// LayoutEngineRegistry.loadProvidersFromClasspath() returns an empty list —
// there is currently no registered layout engine that runs in wasm.
//
// As of V3.2.10, `KumlDiagram` is `@Serializable` and the open `KumlElement` /
// `KumlNamespaceMember` bases are `@Polymorphic`, with the UML metamodel's
// concrete subtypes registered via `dev.kuml.uml.UmlSerializersModule`. This
// module therefore accepts an arbitrary UML `KumlDiagram` as JSON
// (`renderDiagramJson`, `renderDiagramJsonWithGrid`) in addition to the
// original hand-rolled sample diagram (`renderSampleClassDiagram`). Layout is
// still supplied by the caller as JSON, or computed with a demo-only single
// row grid — there is still no real layout engine in wasm. C4/BPMN/SysML2/
// KerML/Blueprint diagrams are not decodable yet; registering their
// SerializersModules is the natural V3.2.11 follow-up.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(21)
    explicitApi()

    wasmJs {
        browser()
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":kuml-io:kuml-io-svg"))
            implementation(project(":kuml-renderer:kuml-layout-api"))
            implementation(project(":kuml-renderer:kuml-themes-core"))
            implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
            implementation(project(":kuml-core:kuml-core-model"))
            implementation(libs.kotlinx.serialization.json)
        }
        wasmJsTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
