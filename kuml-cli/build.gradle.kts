import java.time.Instant
import java.util.jar.JarOutputStream
import java.util.jar.Attributes as JarAttributes
import java.util.jar.Manifest as JarManifest

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
    implementation(project(path = ":kuml-codegen:kuml-codegen-m2m-exposed")) // ADR-0016 — UML → Exposed Table objects
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
    implementation(project(path = ":kuml-metamodel:kuml-metamodel-erm")) // V3.4.9 — ErmModelDslPrinter (kuml reverse --format sql)
    implementation(project(path = ":kuml-docs:kuml-markdown"))
    implementation(project(path = ":kuml-docs:kuml-asciidoc")) // V3.2.19 — `kuml asciidoc` subcommand, Antora pre-render step
    implementation(project(path = ":kuml-docs:kuml-workspace")) // V3.6.1 — OKF workspace core module + vocabulary (FT-2)

    // V3.0.9 — `kuml reverse` subcommand. API is needed at compile time,
    // engines are loaded via ServiceLoader at runtime.
    implementation(project(path = ":kuml-codegen:kuml-codegen-reverse-api"))
    runtimeOnly(project(path = ":kuml-codegen:kuml-codegen-reverse-java")) // V3.0.7
    runtimeOnly(project(path = ":kuml-codegen:kuml-codegen-reverse-kotlin")) // V3.0.8
    // V3.4.9 — JSqlParser-based SQL DDL → ERM reverse engine. MUST remain runtimeOnly:
    // JSqlParser is a JavaCC-generated parser, not verified GraalVM Native Image-safe —
    // exactly the same rationale as reverse-java's JavaParser dependency above.
    runtimeOnly(project(path = ":kuml-codegen:kuml-codegen-reverse-sql"))
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

