plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-api"))

    // ADR-0016 Wave B: PSM interop test (FlywayBaselineGeneratorTest, test-only, no main-sourceset cycle —
    // kuml-codegen-m2m-exposed's main sourceset does not depend on kuml-gen-sql).
    testImplementation(project(":kuml-codegen:kuml-codegen-m2m-exposed"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
