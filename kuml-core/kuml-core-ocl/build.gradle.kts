plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-profile:kuml-profile-api"))
    // V3.2.23 — OclValidator gains BPMN + SysML 2 branches (OCL over BpmnProcess /
    // PartDefinition constraints), analogous to the existing UML branch.
    implementation(project(":kuml-metamodel:kuml-metamodel-bpmn"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
