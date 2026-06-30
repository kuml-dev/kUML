plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    jvm()
    // Wasm/iOS targets für V1.1+ — V1 baut nur JVM
    // (Kotlin/Native-Bundle würde > 1 GB ziehen, ohne Mehrwert für CLI/SVG)
    // wasmJs { browser() }
    // iosArm64(); iosX64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kuml-renderer:kuml-themes"))
            implementation(libs.kuiver)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
        }

        // JVM-specific: depends on metamodel + layout-api (JVM-only modules)
        val jvmMain = getByName("jvmMain") {
            dependencies {
                api(project(":kuml-renderer:kuml-layout-api"))
                api(project(":kuml-metamodel:kuml-metamodel-uml"))
                api(project(":kuml-metamodel:kuml-metamodel-c4"))
                implementation(project(":kuml-core:kuml-core-model"))
            }
        }
        val jvmTest = getByName("jvmTest") {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(project(":kuml-renderer:kuml-layout-api"))
                implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
                implementation(project(":kuml-metamodel:kuml-metamodel-c4"))
            }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
