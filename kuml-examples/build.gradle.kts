plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kuml-profile:kuml-profile-soaml"))
    implementation(project(":kuml-profile:kuml-profile-javaee"))
    implementation(project(":kuml-profile:kuml-profile-spring"))
    implementation(project(":kuml-profile:kuml-profile-openapi"))
    implementation(project(":kuml-profile:kuml-profile-autosar"))
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.3 — Hybrid-Vehicle SysML 2 BDD
    implementation(project(":kuml-codegen:kuml-codegen-m2m")) // V2.0.25 — C4→UML transformer example

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-io:kuml-io-svg"))
    testImplementation(project(":kuml-renderer:kuml-themes-core"))
    testImplementation(project(":kuml-runtime:kuml-runtime-chain-api")) // V3.0.6 — DAP Verfassungs-Showcase
    testImplementation(project(":kuml-runtime:kuml-runtime-chain-evm")) // V3.0.6 — DAP Verfassungs-Showcase

    // V3.6.3 — FT-3 sample-association-charter OKF demo workspace: scan + validate + render
    testImplementation(project(":kuml-docs:kuml-workspace")) // WorkspaceScanner / OkfValidator / OkfType
    testImplementation(project(":kuml-core:kuml-core-script")) // KumlScriptHost / DiagramExtractor / ExtractedDiagram
    testImplementation(project(":kuml-metamodel:kuml-metamodel-uml")) // classDiagram / stateDiagram runtime
    testImplementation(project(":kuml-metamodel:kuml-metamodel-bpmn")) // bpmnModel runtime
    testImplementation(project(":kuml-renderer:kuml-layout-api"))
    testImplementation(project(":kuml-renderer:kuml-layout-bridge"))
    testImplementation(project(":kuml-renderer:kuml-layout-elk"))
    testImplementation(project(":kuml-renderer:kuml-layout-grid"))
    testImplementation(project(":kuml-renderer:kuml-themes")) // theme content for ThemeRegistry.loadFromClasspath
    // kotlin-scripting-* are implementation (not api) in kuml-core-script, so we need
    // them here explicitly for access to ResultWithDiagnostics etc. (mirrors
    // kuml-vault-examples-tests' build.gradle.kts).
    testImplementation(libs.kotlin.scripting.common)
    testImplementation(libs.kotlin.scripting.jvm)
    testImplementation(libs.kotlin.scripting.jvm.host)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
