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

dependencies {
    implementation(libs.clikt)
    implementation(project(":kuml-web")) // V2.0.34 — kuml serve subcommand
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-codegen:kuml-codegen-m2m")) // V2.0.21 — M2M transformer track
    implementation(project(":kuml-codegen:kuml-gen-kotlin"))
    implementation(project(":kuml-codegen:kuml-gen-java"))
    implementation(project(":kuml-codegen:kuml-gen-sql"))
    implementation(project(":kuml-runtime:kuml-runtime-core"))
    implementation(project(":kuml-runtime:kuml-runtime-trace")) // V2.0.39 — kuml trace replay/export
    implementation(project(":kuml-core:kuml-core-ocl"))
    // V2.0.20a — validate-expressions command
    implementation(project(":kuml-core:kuml-core-expr"))
    implementation(project(":kuml-profile:kuml-profile-api"))
    implementation(project(":kuml-profile:kuml-profile-soaml"))
    implementation(project(":kuml-profile:kuml-profile-javaee"))
    implementation(project(":kuml-profile:kuml-profile-spring"))
    implementation(project(":kuml-profile:kuml-profile-openapi"))
    implementation(project(":kuml-profile:kuml-profile-autosar"))
    implementation(libs.kotlinx.serialization.json)

    // Full pipeline dependencies
    implementation(project(":kuml-core:kuml-core-script"))
    implementation(project(":kuml-core:kuml-core-config"))
    // Scripting API needed to access ResultWithDiagnostics / ResultValue
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Reflection for script instance property scanning
    implementation(libs.kotlin.reflect)
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-grid"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-io:kuml-io-latex")) // V2.0.2 — TikZ/LaTeX-Export
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))
    implementation(project(":kuml-docs:kuml-markdown"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
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
val projectVersionForResources = project.version.toString()
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
    dependsOn("bundleInstallDist", "bundleJlinkRuntime")

    val launcherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml")
            .absolutePath
    outputs.file(launcherPath)

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
            }
        },
    )
}

tasks.register<Zip>("runtimeZip") {
    group = "distribution"
    description = "Zips the bundled kuml runtime image for release distribution."
    dependsOn("bundledImage")
    archiveBaseName.set("kuml-runtime")
    archiveVersion.set(project.version.toString())

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
