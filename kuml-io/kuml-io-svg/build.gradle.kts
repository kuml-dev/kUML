plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()
    js {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuml-renderer:kuml-layout-api"))
            api(project(":kuml-renderer:kuml-layout-bridge")) // IBD port enrichment (V3.1.x)
            api(project(":kuml-renderer:kuml-themes-core"))
            api(project(":kuml-metamodel:kuml-metamodel-uml"))
            api(project(":kuml-metamodel:kuml-metamodel-c4"))
            api(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.4 — SysML 2 BDD-Rendering
            api(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.3 — BPMN Process SVG-Renderer
            api(project(":kuml-metamodel:kuml-metamodel-blueprint")) // V3.1.23 — Blueprint-Rendering
            implementation(project(":kuml-core:kuml-core-model"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // V3.1.30 — BPMN SMIL animation + TraceFile for BPMN animation.
            // JVM-only: reachable exclusively from the `**/smil/**` subpackages
            // (moved to jvmMain in the V3.2.8/9 KMP split) plus the 13
            // `toSvgFile(...)` file-writing overloads in KumlSvgRenderer.jvm.kt.
            // Deferred from KMP conversion — see CLAUDE.md kUML section for
            // rationale (java.time.Instant/MessageDigest/ConcurrentHashMap/File
            // usage in kuml-runtime-core is unreachable from the wasmJs render
            // path).
            api(project(":kuml-io:kuml-render-smil"))
            api(project(":kuml-runtime:kuml-runtime-core"))
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            // V1.1: stereotype render tests use KumlStereotypeApplication from kuml-profile-api
            implementation(project(":kuml-profile:kuml-profile-api"))
            // PNG co-generation in SampleOutput: converts each SVG sample to PNG for visual regression
            implementation(project(":kuml-io:kuml-io-png"))
            // V3.1.36 — ArxmlComponentRenderTest: render an imported ARXML composition as SVG
            implementation(project(":kuml-io:kuml-io-arxml"))
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
