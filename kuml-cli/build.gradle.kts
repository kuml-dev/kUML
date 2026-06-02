plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.graalvm.native)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("dev.kuml.cli.MainKt")
    applicationName = "kuml"
}

dependencies {
    implementation(libs.clikt)
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-codegen:kuml-gen-kotlin"))
    implementation(project(":kuml-core:kuml-core-ocl"))
    implementation(libs.kotlinx.serialization.json)

    // Full pipeline dependencies
    implementation(project(":kuml-core:kuml-core-script"))
    // Scripting API needed to access ResultWithDiagnostics / ResultValue
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Reflection for script instance property scanning
    implementation(libs.kotlin.reflect)
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx512m")
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
