plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-runtime:kuml-runtime-core"))
    api(project(":kuml-runtime:kuml-runtime-sandbox"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-bpmn"))
    api(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-core:kuml-core-ocl"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // Kotlin reflection (visibility of TokenFlowEngine's sole constructor) —
    // JVM bytecode modifiers can't be used for this check: an `internal`
    // Kotlin constructor still compiles to a `public` JVM constructor (no
    // mangling applies to <init>), so only kotlin-reflect's own visibility
    // metadata (KVisibility.INTERNAL) can verify the doctrine in
    // TokenFlowSandboxTest that TokenFlowEngine has no public constructor.
    testImplementation(libs.kotlin.reflect)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
