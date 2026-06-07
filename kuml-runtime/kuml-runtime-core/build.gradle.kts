plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    // V2.0.17 — SysML 2 STM ↔ Behaviour-Runtime adapter consumes Sysml2Model / StmDiagram
    api(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    api(project(":kuml-core:kuml-core-model"))
    api(project(":kuml-core:kuml-core-ocl"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
