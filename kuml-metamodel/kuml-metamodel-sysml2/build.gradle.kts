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
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuml-metamodel:kuml-metamodel-kerml"))
            implementation(project(":kuml-core:kuml-core-model"))
            // V2.0.20b — constraint type-checking uses the typed expression AST
            implementation(project(":kuml-core:kuml-core-expr"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
