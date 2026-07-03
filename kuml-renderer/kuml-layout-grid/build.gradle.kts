plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// V0.23.3 — Grid-Layout-Engine ist jetzt multiplatform (common/jvm/js/wasmJs).
// Die Engine hängt ausschließlich von :kuml-renderer:kuml-layout-api ab (selbst
// multiplatform) und ist reines Kotlin (kein ELK/EMF/Xtext, kein java.*) — damit
// GraalVM-Native-Image-tauglich UND Kotlin/Wasm-tauglich. Das entkoppelt den
// wasm-Playground von der bisherigen Einzeilen-Demo-Grid-Notlösung und gibt ihm
// ein echtes mehrspaltiges Layout mit inhaltsbasierten Knotengrößen.
kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()
    js {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuml-renderer:kuml-layout-api"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
