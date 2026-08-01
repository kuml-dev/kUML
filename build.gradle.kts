import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    // `base` gives the root project its own lifecycle `check` task (it has no
    // Kotlin plugin of its own) so `verifyDetektCoverage` below can hang off
    // it. Gradle's by-name task matching still runs every subproject's own
    // `check` exactly as before — this only adds one more (root-level) check.
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.detekt) apply false
}

// Force a single, consistent kotlinx-serialization-core across the whole
// plugin/buildscript classpath. Without this, adding `dev.detekt` anywhere in
// this build (even just declaring it here with `apply false`) makes
// `org.jetbrains.intellij.platform.gradle` (applied only in
// kuml-jetbrains-plugin) resolve a DIFFERENT kotlinx-serialization-core than
// the one its own precompiled `$$serializer` classes expect, crashing even
// plain `compileKotlin` there with:
//   AbstractMethodError: GeneratedSerializer.typeParametersSerializers()
// Forcing one version for the shared plugin classpath resolves the conflict.
// Verified 2026-07-30.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.kotlinx.serialization.get()}")
            force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${libs.versions.kotlinx.serialization.get()}")
            force("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinx.serialization.get()}")
            force("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:${libs.versions.kotlinx.serialization.get()}")
        }
    }
}

allprojects {
    group = "dev.kuml"
    version = "0.46.0"
}

// Kotlin modules that provably cannot be covered by the RequireNamedArguments
// gate. Every entry needs a written justification in the module's own build
// script. Adding to this set is a review-gated decision. Two distinct reasons
// are represented here:
//  - kuml-wasm-playground: no JVM compilation → no type resolution →
//    RequiresAnalysisApi rule would be silently skipped (see its build script).
//  - kuml-jetbrains-plugin: applying `dev.detekt` to this project crashes even
//    plain `compileKotlin` — a real Gradle-plugin-classloader clash between
//    `dev.detekt` 2.0.0-alpha.5 and `org.jetbrains.intellij.platform.gradle`
//    (both load a different `kotlinx-serialization-core` into the same
//    buildscript classloader; IntelliJPlatformDependenciesHelper then hits
//    `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()`).
//    Verified 2026-07-30: reproduces on master-plus-this-wiring with a bare
//    `:kuml-jetbrains:kuml-jetbrains-plugin:compileKotlin`, and disappears the
//    moment `dev.detekt` is not applied to that project. Not a "skip
//    analysis" situation like wasm — the plugin must not even be *applied*
//    here. See the module's own build script for the full note.
val kumlDetektExemptModules = setOf(":kuml-wasm-playground", ":kuml-jetbrains:kuml-jetbrains-plugin")