// Step A3: copy the kuml-lsp installDist tree into its own subdir (2026-07-19 —
// closes the "kuml-lsp isn't shipped anywhere" gap; see
// docs/handbook and the Distribution-und-Packaging vault note).
//
// Same rationale as bundleMcpInstallDist above: kuml-lsp shares several jars
// with kuml-cli/kuml-mcp (kuml-core-dsl, kuml-metamodel-*, ...) but not
// necessarily the same version/classpath ordering, so a separate lsp/
// subdirectory with the complete, self-contained kuml-lsp installDist tree
// avoids the Sync dedup problem entirely, at the cost of a few duplicated
// jars in the zip. bin/kuml-lsp is then a thin wrapper that simply execs
// into lsp/bin/kuml-lsp — mirrors bin/kuml-mcp exactly.
val bundleLspInstallDist =
    tasks.register<Sync>("bundleLspInstallDist") {
        group = "distribution"
        description = "Copies the kuml-lsp installDist tree into the runtime-image lsp/ subdir."
        dependsOn(":kuml-language-server:installDist")
        // Same race-avoidance reasoning as bundleMcpInstallDist's mustRunAfter:
        // all three Sync tasks write under imageDir and would race on each
        // other's pre-clean step under org.gradle.parallel=true.
        mustRunAfter(bundleInstallDist, bundleMcpInstallDist)
        from(project(":kuml-language-server").layout.buildDirectory.dir("install/kuml-lsp"))
        into(imageDir.map { it.dir("lsp") })
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
// cmd.exe refuses to read any single batch-script source line longer than
// ~8191 characters ("Die eingegebene Zeile ist zu lang." / "The input line
// is too long."), and Gradle's `application` plugin unconditionally emits
// the ENTIRE classpath as one `set CLASSPATH=...` line. That's harmless for
// small dependency trees, but kuml-cli bundles ~300 jars (AI providers,
// Compose Multiplatform, AWS Bedrock SDK, Kotlin compiler-embeddable, ELK,
// Batik, Ktor, ...) and that line is ~14.9 KB — cmd.exe rejects it outright,
// so kuml.bat never even reaches java.exe. Confirmed on real Windows 11
// (2026-07-05, first-ever real Windows test of this launcher): every
// invocation failed immediately with the error above. This affects every
// Windows distribution channel that ships this launcher (Chocolatey, direct
// runtimeZip download) — not something specific to Chocolatey packaging.
//
// Fix attempt #1 (superseded — kept here as a documented dead end): rewrite
// the single `set CLASSPATH=...` line into one short `set CLASSPATH=%CLASSPATH%;...`
// append per jar. That does NOT work: cmd.exe's ~8191 char ceiling applies to
// the FINAL, fully-expanded command it executes, not just to raw source
// lines. The launcher's closing
// `"%JAVA_EXE%" ... -classpath "%CLASSPATH%" dev.kuml.cli.MainKt %*` line is
// short as written, but once %CLASSPATH% (still ~14.9 KB) is substituted at
// execution time, cmd.exe hits the identical limit and fails identically —
// confirmed on real Windows 11, 2026-07-05, immediately after "fixing" it
// this way.
//
// Actual fix: a "pathing jar" — the standard, widely-used technique for JVM
// launchers with huge classpaths on Windows (used by Maven's
// maven-jar-plugin classpath mode, sbt-native-packager, and others facing
// the identical problem). A stub jar with no class files, just a
// META-INF/MANIFEST.MF `Class-Path:` attribute listing every real jar as a
// space-separated *relative* filename. Per the JAR spec, manifest Class-Path
// entries resolve relative to the jar that CONTAINS the manifest — not the
// process's working directory — so bare filenames are enough since every
// jar lives flat in the same lib/ directory as the stub. The launcher then
// only ever needs `-classpath <stub.jar>` (one short path); the JVM reads
// the rest straight out of the manifest with no OS command-line involved at
// all, so no length limit applies. `java.util.jar.Manifest`'s writer also
// handles the JAR spec's 72-byte-line-plus-continuation wrapping for long
// attribute values automatically — no manual line-wrapping needed here.
//
// Preserves the exact original jar order (unlike a `%APP_HOME%\lib\*`
// wildcard, whose expansion order follows NTFS directory enumeration and
// isn't guaranteed to match) — order matters here because the bundled
// Compose Multiplatform dependency tree pulls in more than one version of a
// handful of jars (e.g. two `runtime-desktop-*.jar` releases) and
// classloading currently relies on the existing explicit sequence to pick
// the intended one, same as on Linux/macOS.
//
// Applied unconditionally, regardless of how short the *source* line looks —
// deliberately NOT gated behind a source-line-length safety margin. The
// source line only ever contains the short `%APP_HOME%` token per entry;
// the string cmd.exe actually has to execute is the *expanded* one, with
// %APP_HOME% substituted by the real install path — and that varies by
// install location in a way this build cannot predict (e.g. kuml-mcp.bat's
// source line is a modest ~3.5 KB with ~90 jars, but a length check against
// THAT number is meaningless: at a sufficiently deep install path — plenty
// of real Chocolatey/user-profile paths qualify — the expanded line still
// exceeds cmd.exe's ~8191 char ceiling and fails identically. Confirmed by
// direct reproduction on real Windows 11 (2026-07-05): kuml-mcp.bat failed
// with the exact same "Die eingegebene Zeile ist zu lang." even though its
// own source CLASSPATH line is nowhere near 8000 chars. The fix is cheap
// (one small manifest-only jar) and correct at any classpath size or
// install depth, so there is no real benefit to skipping it selectively —
// only risk in guessing wrong about "long enough to matter".
//
// Declared as a top-level `object` (not a top-level `fun`): a plain script
// function becomes an instance method of the synthesized build-script class,
// so calling it from inside a `doLast(object : Action<Task> { ... })` block
// captures an unserializable reference to the script instance itself —
// exactly the "cannot serialize Gradle script object references" failure
// this module's other doFirst/doLast blocks already work around (see
// signBundledRuntime below). A top-level `object` compiles as a genuinely
// independent class with no such implicit outer reference.
object WindowsClasspathFix {
    fun explodeIfNeeded(batFile: File) {
        val eol = "\r\n"
        val lines = batFile.readText().split(eol)
        val idx = lines.indexOfFirst { it.startsWith("set CLASSPATH=") }
        if (idx < 0) return
        val line = lines[idx]

        val entries = line.removePrefix("set CLASSPATH=").split(";")
        val jarNames = entries.map { it.substringAfterLast('\\') }

        // bin/ and lib/ are siblings directly under the image root (or, for
        // kuml-mcp, under mcp/) — batFile is .../<root>/bin/kuml.bat.
        val libDir = File(batFile.parentFile.parentFile, "lib")
        require(libDir.isDirectory) { "Expected lib dir at $libDir next to $batFile" }
        val pathingJar = File(libDir, "kuml-windows-classpath.jar")
        val manifest =
            JarManifest().apply {
                mainAttributes[JarAttributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[JarAttributes.Name.CLASS_PATH] = jarNames.joinToString(" ")
            }
        val jarOut = JarOutputStream(pathingJar.outputStream(), manifest)
        jarOut.close()

        val newLines = lines.toMutableList()
        newLines[idx] = "set CLASSPATH=%APP_HOME%\\lib\\${pathingJar.name}"
        batFile.writeText(newLines.joinToString(eol))
    }
}

tasks.register("bundledImage") {
    group = "distribution"
    description =
        "Assembles a self-contained kuml runtime image: installDist + jlink JRE + patched launcher."
    dependsOn("bundleInstallDist", "bundleJlinkRuntime", bundleMcpInstallDist, bundleLspInstallDist)

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
    // Windows counterparts of the two paths above (V0.23.4 — Chocolatey
    // kuml-mcp support). The Gradle `application` plugin generates
    // mcp/bin/kuml-mcp.bat unconditionally (regardless of build-host OS), but
    // it is never patched with a JAVA_HOME override, unlike mcp/bin/kuml-mcp —
    // so on Windows it silently demands a system JDK, breaking the
    // "self-contained, no separate JDK install required" contract that
    // kuml.bat already honours. There is also no top-level bin/kuml-mcp.bat
    // wrapper (only the Unix bin/kuml-mcp sh wrapper exists), so Chocolatey's
    // shim step has nothing to expose as a `kuml-mcp` command.
    val mcpBatLauncherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("mcp/bin/kuml-mcp.bat")
            .absolutePath
    val mcpBatWrapperPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml-mcp.bat")
            .absolutePath
    // kuml-lsp (2026-07-19) — installDist launcher living under lsp/bin/, plus the
    // thin wrapper script at bin/kuml-lsp that end users (and Homebrew) invoke.
    // Mirrors the kuml-mcp paths above exactly.
    val lspLauncherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("lsp/bin/kuml-lsp")
            .absolutePath
    val lspWrapperPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml-lsp")
            .absolutePath
    val lspBatLauncherPath: String =
        imageDir
            .get()
            .asFile
            .resolve("lsp/bin/kuml-lsp.bat")
            .absolutePath
    val lspBatWrapperPath: String =
        imageDir
            .get()
            .asFile
            .resolve("bin/kuml-lsp.bat")
            .absolutePath
    outputs.files(
        launcherPath,
        batLauncherPath,
        mcpLauncherPath,
        mcpWrapperPath,
        mcpBatLauncherPath,
        mcpBatWrapperPath,
        lspLauncherPath,
        lspWrapperPath,
        lspBatLauncherPath,
        lspBatWrapperPath,
    )

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
                WindowsClasspathFix.explodeIfNeeded(batLauncher)

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

                // ── kuml-mcp Windows launcher (mcp/bin/kuml-mcp.bat) ──────────
                // Same self-containment fix as kuml.bat above, applied to the
                // kuml-mcp installDist Windows launcher under mcp/bin/. The
                // Gradle application plugin's Java-detection block starts at
                // "@rem Find java.exe" — same marker as kuml.bat, since both
                // are generated by the same plugin template. %APP_HOME% here
                // resolves to mcp\, one level below the image root, so the
                // bundled JRE is reached via ..\runtime rather than .\runtime.
                val mcpBatLauncher = File(mcpBatLauncherPath)
                require(mcpBatLauncher.isFile) { "Expected kuml-mcp installDist Windows launcher at $mcpBatLauncher" }
                val mcpBatSource = mcpBatLauncher.readText()
                require(mcpBatSource.contains(batMarker)) {
                    "kuml-mcp Windows launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $mcpBatLauncher and adjust patcher."
                }
                val mcpBatPatched =
                    mcpBatSource.replace(
                        batMarker,
                        "@rem kUML bundled runtime: always use the JRE shipped under runtime\\\r\n" +
                            "@rem one level above this launcher (mcp\\bin\\kuml-mcp.bat -> ..\\..\\runtime),\r\n" +
                            "@rem regardless of the user's JAVA_HOME / PATH.\r\n" +
                            "set JAVA_HOME=%APP_HOME%\\..\\runtime\r\n" +
                            "\r\n" +
                            batMarker,
                    )
                mcpBatLauncher.writeText(mcpBatPatched)
                WindowsClasspathFix.explodeIfNeeded(mcpBatLauncher)

                // ── bin/kuml-mcp.bat wrapper ───────────────────────────────────
                // Windows counterpart of the bin/kuml-mcp sh wrapper above: a
                // thin CALL into the real launcher under mcp\bin\, so
                // Chocolatey's auto-shim step (which already picks up
                // kuml.bat, see chocolateyInstall.ps1) exposes a `kuml-mcp`
                // command at the same top-level bin\ location as `kuml`.
                // %~dp0 is the batch-file equivalent of the sh wrapper's
                // symlink-resolved $DIR — it always resolves to this file's
                // own directory, not the caller's cwd.
                val mcpBatWrapper = File(mcpBatWrapperPath)
                mcpBatWrapper.parentFile.mkdirs()
                val mcpBatWrapperLines =
                    listOf(
                        "@rem kUML bundled runtime: thin wrapper delegating to the kuml-mcp",
                        "@rem installDist launcher bundled under mcp\\bin\\. Generated by the",
                        "@rem :kuml-cli:bundledImage Gradle task -- do not edit by hand.",
                        "@echo off",
                        "call \"%~dp0..\\mcp\\bin\\kuml-mcp.bat\" %*",
                        "",
                    )
                mcpBatWrapper.writeText(mcpBatWrapperLines.joinToString("\r\n"))

                // ── kuml-lsp launcher (2026-07-19) ────────────────────────────────
                // Same self-containment fix as bin/kuml-mcp above, applied to the
                // kuml-lsp installDist launcher under lsp/bin/. APP_HOME here
                // resolves to the lsp/ directory itself (one level below the
                // image root), so the bundled JRE is reached via ../runtime
                // rather than ./runtime — identical layout to mcp/.
                val lspLauncher = File(lspLauncherPath)
                require(lspLauncher.isFile) { "Expected kuml-lsp installDist launcher at $lspLauncher" }
                val lspSource = lspLauncher.readText()
                require(lspSource.contains(marker)) {
                    "kuml-lsp launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $lspLauncher and adjust patcher."
                }
                val lspPatched =
                    lspSource.replace(
                        marker,
                        """
                        |# kUML bundled runtime: always use the JRE shipped under runtime/
                        |# one level above this launcher (lsp/bin/kuml-lsp -> ../../runtime),
                        |# regardless of the user's JAVA_HOME / PATH.
                        |JAVA_HOME="${'$'}APP_HOME/../runtime"
                        |export JAVA_HOME
                        |
                        |$marker
                        """.trimMargin(),
                    )
                lspLauncher.writeText(lspPatched)
                lspLauncher.setExecutable(true)

                // ── bin/kuml-lsp wrapper ────────────────────────────────────────
                // Thin exec wrapper so end users (and the Homebrew formula)
                // invoke a single top-level bin/kuml-lsp, matching bin/kuml and
                // bin/kuml-mcp's location, without duplicating kuml-lsp's jars
                // into lib/.
                val lspWrapper = File(lspWrapperPath)
                lspWrapper.parentFile.mkdirs()
                val lspWrapperLines =
                    listOf(
                        "#!/bin/sh",
                        "# kUML bundled runtime: thin wrapper delegating to the kuml-lsp",
                        "# installDist launcher bundled under lsp/bin/. Generated by the",
                        "# :kuml-cli:bundledImage Gradle task -- do not edit by hand.",
                        "#",
                        "# Resolve \$0 through symlinks (Homebrew installs this file as a",
                        "# symlink at \$HOMEBREW_PREFIX/bin/kuml-lsp pointing into libexec/bin/),",
                        "# the same way the Gradle `application` plugin's own generated",
                        "# launchers do, so ../lsp/bin/kuml-lsp resolves relative to the real",
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
                        "exec \"\$DIR/../lsp/bin/kuml-lsp\" \"\$@\"",
                        "",
                    )
                lspWrapper.writeText(lspWrapperLines.joinToString("\n"))
                lspWrapper.setExecutable(true)

                // ── kuml-lsp Windows launcher (lsp/bin/kuml-lsp.bat) ───────────
                // Same self-containment fix as kuml-mcp.bat above, applied to the
                // kuml-lsp installDist Windows launcher under lsp/bin/. %APP_HOME%
                // here resolves to lsp\, one level below the image root, so the
                // bundled JRE is reached via ..\runtime rather than .\runtime.
                val lspBatLauncher = File(lspBatLauncherPath)
                require(lspBatLauncher.isFile) { "Expected kuml-lsp installDist Windows launcher at $lspBatLauncher" }
                val lspBatSource = lspBatLauncher.readText()
                require(lspBatSource.contains(batMarker)) {
                    "kuml-lsp Windows launcher format unexpected — could not find Java-detection marker. " +
                        "Inspect $lspBatLauncher and adjust patcher."
                }
                val lspBatPatched =
                    lspBatSource.replace(
                        batMarker,
                        "@rem kUML bundled runtime: always use the JRE shipped under runtime\\\r\n" +
                            "@rem one level above this launcher (lsp\\bin\\kuml-lsp.bat -> ..\\..\\runtime),\r\n" +
                            "@rem regardless of the user's JAVA_HOME / PATH.\r\n" +
                            "set JAVA_HOME=%APP_HOME%\\..\\runtime\r\n" +
                            "\r\n" +
                            batMarker,
                    )
                lspBatLauncher.writeText(lspBatPatched)
                WindowsClasspathFix.explodeIfNeeded(lspBatLauncher)

                // ── bin/kuml-lsp.bat wrapper ─────────────────────────────────────
                // Windows counterpart of the bin/kuml-lsp sh wrapper above: a
                // thin CALL into the real launcher under lsp\bin\, so
                // Chocolatey's auto-shim step exposes a `kuml-lsp` command at
                // the same top-level bin\ location as `kuml` and `kuml-mcp`.
                val lspBatWrapper = File(lspBatWrapperPath)
                lspBatWrapper.parentFile.mkdirs()
                val lspBatWrapperLines =
                    listOf(
                        "@rem kUML bundled runtime: thin wrapper delegating to the kuml-lsp",
                        "@rem installDist launcher bundled under lsp\\bin\\. Generated by the",
                        "@rem :kuml-cli:bundledImage Gradle task -- do not edit by hand.",
                        "@echo off",
                        "call \"%~dp0..\\lsp\\bin\\kuml-lsp.bat\" %*",
                        "",
                    )
                lspBatWrapper.writeText(lspBatWrapperLines.joinToString("\r\n"))
            }
        },
    )
}

