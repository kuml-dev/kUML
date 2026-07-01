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
            api(project(":kuml-metamodel:kuml-metamodel-uml"))
            api(project(":kuml-metamodel:kuml-metamodel-c4"))
            api(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.4 — SysML 2 BDD-Bridge
            api(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.3 — BPMN Layout Bridge
            api(project(":kuml-metamodel:kuml-metamodel-blueprint")) // V3.1.23 — Blueprint grid layout
            implementation(project(":kuml-core:kuml-core-model"))
            implementation(libs.kotlinx.serialization.json)
            // Layout-Keys aus dem DSL-Modul werden NICHT verlinkt — String-Literale duplizieren wir lokal
            // in BridgeLayoutKeys (see spec: „Vertrag in Worten" #6)
        }
        jvmMain.dependencies {
            // Reflection für LayoutHintWriter.copyWithMetadata — data-class copy() via KFunction.callBy
            // JVM-only: LayoutHintWriter.kt lives in jvmMain (editor round-trip feature,
            // not on the wasmJs SVG render path).
            implementation(libs.kotlin.reflect)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.serialization.json)
            // V2.0.26 — LayoutEngineSelectionTest braucht beide Engine-Provider
            implementation(project(":kuml-renderer:kuml-layout-grid"))
            implementation(project(":kuml-renderer:kuml-layout-elk"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
