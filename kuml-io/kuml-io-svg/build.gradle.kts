plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-renderer:kuml-layout-api"))
    api(project(":kuml-renderer:kuml-layout-bridge")) // IBD port enrichment (V3.1.x)
    api(project(":kuml-renderer:kuml-themes-core"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))
    api(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.4 — SysML 2 BDD-Rendering
    api(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.3 — BPMN Process SVG-Renderer
    api(project(":kuml-metamodel:kuml-metamodel-blueprint")) // V3.1.23 — Blueprint-Rendering
    implementation(project(":kuml-core:kuml-core-model"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // V1.1: stereotype render tests use KumlStereotypeApplication from kuml-profile-api
    testImplementation(project(":kuml-profile:kuml-profile-api"))
    // PNG co-generation in SampleOutput: converts each SVG sample to PNG for visual regression
    testImplementation(project(":kuml-io:kuml-io-png"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
