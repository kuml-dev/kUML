import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = "dev.kuml"
    version = "0.24.0"
}

// Apply ktlint to all subprojects that use the Kotlin JVM plugin.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Maven Central publishing — applied to library modules only.
//
// Executable application modules (kuml-cli, kuml-mcp, kuml-llm-bench) ship as
// distZip / Homebrew tarballs and are NOT published as Maven artefacts.
//
// Required environment / Gradle properties for a release:
//   ORG_GRADLE_PROJECT_mavenCentralUsername
//   ORG_GRADLE_PROJECT_mavenCentralPassword
//   ORG_GRADLE_PROJECT_signingInMemoryKey
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
// See docs/release.md for the full setup walkthrough.
// ─────────────────────────────────────────────────────────────────────────────

// Modules that are intentionally NOT published to Maven Central. Matched
// against `Project.name` (the leaf name), not the colon-path — so to keep
// a sub-module out of the publication set its leaf name must appear here.
//
// Test sub-modules (`:kuml-tests:kuml-mcp-tests`, etc.) were silently being
// included in releases through v0.3.0 because their leaf names were not
// listed; this caused the Central Portal's component validator to reject
// the whole deployment as a duplicate against the already-published v0.2.0
// JARs. Listing each test leaf name fixes that.
val nonPublishedModules =
    setOf(
        "kUML",
        "kuml-cli",
        "kuml-mcp",
        "kuml-llm-bench",
        "kuml-tests",
        "kuml-examples",
        "kuml-packaging",
        "kuml-web",
        "kuml-desktop",        // V3.0.10 — Compose Desktop app (not published to Maven Central)
        // Test sub-modules (path-aware listing — Gradle subprojects iteration
        // sees them as separate projects with these leaf names).
        "kuml-cli-tests",
        "kuml-codegen-tests",
        "kuml-dsl-tests",
        "kuml-llm-tests",
        "kuml-mcp-tests",
        "kuml-ocl-tests",
        "kuml-renderer-tests",
        "kuml-vault-examples-tests",  // V3.0.x — CI render smoke tests (not published)
        // V1.1+ tooling-side artefacts published through other channels
        // (Gradle Plugin Portal, JetBrains Marketplace, VS Code Marketplace).
        "kuml-gradle-plugin",
        "kuml-jetbrains-plugin",
        // Aggregator parent projects with no JAR of their own — leaf names.
        "kuml-gradle",
        "kuml-jetbrains",
        "kuml-renderer",
        "kuml-runtime",
        "kuml-docs",
        "kuml-codegen",
        "kuml-core",
        "kuml-io",
        "kuml-llm",
        "kuml-metamodel",
        "kuml-profile",
        "kuml-ai",             // V3.0.22 — AI aggregator parent (kuml-ai-core is published separately)
        "kuml-plugin-api",     // V3.0.27 — Plugin API aggregator parent (sub-modules are published separately)
    )

// Shared publication config (coordinates, POM, signing, Central Portal
// upload) applied to every published module regardless of whether it's a
// plain Kotlin/JVM module or a Kotlin Multiplatform (KMP) module. The
// per-platform bit (KotlinJvm(...) vs KotlinMultiplatform(...)) is configured
// separately by each `pluginManager.withPlugin` branch below, because
// vanniktech's `configure(...)` call differs by module shape.
fun Project.configureKumlPublishing() {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        // automaticRelease = true → vanniktech uploads to the Central
        // Portal staging and immediately publishes it. Without this flag
        // (which we had through v0.3.0), the staging deployment sits at
        // VALIDATED indefinitely until a maintainer clicks "Publish" in
        // https://central.sonatype.com/publishing/deployments. That's how
        // v0.3.0's JARs never reached Maven Central even though the
        // release workflow reported success.
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        coordinates(
            groupId = "dev.kuml",
            artifactId = project.name,
            version = project.version.toString(),
        )

        pom {
            name.set(project.name)
            description.set("kUML — a Kotlin-DSL approach to UML 2.x and C4 modelling. Module: ${project.name}")
            url.set("https://github.com/kuml-dev/kuml")

            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }

            developers {
                developer {
                    id.set("ibetchvaia")
                    name.set("Irakli Betchvaia")
                    email.set("ibetchvaia@gmail.com")
                }
            }

            scm {
                url.set("https://github.com/kuml-dev/kuml")
                connection.set("scm:git:https://github.com/kuml-dev/kuml.git")
                developerConnection.set("scm:git:ssh://git@github.com/kuml-dev/kuml.git")
            }
        }
    }
}

subprojects {
    if (name in nonPublishedModules) return@subprojects

    // Plain Kotlin/JVM modules (the majority — kuml-io-*, kuml-metamodel-*
    // aggregators' leaf modules, codegen, etc.).
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configureKumlPublishing()
        configure<MavenPublishBaseExtension> {
            configure(
                KotlinJvm(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = SourcesJar.Sources(),
                ),
            )
        }
    }

    // Kotlin Multiplatform (KMP) modules — V3.2.6 converted kuml-core-model,
    // kuml-core-dsl, kuml-metamodel-uml, kuml-metamodel-c4 and
    // kuml-profile-api from `kotlin.jvm` to `kotlin.multiplatform` with
    // jvm()/js()/wasmJs() targets. These modules do NOT apply
    // `org.jetbrains.kotlin.jvm`, so without this branch they silently fell
    // out of the publication set entirely (V3.2.7 fix — see CLAUDE.md /
    // ADR-0012). vanniktech auto-detects a per-target publication set
    // (root metadata module + `-jvm`/`-js`/`-wasm-js` legs); sources jars are
    // produced automatically per target under KMP, so JavadocJar is the only
    // knob needed here.
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        configureKumlPublishing()
        configure<MavenPublishBaseExtension> {
            configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
        }
    }
}
