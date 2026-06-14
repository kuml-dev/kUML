plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
        }
        val jvmMain by getting {
            dependencies {
                implementation(project(":kuml-core:kuml-core-script"))
                implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
                implementation(project(":kuml-metamodel:kuml-metamodel-c4"))
                implementation(project(":kuml-metamodel:kuml-metamodel-sysml2"))
                implementation(project(":kuml-renderer:kuml-layout-api"))
                implementation(project(":kuml-renderer:kuml-layout-bridge"))
                implementation(project(":kuml-renderer:kuml-layout-elk"))
                implementation(project(":kuml-renderer:kuml-layout-grid"))
                implementation(project(":kuml-renderer:kuml-themes"))
                implementation(project(":kuml-renderer:kuml-themes-core"))
                implementation(project(":kuml-io:kuml-io-svg"))
                implementation(libs.batik.swing)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlin.scripting.common)
                implementation(libs.kotlin.scripting.jvm)
                implementation(libs.kotlin.scripting.jvm.host)
                implementation(libs.kotlin.reflect)
                implementation("com.fifesoft:rsyntaxtextarea:3.5.3")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.kuml.desktop.MainKt"
        nativeDistributions {
            packageName = "kUML Desktop"
            packageVersion = "3.0.0"
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
