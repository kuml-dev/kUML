plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    // compileOnly: the ruleset jar is loaded into detekt-cli's own classloader,
    // which already supplies detekt-api + the Kotlin Analysis API. Bundling them
    // would produce two copies of KaSession and blow up with LinkageError.
    compileOnly(libs.detekt.api)

    // NOTE: deliberately NOT depending on dev.detekt:detekt-test here. Its
    // published Gradle module metadata requests the `detekt-api-test-fixtures`
    // capability, but dev.detekt:detekt-api 2.0.0-alpha.5 only ever published a
    // sources-only variant for that capability (no compiled jar) — resolving
    // detekt-test's testRuntimeClasspath fails with "No matching variant ...
    // requested capability 'dev.detekt:detekt-api-test-fixtures'". This is an
    // alpha packaging gap in detekt-test itself, not something a version bump
    // of our own dependencies fixes. detekt-test-utils (createEnvironment,
    // KotlinAnalysisApiEngine) resolves fine standalone and is all
    // RequireNamedArgumentsSpec needs; the tiny lintWithContext-equivalent
    // helper lives directly in the spec (see TestHarness.kt).
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test.utils)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
