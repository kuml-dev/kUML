plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

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
            api(project(":kuml-core:kuml-core-model"))
            // V3.2.23 — BpmnProcess.constraints reuses UmlConstraint (OCL invariant body)
            // so kuml-core-ocl can validate BPMN processes with the same constraint model
            // as UML classifiers, without duplicating the constraint-kind/body shape.
            api(project(":kuml-metamodel:kuml-metamodel-uml"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
