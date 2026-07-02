import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("dev.kuml.cli.MainKt")
    applicationName = "kuml"
    // Cold-start tuning: Kotlin Scripting's embedded compiler dominates `kuml render`
    // startup (~4-5s on JDK 21). TieredStopAtLevel=1 caps JIT at C1, shaving
    // ~700ms off cold-start at no measurable cost for typical single-shot CLI usage
    // (the dominant runtime cost is the script compile, not loop-body hot code).
    // See V1.0.1 jlink plan + ADR-0007 for the longer-term path.
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1")
}

// V3.1.15 — `kuml ai` command group requires kuml-ai-core.
// Guard: when built with -Pkuml.noAi=true the module is not included, so the
// project reference would be unresolvable. Check the property here and mirror it.
val aiEnabled = (findProperty("kuml.noAi") ?: "false").toString() != "true"

dependencies {
    if (aiEnabled) {
        implementation(project(path = ":kuml-ai:kuml-ai-core")) // V3.1.15 — kuml ai provider commands
        // kuml-ai-spi comes transitively via kuml-ai-core's api() dependency
        implementation(project(path = ":kuml-ai:kuml-ai-tools")) // V3.1.16 — kuml ai tools commands
    }
    implementation(libs.clikt)
    implementation(project(path = ":kuml-plugin-loader")) // V3.0.29 — kuml plugin subcommand group
    implementation(project(path = ":kuml-web")) // V2.0.34 — kuml serve subcommand
    implementation(project(path = ":kuml-codegen:kuml-codegen-api"))
    implementation(project(path = ":kuml-codegen:kuml-codegen-m2m")) // V2.0.21 — M2M transformer track
    implementation(project(path = ":kuml-codegen:kuml-transform-bpmn-to-uml")) // V3.1.43 — BPMN⇌UML Activity bridge
    implementation(project(path = ":kuml-codegen:kuml-gen-kotlin"))
    implementation(project(path = ":kuml-codegen:kuml-gen-java"))
    implementation(project(path = ":kuml-codegen:kuml-gen-sql"))
    implementation(project(path = ":kuml-runtime:kuml-runtime-core"))
    implementation(project(path = ":kuml-runtime:kuml-runtime-trace")) // V2.0.39 — kuml trace replay/export
    implementation(project(path = ":kuml-runtime:kuml-runtime-sandbox")) // V2.0.40 — Sandbox-Garantien
    implementation(project(path = ":kuml-runtime:kuml-runtime-chain-api")) // V3.0.1 — fmt --canonical + ModelHasher
    implementation(project(path = ":kuml-runtime:kuml-runtime-chain-evm")) // V3.0.4 — kuml chain subcommand (EvmChainAdapter)
    implementation(project(path = ":kuml-core:kuml-core-ocl"))
    // V2.0.20a — validate-expressions command
    implementation(project(path = ":kuml-core:kuml-core-expr"))
    implementation(project(path = ":kuml-profile:kuml-profile-api"))
    implementation(project(path = ":kuml-profile:kuml-profile-soaml"))
    implementation(project(path = ":kuml-profile:kuml-profile-javaee"))
    implementation(project(path = ":kuml-profile:kuml-profile-spring"))
    implementation(project(path = ":kuml-profile:kuml-profile-openapi"))
    implementation(project(path = ":kuml-profile:kuml-profile-autosar"))
    implementation(libs.kotlinx.serialization.json)

    // Full pipeline dependencies
    implementation(project(path = ":kuml-core:kuml-core-script"))
    implementation(project(path = ":kuml-core:kuml-core-config"))
    // Scripting API needed to access ResultWithDiagnostics / ResultValue
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Reflection for script instance property scanning
    implementation(libs.kotlin.reflect)
    implementation(project(path = ":kuml-core:kuml-core-dsl"))
    implementation(project(path = ":kuml-renderer:kuml-layout-api"))
    implementation(project(path = ":kuml-renderer:kuml-layout-elk"))
    implementation(project(path = ":kuml-renderer:kuml-layout-grid"))
    implementation(project(path = ":kuml-renderer:kuml-layout-bridge"))
    implementation(project(path = ":kuml-renderer:kuml-themes-core"))
    implementation(project(path = ":kuml-io:kuml-io-svg"))
    implementation(project(path = ":kuml-io:kuml-render-smil")) // V3.1.31 — TraceFileLoader, SpeedFactor, StaticSnapshotMode
    implementation(project(path = ":kuml-io:kuml-io-png"))
    implementation(project(path = ":kuml-io:kuml-io-anim")) // V3.2 — Animated APNG/WebP export (Fat-JAR only)
    implementation(project(path = ":kuml-io:kuml-io-latex")) // V2.0.2 — TikZ/LaTeX-Export
    implementation(project(path = ":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(path = ":kuml-metamodel:kuml-metamodel-c4"))
    implementation(project(path = ":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN CLI-Integration
    implementation(project(path = ":kuml-docs:kuml-markdown"))
    implementation(project(path = ":kuml-docs:kuml-asciidoc")) // V3.2.19 — `kuml asciidoc` subcommand, Antora pre-render step

    // V3.0.9 — `kuml reverse` subcommand. API is needed at compile time,
    // engines are loaded via ServiceLoader at runtime.
    implementation(project(path = ":kuml-codegen:kuml-codegen-reverse-api"))
    runtimeOnly(project(path = ":kuml-codegen:kuml-codegen-reverse-java")) // V3.0.7
    runtimeOnly(project(path = ":kuml-codegen:kuml-codegen-reverse-kotlin")) // V3.0.8
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // V3.1.36 — ARXML CLI tests: ExportCommandArxmlCliTest + ReverseCommandArxmlCliTest.
    // MUST remain testRuntimeOnly — kuml-io-arxml is JVM-only (JDOM2) and must NEVER become
    // a compile-time or implementation dep of kuml-cli (that would break the GraalVM native image).
    testRuntimeOnly(project(path = ":kuml-io:kuml-io-arxml"))
    // V3.1.41 — profile-uml CLI tests: ExportCommandProfileUmlCliTest.
    // MUST remain testRuntimeOnly — kuml-io-emf is JVM-only (Eclipse EMF) and must NEVER become
    // a compile-time or implementation dep of kuml-cli (that would break the GraalVM native image).
    testRuntimeOnly(project(path = ":kuml-io:kuml-io-emf"))
}

// ─────────────────────────────────────────────────────────────────────────────
// Shadow JAR — fat JAR bundling all runtime dependencies.
// Used as input for jpackage native installers and the Docker CLI image.
// MergeServiceFiles is required for Kotlin Scripting (META-INF/services entries).
// ─────────────────────────────────────────────────────────────────────────────
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "dev.kuml.cli.MainKt"
    }
    // The fat-jar's dependency tree (AWS SDK, koog agents, Compose Desktop,
    // Batik, the Kotlin compiler, …) exceeds the 65535-entry limit of the
    // classic ZIP format, so packaging tasks that build the shadow jar
    // (packageDmg, packageMsi, packageDeb/Rpm) fail with
    // Zip64RequiredException. Enabling the Zip64 extension lifts the limit;
    // all modern JVMs and jpackage/jlink read Zip64 archives transparently.
    isZip64 = true
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx512m")
}

// ─────────────────────────────────────────────────────────────────────────────
// Gradle 9 strict duplicate handling — kuml-cli transitively pulls
// runtime-desktop-<version>.jar via :kuml-web → :kuml-renderer:kuml-themes
// (Compose KMP JVM target).  Without an explicit strategy, distTar/distZip
// fail with "Entry … is a duplicate but no duplicate handling strategy has
// been set."  EXCLUDE keeps the first copy encountered.
// ─────────────────────────────────────────────────────────────────────────────
distributions {
    main {
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Build-time version metadata generation for `kuml --version` and the
// `kuml version` subcommand. Read at runtime by `dev.kuml.cli.KumlVersion`.
//
// Three values land in `build/resources/main/dev/kuml/cli/version.properties`:
//   version    — Gradle project version (single source of truth)
//   gitSha     — git rev-parse --short HEAD, or "unknown" outside a git tree
//   buildTime  — ISO-8601 timestamp captured at configuration time
//
// Mechanism: `generateVersionProperties` (WriteProperties task) writes the
// real values into a generated-resources directory; `processResources`
// excludes the static template (`src/main/resources/dev/kuml/cli/version.properties`,
// a placeholder for IDE autocomplete with `@…@` tokens) and copies the
// generated file in its place. We use WriteProperties rather than
// ProcessResources' `expand` filter because the latter's closure captures
// script-object references that break Gradle's configuration cache.
// ─────────────────────────────────────────────────────────────────────────────
val gitSha: String by lazy {
    try {
        val out =
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }
        out.standardOutput.asText
            .get()
            .trim()
            .ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

// Capture values at configuration time so the generator task can run with
// the Gradle configuration cache enabled — no `project.*` access at
// execution time.
val projectVersionForResources = version.toString()
val gitShaForResources = gitSha
// `buildTime` resolves at configuration time on each run; that's fine — we
// don't list it as a task input so it doesn't invalidate downstream caches.
val buildTimeForResources = Instant.now().toString()

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/version")

val generateVersionProperties =
    tasks.register<WriteProperties>("generateVersionProperties") {
        destinationFile.set(generatedResourcesDir.map { it.file("dev/kuml/cli/version.properties") })
        property("version", projectVersionForResources)
        property("gitSha", gitShaForResources)
        property("buildTime", buildTimeForResources)
        comment = "Generated by :kuml-cli:generateVersionProperties — read at runtime by dev.kuml.cli.KumlVersion."
    }

tasks.named<ProcessResources>("processResources") {
    // The static `src/main/resources/dev/kuml/cli/version.properties` is a
    // placeholder for IDE autocomplete; the real values come from
    // `generateVersionProperties`. Excluding the static template ensures we
    // never ship `@version@` literally to users.
    exclude("dev/kuml/cli/version.properties")
    // Copy the generated file into the resources output at the correct path.
    // Using `from(file).into(directory)` keeps both endpoints explicit and
    // avoids surprises from nested-directory traversal heuristics.
    from(generateVersionProperties.flatMap { it.destinationFile }) {
        into("dev/kuml/cli")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GraalVM Native Image configuration (Phase 2 — `kuml-packaging/kuml-native`)
//
// Status: EXPERIMENTAL. Full native compilation is blocked by Kotlin Scripting,
// which uses the embedded Kotlin compiler at runtime (incompatible with the
// closed-world assumption of GraalVM Native Image). See ADR-0007.
//
// Attempting a build: `./gradlew :kuml-cli:nativeCompile` (requires GraalVM 21+
// on PATH or via toolchain). Expected to fail in V1.0 — the configuration is
// provided so the path-forward work for V1.1 has a starting point.
// ─────────────────────────────────────────────────────────────────────────────
graalvmNative {
    binaries {
        named("main") {
            imageName.set("kuml")
            mainClass.set("dev.kuml.cli.MainKt")
            useFatJar.set(true)

            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:+UnlockExperimentalVMOptions",
                // Kotlin Scripting requires runtime class generation — these
                // classes cannot be initialised at build time.
                "--initialize-at-run-time=kotlin.script.experimental",
                "--initialize-at-run-time=org.jetbrains.kotlin.scripting",
                "--initialize-at-run-time=org.jetbrains.kotlin.cli",
                "--initialize-at-run-time=kotlin.reflect.jvm.internal",
                // Kotlinx serialization uses static initialisers that touch
                // reflection — defer initialisation.
                "--initialize-at-run-time=kotlinx.serialization.internal",
            )
        }
    }
    // Toolchain detection — looks for GraalVM 21 on PATH.
    // If not found, a downloadable toolchain can be added explicitly.
    toolchainDetection.set(true)
}

// ─────────────────────────────────────────────────────────────────────────────
// jlink runtime image (V1.0.1 Patch 2)
//
// Bundles a custom JRE with kuml-cli — eliminates the `depends_on "openjdk@21"`
// from the Homebrew formula. End users get a single self-contained tarball.
//
// Implemented by hand (jlink + install-dist patch) rather than via
// badass-runtime-plugin: the latest plugin release (1.13.1, Feb 2024) still
// uses `project.exec()` which Gradle 9 deprecated. A hand-rolled set of three
// tasks is ~30 lines and gives us control over module list + launcher.
//
// Tasks:
//   :kuml-cli:jlinkRuntime  → build/jlink/ (the stripped JRE)
//   :kuml-cli:bundledImage  → build/image/kuml/ (installDist + runtime + patched launcher)
//   :kuml-cli:runtimeZip    → build/distributions/kuml-runtime-<version>.zip
//
// Run locally: kuml-cli/build/image/kuml/bin/kuml --help
// ─────────────────────────────────────────────────────────────────────────────

// Module list — kept explicit so the runtime is reproducible across JDK
// vendors. Derived empirically from `jdeps --print-module-deps` on the
// kuml-cli fat-jar.
val jlinkModules =
    listOf(
        "java.base",
        "java.scripting", // Kotlin Scripting host
        "java.xml", // ELK + Batik
        "java.desktop", // Batik AWT
        "java.management", // Kotlin compiler diagnostics + JNA
        "java.logging", // ELK, Batik
        "java.naming", // Apache XML parser
        "java.net.http", // forward-compat for embedded LlmBackend
        "java.sql", // Batik
        "jdk.unsupported", // Kotlin compiler sun.misc.Unsafe
        "jdk.zipfs", // Kotlin scripting JAR classpath access
        "jdk.crypto.ec", // TLS for any HTTPS callers
    )

val jlinkOutputDir = layout.buildDirectory.dir("jlink")
val imageDir = layout.buildDirectory.dir("image/kuml")

tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Builds a stripped JRE for the kuml CLI via jlink."
    outputs.dir(jlinkOutputDir)
    val javaHome = providers.systemProperty("java.home").get()
    executable = "$javaHome/bin/jlink"
    val outDir = jlinkOutputDir.get().asFile
    doFirst { outDir.deleteRecursively() }
    args =
        listOf(
            "--strip-debug",
            // zip-6: 56 MB runtime image. Tested zip-0 (uncompressed, 88 MB)
            // and it actually rendered ~0.8s slower — Apple SSDs are fast
            // enough that decompression beats reading the bigger image.
            "--compress=zip-6",
            "--no-header-files",
            "--no-man-pages",
            "--add-modules",
            jlinkModules.joinToString(","),
            "--output",
            outDir.absolutePath,
        )
}

// Step A: copy installDist into the image tree.
val bundleInstallDist =
    tasks.register<Sync>("bundleInstallDist") {
        group = "distribution"
        description = "Copies the installDist tree into the runtime-image staging dir."
        dependsOn("installDist")
        from(layout.buildDirectory.dir("install/kuml"))
        into(imageDir)
    }

// Step A2: copy the kuml-mcp installDist tree into its own subdir (V3.2.13).
//
// We deliberately do NOT merge kuml-mcp's bin/ and lib/ directly into the
// CLI's bin/ and lib/ trees. kuml-cli and kuml-mcp share several jars
// (kuml-core-dsl, kuml-core-model, kuml-metamodel-*, ...) but not
// necessarily the exact same version/classpath ordering, and Gradle 9's
// Sync task has no sane "keep either, they're identical" merge semantics
// for that case — it would require either a EXCLUDE duplicatesStrategy
// (silently dropping one binary's copy of a shared jar) or per-file
// collision resolution. A separate mcp/ subdirectory with the complete,
// self-contained kuml-mcp installDist tree avoids the dedup problem
// entirely at the cost of a few duplicated jars in the zip — an
// acceptable trade for a "Small" complexity task. bin/kuml-mcp is then a
// thin wrapper that simply execs into mcp/bin/kuml-mcp.
val bundleMcpInstallDist =
    tasks.register<Sync>("bundleMcpInstallDist") {
        group = "distribution"
        description = "Copies the kuml-mcp installDist tree into the runtime-image mcp/ subdir."
        dependsOn(":kuml-mcp:installDist")
        // bundleInstallDist also writes into imageDir (a parent of mcp/). Gradle's
        // Sync task pre-cleans its destination directory before copying; running
        // both Sync tasks in parallel (org.gradle.parallel=true is the project
        // default) races on that pre-clean step and can leave mcp/ empty. Force
        // sequencing without introducing an artificial dependsOn.
        mustRunAfter(bundleInstallDist)
        from(project(":kuml-mcp").layout.buildDirectory.dir("install/kuml-mcp"))
        into(imageDir.map { it.dir("mcp") })
    }

// Step B: copy the jlink JRE under runtime/.
val bundleJlinkRuntime =
    tasks.register<Sync>("bundleJlinkRuntime") {
        group = "distribution"
        description = "Copies the stripped JRE into the runtime-image runtime/ subdir."
        dependsOn("jlinkRuntime", bundleInstallDist)
        from(jlinkOutputDir)
        into(imageDir.map { it.dir("runtime") })
    }

// Step C: rewrite the launcher's JAVA_HOME so it points at the bundled JRE.
// To stay configuration-cache compatible we capture only an explicit String
// path into the doLast lambda — no Provider, no Project, no script object
// (Gradle 9's cache serialiser otherwise complains about "Gradle script
// object references").
tasks.register("bundledImage") {
    group = "distribution"
    description =
        "Assembles a self-contained kuml runtime image: installDist + jlink JRE + patched launcher."
    dependsOn("bundleInstallDist", "bundleJlinkRuntime", bundleMcpInstallDist)

    val launcherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml")
            .absolutePath
    // The Windows launcher generated by the Gradle `application` plugin alongside
    // the shell launcher. It must be patched too, otherwise the bundled runtime
    // is self-contained only on Unix — on Windows (Chocolatey, WinGet, direct
    // download) kuml.bat would still demand a system JDK on JAVA_HOME / PATH.
    val batLauncherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml.bat")
            .absolutePath
    // kuml-mcp (V3.2.13) — installDist launcher living under mcp/bin/, plus the
    // thin wrapper script at bin/kuml-mcp that end users (and Homebrew) invoke.
    val mcpLauncherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("mcp/bin/kuml-mcp")
            .absolutePath
    val mcpWrapperPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml-mcp")
            .absolutePath
    outputs.files(launcherPath, batLauncherPath, mcpLauncherPath, mcpWrapperPath)

    doLast(
        object : Action<Task> {
            override fun execute(task: Task) {
                val launcher = File(launcherPath)
                require(launcher.isFile) { "Expected installDist launcher at $launcher" }

                // The Gradle-9 installDist launcher detects Java in this order:
                //   1. $JAVA_HOME/bin/java (if JAVA_HOME is set and valid)
                //   2. `java` on $PATH
                // We inject a JAVA_HOME override pointing at the bundled JRE so
                // that (a) the launcher works on a system without any system Java
                // and (b) users who have a different JDK installed still get the
                // exact runtime we shipped.
                val source = launcher.readText()
                val marker = "# Determine the Java command to use to start the JVM."
                require(source.contains(marker)) {
                    "Launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $launcher and adjust patcher."
                }
                val patched =
                    source.replace(
                        marker,
                        """
                        |# kUML bundled runtime: always use the JRE shipped under runtime/
                        |# next to this launcher, regardless of the user's JAVA_HOME / PATH.
                        |JAVA_HOME="${'$'}APP_HOME/runtime"
                        |export JAVA_HOME
                        |
                        |$marker
                        """.trimMargin(),
                    )
                launcher.writeText(patched)
                launcher.setExecutable(true)

                // ── Windows launcher (kuml.bat) ──────────────────────────────
                // Same self-containment fix as the shell launcher above, but for
                // the .bat the Gradle application plugin's Java-detection block
                // starts at the "@rem Find java.exe" marker. We inject a
                // JAVA_HOME override pointing at %APP_HOME%\runtime before it so
                // Chocolatey / WinGet / direct-download installs run without a
                // system JDK. The file uses CRLF line endings — keep them.
                val batLauncher = File(batLauncherPath)
                require(batLauncher.isFile) { "Expected installDist Windows launcher at $batLauncher" }
                val batSource = batLauncher.readText()
                val batMarker = "@rem Find java.exe"
                require(batSource.contains(batMarker)) {
                    "Windows launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $batLauncher and adjust patcher."
                }
                val batPatched =
                    batSource.replace(
                        batMarker,
                        "@rem kUML bundled runtime: always use the JRE shipped under runtime\\\r\n" +
                            "@rem next to this launcher, regardless of the user's JAVA_HOME / PATH.\r\n" +
                            "set JAVA_HOME=%APP_HOME%\\runtime\r\n" +
                            "\r\n" +
                            batMarker,
                    )
                batLauncher.writeText(batPatched)

                // ── kuml-mcp launcher (V3.2.13) ──────────────────────────────
                // Same self-containment fix as bin/kuml, applied to the
                // kuml-mcp installDist launcher under mcp/bin/. APP_HOME here
                // resolves to the mcp/ directory itself (one level below the
                // image root), so the bundled JRE is reached via ../runtime
                // rather than ./runtime.
                val mcpLauncher = File(mcpLauncherPath)
                require(mcpLauncher.isFile) { "Expected kuml-mcp installDist launcher at $mcpLauncher" }
                val mcpSource = mcpLauncher.readText()
                require(mcpSource.contains(marker)) {
                    "kuml-mcp launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $mcpLauncher and adjust patcher."
                }
                val mcpPatched =
                    mcpSource.replace(
                        marker,
                        """
                        |# kUML bundled runtime: always use the JRE shipped under runtime/
                        |# one level above this launcher (mcp/bin/kuml-mcp -> ../../runtime),
                        |# regardless of the user's JAVA_HOME / PATH.
                        |JAVA_HOME="${'$'}APP_HOME/../runtime"
                        |export JAVA_HOME
                        |
                        |$marker
                        """.trimMargin(),
                    )
                mcpLauncher.writeText(mcpPatched)
                mcpLauncher.setExecutable(true)

                // ── bin/kuml-mcp wrapper ──────────────────────────────────────
                // Thin exec wrapper so end users (and the Homebrew formula)
                // invoke a single top-level bin/kuml-mcp, matching bin/kuml's
                // location, without duplicating kuml-mcp's jars into lib/.
                val mcpWrapper = File(mcpWrapperPath)
                mcpWrapper.parentFile.mkdirs()
                // Written as a list of plain (non-interpolated) lines rather than a
                // single triple-quoted template: the launcher needs many literal
                // ${'$'}{...} shell parameter expansions, which collide with
                // ktlint's "redundant curly braces" rule when written as Kotlin
                // string-template escapes.
                val mcpWrapperLines =
                    listOf(
                        "#!/bin/sh",
                        "# kUML bundled runtime: thin wrapper delegating to the kuml-mcp",
                        "# installDist launcher bundled under mcp/bin/. Generated by the",
                        "# :kuml-cli:bundledImage Gradle task -- do not edit by hand.",
                        "#",
                        "# Resolve \$0 through symlinks (Homebrew installs this file as a",
                        "# symlink at \$HOMEBREW_PREFIX/bin/kuml-mcp pointing into libexec/bin/),",
                        "# the same way the Gradle `application` plugin's own generated",
                        "# launchers do, so ../mcp/bin/kuml-mcp resolves relative to the real",
                        "# file location rather than the symlink's directory.",
                        "app_path=\$0",
                        "while",
                        "    dir=\${app_path%\"\${app_path##*/}\"}",
                        "    [ -h \"\$app_path\" ]",
                        "do",
                        "    ls=\$( ls -ld \"\$app_path\" )",
                        "    link=\${ls#*' -> '}",
                        "    case \$link in",
                        "      /*)   app_path=\$link ;;",
                        "      *)    app_path=\$dir\$link ;;",
                        "    esac",
                        "done",
                        "DIR=\$( cd -P \"\${dir:-./}\" > /dev/null && pwd )",
                        "exec \"\$DIR/../mcp/bin/kuml-mcp\" \"\$@\"",
                        "",
                    )
                mcpWrapper.writeText(mcpWrapperLines.joinToString("\n"))
                mcpWrapper.setExecutable(true)
            }
        },
    )
}

tasks.register<Zip>("runtimeZip") {
    group = "distribution"
    description = "Zips the bundled kuml runtime image for release distribution."
    dependsOn("bundledImage")
    archiveBaseName.set("kuml-runtime")
    archiveVersion.set(version.toString())

    // Gradle's Zip task stores every file as 0644 by default. Without an
    // override the launcher and every JRE binary in the runtime image end
    // up non-executable inside the zip, so `brew install kuml` produces a
    // broken install (the Homebrew log says "kuml: command not found"
    // because $HOMEBREW_PREFIX/bin/kuml symlinks at a non-executable file).
    //
    // The image dir itself has correct 0755 permissions on these paths —
    // the `bundleInstallDist` / `bundleJlinkRuntime` Sync tasks preserve
    // them. We just need to tell the Zip writer to keep that for the
    // known executables, then write everything else as 0644.
    from(imageDir) {
        into("kuml-${project.version}")
        eachFile {
            val p = relativePath.pathString
            val isExecutable =
                // CLI launcher (the shell variant; .bat stays 0644).
                p.endsWith("/bin/kuml") ||
                    // kuml-mcp wrapper (V3.2.13) and its bundled installDist launcher.
                    p.endsWith("/bin/kuml-mcp") ||
                    p.endsWith("/mcp/bin/kuml-mcp") ||
                    // All JDK binaries shipped with the bundled runtime.
                    p.contains("/runtime/bin/") ||
                    // jlink's helper executable, lives outside runtime/bin.
                    p.endsWith("/runtime/lib/jspawnhelper")
            permissions {
                unix(if (isExecutable) "0755" else "0644")
            }
        }
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