// Apply ktlint to all subprojects that use a Kotlin plugin — JVM-only or
// Multiplatform. Before this fix only "org.jetbrains.kotlin.jvm" was hooked,
// so the ~19 kotlin.multiplatform modules (kuml-io-svg, kuml-layout-bridge,
// kuml-kuiver, kuml-desktop, kuml-core-*, kuml-metamodel-*, etc.) silently
// had no ktlint task at all and `check` never linted them — discovered
// 2026-07-17 while fixing the UML-abstract-rendering bug.
//
// Detekt (custom ruleset :kuml-detekt-rules, rule kuml/RequireNamedArguments)
// is hooked from the SAME two plugin ids for exactly the same reason. Detekt
// additionally has its own, sneakier version of that same trap: it registers
// BOTH type-resolution tasks (detektMain / detektMainJvm, which get a
// `classpath` and can run Analysis-API rules) AND non-type-resolution tasks
// (detekt, detekt<SourceSet>SourceSet, which get NO classpath). Only the plain
// `detekt` task is wired into `check` by the plugin itself — and a rule marked
// `RequiresAnalysisApi` is SILENTLY SKIPPED by it, reporting zero findings and
// going green. We therefore (a) wire the type-resolution tasks into `check`
// ourselves, (b) disable the non-TR tasks so they cannot produce a false green,
// and (c) install `verifyDetektCoverage` as a hard guard that every Kotlin
// module actually has a TR task in its `check` graph.
subprojects {
    // The ruleset module itself must not be analysed by the ruleset it builds
    // (bootstrap cycle: detekt would need :kuml-detekt-rules:jar to lint
    // :kuml-detekt-rules). ktlint still applies to it.
    val isDetektRulesModule = path == ":kuml-detekt-rules"

    fun applyKumlLinters() {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        if (isDetektRulesModule) return
        // kuml-jetbrains-plugin: do not even apply the `dev.detekt` plugin here —
        // see the justification comment on kumlDetektExemptModules above.
        if (path in kumlDetektExemptModules) return

        apply(plugin = "dev.detekt")

        extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig = false // built-in rulesets stay off — see detekt.yml header
            ignoreFailures = false
            autoCorrect =
                providers.gradleProperty("kuml.detekt.autoCorrect")
                    .getOrElse("false")
                    .toBoolean()
        }

        dependencies.add("detektPlugins", project(":kuml-detekt-rules"))

        // ── (b) neutralise the no-type-resolution tasks ──────────────────────
        // `detekt`, `detektMainSourceSet`, `detektCommonMainSourceSet`,
        // `detektWasmJsMainSourceSet`, … all run WITHOUT a classpath. A
        // RequiresAnalysisApi rule is skipped there, so they always pass and
        // would make `check` look green for modules the gate never inspected.
        tasks.withType(dev.detekt.gradle.Detekt::class.java).configureEach {
            val hasTypeResolution = name.startsWith("detektMain") || name.startsWith("detektTest")
            if (!hasTypeResolution) {
                enabled = false
            }
        }

        // ── (a) wire the surviving type-resolution tasks into `check` ────────
        tasks.named("check").configure {
            dependsOn(
                tasks.withType(dev.detekt.gradle.Detekt::class.java).matching { it.enabled },
            )
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { applyKumlLinters() }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { applyKumlLinters() }
}

// Structural guard against the 2026-07-17 class of bug: a Kotlin module that
// quietly has no working lint gate. Fails the build at *configuration-check*
// time — not by producing zero findings — if any Kotlin subproject ends up
// without a type-resolution detekt task in its `check` graph and is not on the
// documented exemption list.
tasks.register("verifyDetektCoverage") {
    group = "verification"
    description = "Fails if any Kotlin subproject lacks a type-resolution detekt task wired into check."

    val offenders =
        subprojects.filter { sp ->
            val isKotlin =
                sp.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
                    sp.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
            if (!isKotlin) return@filter false
            if (sp.path == ":kuml-detekt-rules") return@filter false
            if (sp.path in kumlDetektExemptModules) return@filter false
            sp.tasks.withType(dev.detekt.gradle.Detekt::class.java)
                .none { it.enabled && (it.name.startsWith("detektMain") || it.name.startsWith("detektTest")) }
        }.map { it.path }

    doLast {
        check(offenders.isEmpty()) {
            "No type-resolution detekt task for: ${offenders.joinToString()}. " +
                "Either give the module a jvm() target, or add it to kumlDetektExemptModules " +
                "in the root build.gradle.kts WITH a written justification in the module's own " +
                "build script (see kuml-wasm-playground)."
        }
    }
}

tasks.named("check") { dependsOn("verifyDetektCoverage") }

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
        "kuml-detekt-rules",   // build tooling — custom Detekt ruleset, never published
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
        // V1.1+ tooling-side artefacts published through additional channels
        // beyond Maven Central. kuml-gradle-plugin is published to BOTH Maven
        // Central (via the GradlePlugin(...) branch below, reusing
        // java-gradle-plugin's pluginMaven + marker publications) AND the
        // Gradle Plugin Portal (via com.gradle.plugin-publish, see
        // kuml-gradle-plugin/build.gradle.kts) — so it is intentionally NOT
        // listed here. kuml-jetbrains-plugin is published ONLY via JetBrains
        // Marketplace, never to Maven Central (IntelliJ platform deps aren't
        // meaningful outside a plugin.xml-driven classloader).
        "kuml-jetbrains-plugin",
        // Wave 1 — shared editor brain (completion/rename/diagnostics/CLI locator),
        // rides the monorepo version but has no independent release cadence yet.
        "kuml-lang-support",
        // Wave 2 — editor-agnostic LSP server application (kuml-lsp launcher,
        // ships as distZip like kuml-web/kuml-cli, never a Maven artifact).
        "kuml-language-server",
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

// Marker extra-property used by the idempotency guard in
// configureKumlPublishing() below.
val kumlPublishingConfiguredMarker = "kuml.publishingConfigured"

// Shared publication config (coordinates, POM, signing, Central Portal
// upload) applied to every published module regardless of whether it's a
// plain Kotlin/JVM module or a Kotlin Multiplatform (KMP) module. The
// per-platform bit (KotlinJvm(...) vs KotlinMultiplatform(...)) is configured
// separately by each `pluginManager.withPlugin` branch below, because
// vanniktech's `configure(...)` call differs by module shape.
fun Project.configureKumlPublishing() {
    // Structural safeguard: this function must run at most once per project.
    // The java-gradle-plugin vs org.jetbrains.kotlin.jvm dispatch below is
    // order-dependent on the subproject's own `plugins {}` block —
    // `pluginManager.hasPlugin("java-gradle-plugin")` only sees the correct
    // answer inside the kotlin.jvm branch's guard if java-gradle-plugin was
    // declared FIRST (see the ordering comment in
    // kuml-gradle/kuml-gradle-plugin/build.gradle.kts). Get that order wrong
    // in a future module and both branches fire, which — without this check —
    // fails downstream with vanniktech's opaque "The value for this property
    // is final and cannot be changed any further". Fail fast here instead,
    // with a message that points at the actual root cause.
    check(!extra.has(kumlPublishingConfiguredMarker)) {
        "configureKumlPublishing() was called twice for project '$path'. This usually means " +
            "the subproject applies both `java-gradle-plugin` and `org.jetbrains.kotlin.jvm` " +
            "but declares `java-gradle-plugin` AFTER `kotlin.jvm` in its plugins {} block — " +
            "java-gradle-plugin must come first so the root build.gradle.kts dispatch guard " +
            "sees it in time. See the ordering comment in " +
            "kuml-gradle/kuml-gradle-plugin/build.gradle.kts for the reference example."
    }
    extra[kumlPublishingConfiguredMarker] = true

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

        // Only wire up GPG signing when a signing key is actually configured
        // (CI secrets, see docs/release.md, or a maintainer's own
        // ~/.gradle/gradle.properties). Without this guard,
        // signAllPublications() unconditionally registers a `sign*` task with
        // no signatory for every publication, which makes the
        // "Dry-runs (no credentials needed)" `./gradlew publishToMavenLocal`
        // workflow documented in docs/release.md fail for every published
        // module — including kuml-gradle-plugin, whose local
        // publishToMavenLocal verification this repo's release workflow
        // relies on before wiring up Gradle Plugin Portal publishing.
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }

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
        // kuml-gradle-plugin applies BOTH org.jetbrains.kotlin.jvm AND
        // java-gradle-plugin — it gets its own GradlePlugin(...) publication
        // branch below, so skip the generic KotlinJvm(...) branch here to
        // avoid two competing "maven"-style publications in the same project.
        if (pluginManager.hasPlugin("java-gradle-plugin")) return@withPlugin
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

    // Gradle-plugin modules (currently only kuml-gradle-plugin): reuse the
    // pluginMaven + marker publications that java-gradle-plugin + maven-publish
    // already create, instead of creating a duplicate "maven" publication.
    pluginManager.withPlugin("java-gradle-plugin") {
        configureKumlPublishing()
        configure<MavenPublishBaseExtension> {
            configure(
                GradlePlugin(
                    // None(), not Empty()/Sources(): com.gradle.plugin-publish
                    // (applied in kuml-gradle-plugin/build.gradle.kts) already
                    // calls java.withJavadocJar()/withSourcesJar() and wires
                    // those real jars into the "pluginMaven" publication that
                    // java-gradle-plugin creates. Asking vanniktech to attach
                    // its own javadoc/sources jars on top produced two
                    // conflicting artifacts with the same ('jar', 'javadoc')
                    // classifier and broke `publishToMavenLocal` /
                    // `publishToMavenCentral` outright ("Invalid publication
                    // 'pluginMaven': multiple artifacts with the identical
                    // extension and classifier") — which in turn broke the
                    // local dry-run documented in docs/release.md.
                    javadocJar = JavadocJar.None(),
                    sourcesJar = SourcesJar.None(),
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
