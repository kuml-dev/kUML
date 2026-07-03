plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.atomicfu)
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
            // Kotlin K2 (2.4.0+) is strict about implementation visibility: DSL entry-points
            // like `umlModel{}` / `c4Model{}` return types from these metamodels, so they
            // must be on the compile classpath of downstream modules — hence `api`, not
            // `implementation`. Same goes for the profile API surface.
            api(project(":kuml-core:kuml-core-model"))
            api(project(":kuml-metamodel:kuml-metamodel-uml"))
            api(project(":kuml-metamodel:kuml-metamodel-c4"))
            api(project(":kuml-profile:kuml-profile-api"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
