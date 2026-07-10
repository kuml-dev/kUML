plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// Editor-agnostic "brain" shared by the JetBrains plugin and the (Wave 2) LSP
// server. Pure Kotlin stdlib only — NO IntelliJ Platform, NO Compose, NO kUML
// runtime/renderer deps. The IntelliJ-specific glue stays in kuml-jetbrains.
dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
