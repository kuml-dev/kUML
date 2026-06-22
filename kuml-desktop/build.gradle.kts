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
                implementation(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN desktop render
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
                // V3.0.24 — AI panel integration
                implementation(project(":kuml-ai:kuml-ai-core"))
                implementation(project(":kuml-ai:kuml-ai-tools"))
                // V3.0.25 — DSL serializer (UmlModelDslPrinter)
                implementation(project(":kuml-cli"))
                // V3.0.13 — Plugin Manager: explizite Dependency für TransformerRegistry
                implementation(project(":kuml-codegen:kuml-codegen-m2m"))
                // Koog agents runtime (für AIAgent / PromptExecutor)
                implementation(libs.koog.agents.jvm)
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

// V3.0.14 — Konvertiert kUML-Versionen (0.x.y) in jpackage-kompatibles Format (x.y.0).
// jpackage / macOS CFBundleShortVersionString verlangen Major-Segment ≥ 1.
fun versionForJpackage(projectVersion: String): String =
    if (projectVersion.startsWith("0.")) {
        val rest = projectVersion.removePrefix("0.")
        val parts = rest.split(".")
        val minor = parts.getOrElse(0) { "0" }
        val patch = parts.getOrElse(1) { "0" }
        "$minor.$patch.0"
    } else {
        projectVersion
    }

compose.desktop {
    application {
        mainClass = "dev.kuml.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
            )
            packageName = "kuml-desktop"
            packageVersion = versionForJpackage(project.version.toString())
            description = "kUML Desktop — UML/SysML2 modelling with AI"
            copyright = "© 2024-2026 kUML contributors"
            vendor = "kuml.dev"
            licenseFile.set(rootProject.file("LICENSE"))

            macOS {
                bundleID = "dev.kuml.desktop"
                // Icon nur setzen wenn vorhanden — Build schlägt sonst mit Missing-File-Fehler fehl
                val macIcon = file("src/jvmMain/resources/icons/kuml-desktop.icns")
                if (macIcon.exists()) iconFile.set(macIcon)
            }
            windows {
                val winIcon = file("src/jvmMain/resources/icons/kuml-desktop.ico")
                if (winIcon.exists()) iconFile.set(winIcon)
                menuGroup = "kUML"
                upgradeUuid = "C4F2B3D1-A1E5-4B8C-9D7F-6E3A2C8B4F1E"
            }
            linux {
                val linuxIcon = file("src/jvmMain/resources/icons/kuml-desktop.png")
                if (linuxIcon.exists()) iconFile.set(linuxIcon)
                menuGroup = "Development"
            }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