// V3.2.26 — Developer ID sign every Mach-O in the bundled jlink runtime image
// (the actual AMFI fix, see "MCP-Server AMFI-Signierungsproblem (macOS)" and the
// "V3.2-Apple-Signierung-Wellenplan" plan).
//
// Gate: only active when KUML_SIGN_IDENTITY is set AND the host is macOS.
// Silent no-op otherwise — must not break Linux/Windows CI legs, nor local
// builds on a machine without a Developer ID certificate.
//
// Signing order is bottom-up: every leaf Mach-O (dylibs, JDK binaries) is
// signed individually via kuml-packaging/scripts/sign-macho.sh with the
// hardened-runtime + JIT entitlements (jvm-hardened.entitlements). There is
// no outer app-bundle wrapper for a bare jlink tree, so there is no "outer
// signing invalidates inner signing" concern here — but every single file
// must still be signed on its own (`codesign --deep` is unreliable/deprecated
// and is deliberately not used).
//
// Deviation from the original plan (discovered during local verification,
// not called out in the plan document): the plan scoped this task to
// `imageDir/runtime/` only (the jlink JRE's own native libs — libjli, libjvm,
// etc., the actual AMFI root cause for kuml-mcp's startup failure). But a
// real `notarytool submit` on the resulting runtimeZip additionally rejects
// with "Archive contains critical validation errors" for several *nested*
// Mach-O files shipped inside third-party dependency JARs under
// `imageDir/lib/` and `imageDir/mcp/lib/` (JNA's libjnidispatch.jnilib,
// sqlite-jdbc's libsqlitejdbc.dylib, and Jansi's libjansi.jnilib bundled
// inside kotlin-compiler-embeddable.jar) — all ad-hoc/unsigned. Apple's
// notary service scans every Mach-O in the archive, not just the ones the
// JVM loads at startup, so these must be signed too for notarization to
// succeed (status: Accepted) — even though they are not the AMFI/Library-
// Validation root cause fixed by signing runtime/. Handled by unpacking each
// affected jar, signing the embedded native libs, and repacking in place.
val signBundledRuntime =
    tasks.register("signBundledRuntime") {
        group = "distribution"
        description =
            "Developer-ID-signs every Mach-O in the bundled jlink runtime image, " +
            "including native libs nested inside dependency jars " +
            "(no-op unless KUML_SIGN_IDENTITY is set and host is macOS)."
        dependsOn("bundledImage")
        mustRunAfter("bundledImage")

        val signIdentity = providers.environmentVariable("KUML_SIGN_IDENTITY").orNull
        val isMacOs =
            org.gradle.internal.os.OperatingSystem
                .current()
                .isMacOsX
        val imageRootPath: String = imageDir.get().asFile.absolutePath
        val runtimeDirPath: String =
            imageDir
                .get()
                .asFile
                .resolve("runtime")
                .absolutePath
        val signScriptPath: String =
            rootProject.file("kuml-packaging/scripts/sign-macho.sh").absolutePath
        val entitlementsPath: String =
            rootProject.file("kuml-packaging/scripts/jvm-hardened.entitlements").absolutePath
        val keychainPath: String? = providers.environmentVariable("KUML_SIGN_KEYCHAIN").orNull

        onlyIf { signIdentity != null && isMacOs }

        doLast(
            object : Action<Task> {
                override fun execute(task: Task) {
                    val runtimeDir = File(runtimeDirPath)
                    require(runtimeDir.isDirectory) {
                        "Expected bundled runtime dir at $runtimeDir — did bundledImage run?"
                    }
                    val env = mutableMapOf("KUML_SIGN_IDENTITY" to signIdentity!!)
                    if (keychainPath != null) env["KUML_SIGN_KEYCHAIN"] = keychainPath

                    // Detects Mach-O files by reading their first 4 bytes directly and
                    // comparing against the known magic numbers, rather than shelling
                    // out to the external `file` command and pattern-matching its
                    // text output. Found 2026-07-05: the CI-built v0.24.0 release
                    // signed 43 Mach-O files instead of the 44 a local build signs —
                    // `runtime/lib/libjli.dylib` (the exact file the original AMFI bug
                    // report named) shipped still ad-hoc-signed, breaking `kuml`/
                    // `kuml-mcp` for every real Homebrew install with a dyld
                    // "different Team IDs" error. The `file`(1) utility's output can
                    // vary across libmagic database versions/OS images in ways a
                    // substring match doesn't survive; magic-byte detection has no
                    // external-tool dependency to drift.
                    val machOMagicNumbers =
                        setOf(
                            0xfeedface.toInt(), // MH_MAGIC (32-bit)
                            0xcefaedfe.toInt(), // MH_CIGAM (32-bit, byte-swapped)
                            0xfeedfacf.toInt(), // MH_MAGIC_64
                            0xcffaedfe.toInt(), // MH_CIGAM_64 (byte-swapped)
                            0xcafebabe.toInt(), // FAT_MAGIC (universal binary)
                            0xbebafeca.toInt(), // FAT_CIGAM (universal binary, byte-swapped)
                        )

                    // v0.24.1 shipped STILL broken despite the magic-byte rewrite above:
                    // the real CI build again signed 43/44 files, and the real shipped
                    // artifact again had runtime/lib/libjli.dylib ad-hoc. So the `file`
                    // command wasn't the (only) cause — something makes this file
                    // invisible to a plain directory walk on that runner at the moment
                    // signBundledRuntime executes. Two changes to actually find out why
                    // instead of guessing again: (1) never silently swallow a read
                    // failure — log it, so a future CI run's log shows the real reason
                    // if this file (or any other) can't be read; (2) retry briefly, in
                    // case of a transient I/O hiccup (e.g. a security-scanning process
                    // holding the file open right after it's written) rather than a
                    // structural miss.
                    fun isMachO(file: File): Boolean {
                        var lastError: Exception? = null
                        repeat(3) { attempt ->
                            try {
                                file.inputStream().use { stream ->
                                    val header = ByteArray(4)
                                    val read = stream.read(header)
                                    if (read != 4) {
                                        logger.warn(
                                            "signBundledRuntime: isMachO($file) read only $read/4 header " +
                                                "bytes (attempt ${attempt + 1}/3)",
                                        )
                                        return@repeat
                                    }
                                    val magic =
                                        ((header[0].toInt() and 0xff) shl 24) or
                                            ((header[1].toInt() and 0xff) shl 16) or
                                            ((header[2].toInt() and 0xff) shl 8) or
                                            (header[3].toInt() and 0xff)
                                    return magic in machOMagicNumbers
                                }
                            } catch (e: Exception) {
                                lastError = e
                                logger.warn(
                                    "signBundledRuntime: isMachO($file) failed on attempt " +
                                        "${attempt + 1}/3: ${e::class.simpleName}: ${e.message}",
                                )
                                Thread.sleep(200)
                            }
                        }
                        if (lastError != null) {
                            logger.warn("signBundledRuntime: isMachO($file) gave up after 3 attempts, treating as non-Mach-O")
                        }
                        return false
                    }

                    val signedFiles = mutableListOf<File>()

                    fun signFile(file: File) {
                        // Plain ProcessBuilder rather than Gradle's exec APIs: this
                        // runs inside a doLast Action, where `project.exec` is
                        // unavailable (Gradle 9 configuration-cache constraints —
                        // same reasoning as the jlinkRuntime comment above on why
                        // this repo avoids project.exec() in task actions).
                        val processBuilder =
                            ProcessBuilder(signScriptPath, file.absolutePath, entitlementsPath)
                                .redirectErrorStream(true)
                        processBuilder.environment().putAll(env)
                        val proc = processBuilder.start()
                        val output = proc.inputStream.bufferedReader().readText()
                        val exitCode = proc.waitFor()
                        require(exitCode == 0) {
                            "sign-macho.sh failed (exit $exitCode) for $file:\n$output"
                        }
                        signedFiles.add(file)
                    }

                    var signedCount = 0

                    // 1. Every real Mach-O directly under runtime/ (the jlink JRE
                    // itself — the actual AMFI root cause).
                    runtimeDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        if (isMachO(file)) {
                            signFile(file)
                            signedCount++
                        }
                    }

                    // 2. Native libs nested inside dependency jars anywhere under
                    // the assembled image (lib/, mcp/lib/) — required for
                    // notarytool to accept the archive (see deviation note above).
                    // Unzip to a scratch dir, sign, rezip in place with `zip`
                    // (Info-ZIP), which by default only replaces the changed
                    // entries and preserves the rest of the archive byte-for-byte.
                    val imageRoot = File(imageRootPath)
                    imageRoot
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "jar" }
                        .forEach { jar ->
                            // `-Z1` (zipinfo short format) prints one bare entry path per
                            // line with no columns to parse — unlike `-l`, it survives
                            // entry paths containing spaces.
                            val nativeEntries =
                                ProcessBuilder("unzip", "-Z1", jar.absolutePath)
                                    .redirectErrorStream(true)
                                    .start()
                                    .let { proc ->
                                        val out = proc.inputStream.bufferedReader().readText()
                                        proc.waitFor()
                                        out
                                    }.lineSequence()
                                    .map { it.trim() }
                                    .filter { it.endsWith(".dylib") || it.endsWith(".jnilib") }
                                    .toList()
                            if (nativeEntries.isEmpty()) return@forEach

                            val scratchDir =
                                File(temporaryDir, jar.nameWithoutExtension + "-" + jar.parentFile.name)
                            scratchDir.deleteRecursively()
                            scratchDir.mkdirs()

                            val extractProc =
                                ProcessBuilder("unzip", "-oq", jar.absolutePath, "-d", scratchDir.absolutePath)
                                    .redirectErrorStream(true)
                                    .start()
                            val extractOut = extractProc.inputStream.bufferedReader().readText()
                            require(extractProc.waitFor() == 0) {
                                "unzip failed for $jar:\n$extractOut"
                            }

                            var jarHadSignedEntry = false
                            nativeEntries.forEach { entryPath ->
                                val extracted = File(scratchDir, entryPath)
                                if (extracted.isFile && isMachO(extracted)) {
                                    signFile(extracted)
                                    signedCount++
                                    jarHadSignedEntry = true
                                }
                            }

                            if (jarHadSignedEntry) {
                                // Update just the signed entries in place — `zip -j`
                                // would flatten paths, so cd into scratchDir and use
                                // relative entry paths instead.
                                val updateProc =
                                    ProcessBuilder(
                                        listOf("zip", jar.absolutePath) + nativeEntries,
                                    ).directory(scratchDir)
                                        .redirectErrorStream(true)
                                        .start()
                                val updateOut = updateProc.inputStream.bufferedReader().readText()
                                require(updateProc.waitFor() == 0) {
                                    "zip (repack) failed for $jar:\n$updateOut"
                                }
                            }
                        }

                    // Post-signing safety net (added 2026-07-05 after v0.24.0 shipped
                    // with runtime/lib/libjli.dylib still ad-hoc-signed — a Mach-O
                    // detection miss let it silently skip signFile() entirely, so it
                    // never even entered `signedFiles`, and nothing caught the gap
                    // before release). Checking only `signedFiles` would repeat that
                    // exact mistake: a file this loop never recognized as Mach-O in
                    // the first place would be invisible to it too. Instead,
                    // independently re-walk the actual filesystem state with the same
                    // magic-byte detector and verify every Mach-O found — signed by
                    // this task or not — carries a real, non-ad-hoc signature.
                    fun verifyNoAdhocMachO(file: File) {
                        val verifyProc =
                            ProcessBuilder("codesign", "-dv", file.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                        val verifyOutput = verifyProc.inputStream.bufferedReader().readText()
                        verifyProc.waitFor()
                        require(!verifyOutput.contains("Signature=adhoc")) {
                            "signBundledRuntime: $file is still ad-hoc-signed after this task " +
                                "completed — refusing to ship a broken runtime image. " +
                                "codesign output:\n$verifyOutput"
                        }
                        require(
                            verifyOutput.contains("TeamIdentifier=") &&
                                !verifyOutput.contains("TeamIdentifier=not set"),
                        ) {
                            "signBundledRuntime: $file has no real TeamIdentifier after this " +
                                "task completed — refusing to ship a broken runtime image. " +
                                "codesign output:\n$verifyOutput"
                        }
                    }
                    runtimeDir.walkTopDown().filter { it.isFile && isMachO(it) }.forEach(::verifyNoAdhocMachO)
                    logger.lifecycle("signBundledRuntime: verified ${signedFiles.size} signed file(s), 0 ad-hoc")

                    // Extra diagnostic + hard gate for the exact file that shipped
                    // broken in both v0.24.0 and v0.24.1 despite the fixes above —
                    // located by FILENAME, independent of isMachO()/walkTopDown()
                    // entirely, so this tells us definitively whether the problem is
                    // "file not found by this task at all" (timing/materialization —
                    // Gradle Sync hasn't finished writing it into imageDir yet when
                    // this doLast runs) vs. "file found but something about signing/
                    // detection still misses it".
                    val criticalFilenames = setOf("libjli.dylib", "libjvm.dylib")
                    val foundCritical = runtimeDir.walkTopDown().filter { it.isFile && it.name in criticalFilenames }.toList()
                    logger.lifecycle(
                        "signBundledRuntime: critical-file check — looked for $criticalFilenames under " +
                            "$runtimeDir, found ${foundCritical.size}: ${foundCritical.map { it.absolutePath }}",
                    )
                    criticalFilenames.forEach { name ->
                        val match = foundCritical.find { it.name == name }
                        require(match != null) {
                            "signBundledRuntime: $name does not exist anywhere under $runtimeDir at the " +
                                "point this task ran — bundledImage did not finish materializing the " +
                                "runtime image before signing started. Directory listing of " +
                                "$runtimeDir/lib: " +
                                (File(runtimeDir, "lib").listFiles()?.map { it.name }?.sorted() ?: "lib/ missing entirely")
                        }
                        logger.lifecycle(
                            "signBundledRuntime: $name found at ${match.absolutePath}, " +
                                "size=${match.length()} bytes, isMachO=${isMachO(match)}",
                        )
                        verifyNoAdhocMachO(match)
                    }

                    logger.lifecycle(
                        "signBundledRuntime: signed $signedCount Mach-O file(s) " +
                            "under $runtimeDir and nested inside dependency jars " +
                            "(verified: none ad-hoc, all carry a real TeamIdentifier)",
                    )
                }
            },
        )
    }

