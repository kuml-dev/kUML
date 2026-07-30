plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// V3.2.9/V3.2.10/V0.23.3 — Browser-hosted kUML renderer (Kotlin/Wasm).
//
// HONEST SCOPE (see WasmPlayground.kt KDoc for the authoritative, per-function
// breakdown): this module exposes the render step (Diagram + LayoutResult ->
// SVG), which genuinely compiles and runs on Kotlin/Wasm today. It does NOT
// expose `.kuml.kts` DSL parsing (kuml-core-script is JVM-only, Kotlin
// scripting has no wasm backend) and it does NOT expose the ELK layout engine
// (kuml-layout-elk is JVM-only, wraps the Java library org.eclipse.elk).
//
// V0.23.3 widened the decode scope beyond UML: C4, SysML 2, BPMN and Blueprint
// diagrams are now decodable and renderable in wasm (their models/diagrams are
// `sealed` `@Serializable` hierarchies that round-trip through plain kotlinx
// Json — no custom SerializersModule needed, unlike UML's open `KumlElement`
// base which still needs `UmlSerializersModule`). KerML is intentionally still
// out of scope: no `KumlSvgRenderer` overload exists for it.
//
// V0.23.3 also made `kuml-layout-grid` multiplatform, so the pure-Kotlin
// `GridLayoutEngine` now runs in wasm. `renderDiagramJsonWithGrid` uses it via
// the multiplatform `UmlLayoutBridge` for a real multi-column, content-sized
// grid layout (replacing the old single-row demo scaffold). For C4/SysML2/BPMN
// the caller still supplies a precomputed `LayoutResult` (typically from the
// JVM `kuml dump-json` command), because those diagram types are laid out with
// ELK on the JVM and grid is a weaker fallback for them.
// ── Detekt gate: intentionally EXCLUDED ──────────────────────────────────────
// kuml-wasm-playground has no jvm() target, so the detekt Gradle plugin registers
// no type-resolution compilation task for it (verified: only jvm/androidJvm
// compilations get a detekt<Compilation>-with-classpath task). The
// RequireNamedArguments rule is `RequiresAnalysisApi` and is therefore SILENTLY
// SKIPPED here — it would report zero findings and go green without inspecting a
// line, which is exactly the failure mode the ktlint incident of 2026-07-17
// produced (see root build.gradle.kts). This module is listed in
// `kumlDetektExemptModules` in the root build script so `verifyDetektCoverage`
// fails if a NEW module ever ends up unanalysed by accident. Its source files
// are named-argument-clean and must be kept so by review; if the module ever
// grows a jvm() target, remove this exemption.
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
            // kuml-io-svg transitively api-exposes the layout-bridge and all
            // metamodels (uml/c4/sysml2/bpmn/blueprint), so no separate
            // metamodel deps are needed here.
            implementation(project(":kuml-io:kuml-io-svg"))
            implementation(project(":kuml-renderer:kuml-layout-api"))
            implementation(project(":kuml-renderer:kuml-layout-bridge"))
            implementation(project(":kuml-renderer:kuml-layout-grid")) // V0.23.3 — real wasm grid layout
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
