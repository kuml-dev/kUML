plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-api"))
    api(project(":kuml-metamodel:kuml-metamodel-erm")) // ErmModel-Typen (ERM-first Signaturen, V3.4.7)
    implementation(project(":kuml-codegen:kuml-codegen-m2m")) // KumlTransformer/TransformResult (V3.4.7)
    implementation(project(":kuml-codegen:kuml-transform-uml-to-erm")) // interner UML→ERM-Chain (V3.4.7)

    // ADR-0016 Wave B: PSM interop test (FlywayBaselineGeneratorTest, test-only, no main-sourceset cycle —
    // kuml-codegen-m2m-exposed's main sourceset does not depend on kuml-gen-sql).
    testImplementation(project(":kuml-codegen:kuml-codegen-m2m-exposed"))
    testImplementation(project(":kuml-core:kuml-core-dsl")) // V3.4.7 — UML/ERM-Fixtures bauen
    testImplementation(project(":kuml-profile:kuml-profile-erm")) // V3.4.7 — ermMappingProfile für UML-Fixtures
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