tasks.register<Zip>("runtimeZip") {
    group = "distribution"
    description = "Zips the bundled kuml runtime image for release distribution."
    dependsOn("bundledImage", signBundledRuntime)
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
                    // kuml-lsp wrapper (2026-07-19) and its bundled installDist launcher —
                    // same reasoning as kuml-mcp above. Missing this entry doesn't fail the
                    // build (the imageDir copy already has 0755 via setExecutable(true) in
                    // bundledImage), but it silently ships bin/kuml-lsp and lsp/bin/kuml-lsp
                    // as non-executable (0644) in the zip itself — direct-download users
                    // hit "Permission denied", masked for Homebrew users only because the
                    // formula's own explicit chmod papers over it.
                    p.endsWith("/bin/kuml-lsp") ||
                    p.endsWith("/lsp/bin/kuml-lsp") ||
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

// V3.2.26 — Notarize the signed runtimeZip. Gated identically to
// signBundledRuntime (KUML_SIGN_IDENTITY set + macOS host); silent no-op
// otherwise. No --staple: a bare zip cannot be stapled (only .app/.pkg/.dmg
// can). Gatekeeper instead does an online check against Apple's notary
// service the first time the unzipped binaries run — expected and fine for
// a CLI tool distributed via Homebrew/SDKMAN.
tasks.register("notarizeRuntimeZip") {
    group = "distribution"
    description =
        "Notarizes the signed kuml-runtime zip (no-op unless KUML_SIGN_IDENTITY is set and host is macOS)."
    dependsOn("runtimeZip")

    val signIdentity = providers.environmentVariable("KUML_SIGN_IDENTITY").orNull
    val isMacOs =
        org.gradle.internal.os.OperatingSystem
            .current()
            .isMacOsX
    val notarizeScriptPath: String =
        rootProject.file("kuml-packaging/scripts/notarize-and-staple.sh").absolutePath
    val zipPath: String =
        layout.buildDirectory
            .dir("distributions")
            .get()
            .asFile
            .resolve("kuml-runtime-${project.version}.zip")
            .absolutePath

    onlyIf { signIdentity != null && isMacOs }

    doLast(
        object : Action<Task> {
            override fun execute(task: Task) {
                val zip = File(zipPath)
                require(zip.isFile) { "Expected runtimeZip output at $zip — did runtimeZip run?" }
                // Plain ProcessBuilder — see signBundledRuntime above for why
                // project.exec() is avoided inside a doLast Action here.
                val proc =
                    ProcessBuilder(notarizeScriptPath, zip.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                // Stream output live rather than buffering — notarization can take
                // several minutes and this keeps the build log informative while
                // waiting on Apple's servers.
                proc.inputStream.bufferedReader().forEachLine { println(it) }
                val exitCode = proc.waitFor()
                require(exitCode == 0) { "notarize-and-staple.sh failed (exit $exitCode) for $zip" }
            }
        },
    )
}
