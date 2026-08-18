plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// V0.50.0 — kUML source-style validation worker.
//
// This module runs the `RequireNamedArguments` check (see :kuml-detekt-rules)
// against real `*.kuml.kts` source text — NOT via the detekt Gradle pipeline,
// but as a standalone child-process JVM launched by
// dev.kuml.core.script.style.NamedArgumentStyleCheck (:kuml-core:kuml-core-script).
//
// ## Why a separate module, and why NOT a dependency on :kuml-core:kuml-core-script
//
// The Kotlin Analysis API standalone session (`detekt-kotlin-analysis-api-standalone`)
// needs the PLAIN `org.jetbrains.kotlin:kotlin-compiler` artifact (unshaded
// `com.intellij.**`). `:kuml-core:kuml-core-script` pulls in
// `kotlin-scripting-jvm-host`, which transitively depends on
// `kotlin-compiler-embeddable` (SHADED `com.intellij.**` under
// `org.jetbrains.kotlin.com.intellij`). Both jars declare overlapping
// `org/jetbrains/kotlin/**` packages; having both on one classpath is fatal —
// verified empirically: `KotlinCoreEnvironment$Companion.getOrCreateApplicationEnvironment`
// throws `NoSuchMethodError` regardless of classpath ordering. So this module
// must never depend on kuml-core-script (directly or transitively), and vice
// versa kuml-core-script must never depend on this module. The wire protocol
// between them (see StyleWorkerWireProtocol.kt here and
// dev.kuml.core.script.style.StyleWorkerProtocol in kuml-core-script) is
// therefore intentionally duplicated rather than shared — the JSON shape is
// the contract, not a shared Kotlin type.
//
// This module's jars are copied into `lib/style/` of the kuml-cli / kuml-mcp
// distributions (never merged onto the app's own runtime classpath) and are
// only ever loaded by a freshly launched child JVM.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.detekt.kotlin.analysis.api)
    implementation(libs.detekt.kotlin.analysis.api.standalone)
    implementation(libs.kotlin.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// kotlin-compiler (unshaded) transitively pulls an older kotlin-reflect than
// this repo's Kotlin version; force it to match so reflection metadata stays
// consistent with kotlin-stdlib 2.4.0. NOTE: this only governs THIS module's
// own classpaths (e.g. running its unit tests) — the kuml-cli / kuml-mcp
// distributions resolve :kuml-style-worker as a project dependency in their
// OWN `styleWorkerRuntime` configuration, which needs (and has) the identical
// force applied on the consumer side; see the KDoc there.
//
// Two independent kotlinx-coroutines-core copies (`org.jetbrains.kotlinx:...`
// from kotlin-compiler and `org.jetbrains.intellij.deps.kotlinx:...` from
// detekt-kotlin-analysis-api) remain on the classpath — different Maven
// coordinates, not a version conflict Gradle can dedupe. This is the
// "two coroutines copies" line item in the +MB bundle-size accounting; not
// fixable without vendoring one side.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}
