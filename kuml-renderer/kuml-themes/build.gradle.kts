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
            api(project(":kuml-renderer:kuml-themes-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
        }
        val jvmTest =
            getByName("jvmTest") {
                dependencies {
                    implementation(libs.kotest.runner.junit5)
                }
            }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
