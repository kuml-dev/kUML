plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.assertions.core)
        }
        val jvmMain =
            getByName("jvmMain") {
                dependencies {
                    api(project(":kuml-runtime:kuml-runtime-core"))
                    api(project(":kuml-metamodel:kuml-metamodel-uml"))
                    api(project(":kuml-metamodel:kuml-metamodel-sysml2"))
                    implementation(project(":kuml-io:kuml-io-svg"))
                    implementation(project(":kuml-renderer:kuml-layout-api"))
                    implementation(project(":kuml-renderer:kuml-layout-bridge"))
                    implementation(project(":kuml-renderer:kuml-layout-elk"))
                    implementation(project(":kuml-renderer:kuml-themes-core"))
                    implementation(project(":kuml-core:kuml-core-script"))
                    implementation(project(":kuml-core:kuml-core-ocl"))
                    implementation(libs.batik.swing)
                    implementation(libs.batik.codec)
                    implementation(libs.batik.transcoder)
                    implementation(compose.desktop.currentOs)
                    implementation(libs.kotlinx.serialization.json)
                    implementation(libs.kotlinx.coroutines.core)
                }
            }
        val jvmTest =
            getByName("jvmTest") {
                dependencies {
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    // runComposeUiTest + onNode* matchers (desktop actual); driven with
                    // kotest, not the JUnit4 rule, so no test-framework change.
                    implementation(libs.compose.ui.test.junit4)
                }
            }
    }
}

compose.desktop {
    application {
        mainClass = "dev.kuml.widget.compose.demo.BehaviourWidgetDemoKt"
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Off-screen Skia scene for Compose UI tests — no display needed, because
    // only ControlPanel/OclGuardEditor (no SwingPanel/Batik) are hosted in tests.
    systemProperty("java.awt.headless", "true")
}
