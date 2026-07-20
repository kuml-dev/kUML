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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        val jvmMain =
            getByName("jvmMain") {
                dependencies {
                    implementation(project(":kuml-core:kuml-core-script"))
                    // V3.6.4 — Knowledge Workspace viewer: OKF workspace scanner (transitively
                    // brings kuml-docs:kuml-markdown's KumlCodeBlock via its `api` dependency)
                    implementation(project(":kuml-docs:kuml-workspace"))
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
                    // P3, design review — PNG export (Export PNG… in the File menu)
                    implementation(project(":kuml-io:kuml-io-png"))
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
                    // V3.1.11 — Plugin update-badge: UpdateCheckService + UpdateCheckResult
                    implementation(project(":kuml-plugin-loader"))
                    // Koog agents runtime (für AIAgent / PromptExecutor)
                    implementation(libs.koog.agents.jvm)
                    // V3.1.13 — Plugin marketplace screenshots (Coil 3 async image + disk cache)
                    implementation(libs.coil.compose)
                    implementation(libs.coil.network.ktor)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.java)
                    // V3.6.4 — Knowledge Workspace viewer: read-only Markdown rendering (M3)
                    implementation(libs.markdown.renderer.m3)
                }
            }
        val jvmTest =
            getByName("jvmTest") {
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

                // V3.2.25 — Developer ID signing (local, gated on KUML_SIGN_IDENTITY).
                //
                // Verified against the actual Compose Multiplatform 1.11.1 Gradle
                // plugin classes (org.jetbrains.compose.desktop.application.dsl.
                // MacOSSigningSettings / MacOSNotarizationSettings, decompiled via
                // javap): `signing { sign, identity, keychain, prefix }` exists and
                // works as expected for hardened-runtime signing. `notarization {}`
                // on this Compose version only exposes `appleID` / `password` /
                // `teamID` / `ascProvider` (the legacy Apple-ID + app-specific-
                // password flow) — it has NO support for notarytool keychain
                // profiles or App Store Connect API keys, which is the credential
                // path already proven on this machine (`kuml-notary` profile).
                //
                // Resolution (per the V3.2.25 plan's documented fallback): use
                // Compose only for signing; run notarization + stapling as a
                // separate external step via
                // kuml-packaging/scripts/notarize-and-staple.sh after packageDmg
                // produces the DMG, rather than fighting a DSL that can't express
                // the credential flow we use.
                val signIdentity = providers.environmentVariable("KUML_SIGN_IDENTITY").orNull
                if (signIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(signIdentity)
                        // Only needed in CI with an ephemeral keychain (V3.2.27).
                        providers.environmentVariable("KUML_SIGN_KEYCHAIN").orNull?.let {
                            keychain.set(it)
                        }
                    }
                }
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
