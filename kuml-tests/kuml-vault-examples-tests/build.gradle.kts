plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-core:kuml-core-script"))
    testImplementation(project(":kuml-core:kuml-core-dsl"))
    testImplementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    testImplementation(project(":kuml-metamodel:kuml-metamodel-c4"))
    testImplementation(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    testImplementation(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN vault examples
    testImplementation(project(":kuml-metamodel:kuml-metamodel-erm")) // V3.4 — ERM vault example
    testImplementation(project(":kuml-renderer:kuml-layout-api"))
    testImplementation(project(":kuml-renderer:kuml-layout-bridge"))
    testImplementation(project(":kuml-renderer:kuml-layout-elk"))
    testImplementation(project(":kuml-renderer:kuml-layout-grid"))
    testImplementation(project(":kuml-renderer:kuml-themes-core"))
    testImplementation(project(":kuml-renderer:kuml-themes"))
    testImplementation(project(":kuml-io:kuml-io-svg"))
    testImplementation(project(":kuml-io:kuml-render-smil")) // V3.1.32 — SMIL vault examples
    testImplementation(project(":kuml-io:kuml-io-png"))
    testImplementation(project(":kuml-io:kuml-io-latex"))
    testImplementation(project(":kuml-io:kuml-io-arxml")) // V3.1.36 — ArxmlComponentRenderTest
    testImplementation(project(":kuml-io:kuml-io-anim")) // V3.2 — APNG/WebP sample output in SMIL tests
    // kotlin-scripting-* are implementation (not api) in kuml-core-script,
    // so we need to list them here explicitly for access to ResultWithDiagnostics etc.
    testImplementation(libs.kotlin.scripting.common)
    testImplementation(libs.kotlin.scripting.jvm)
    testImplementation(libs.kotlin.scripting.jvm.host)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx1g")
    // Declare sample-output as an explicit task output so that:
    //   • Gradle build cache stores SVGs/PNGs/index alongside test results
    //   • `clean` removes it correctly (it is inside build/ anyway, but the
    //     declaration makes the relationship explicit for cache restore)
    //   • A cache hit restores sample-output instead of silently leaving it absent
    outputs.dir(layout.buildDirectory.dir("sample-output"))
}

// The vault example .md files live under src/test/resources/vault-examples/
// and are committed to the repository. They are the canonical inputs to the
// render-smoke-test suite and need to be present for CI builds.
//
// The vault in Obsidian (~/Documents/Obsidian/Zweites Gehirn/03 Bereiche/kUML/Beispiele)
// is the source of truth for content. Use the sync-vault-examples script
// (scripts/sync-vault-examples.sh in the repo root) to copy fresh examples
// from the vault into this resource directory whenever vault content changes.
//
// The vault-examples-index.md (mapping of vault file → test case → output base)
// is written by VaultExamplesRenderTest itself in an afterSpec block — this
// keeps the build script free of custom Gradle tasks that would otherwise hit
// the Gradle 9 configuration-cache issue with script-object references
// (see CLAUDE.md → "kuml-packaging Exec-Task doFirst" entry for the same root
// cause). The index lands in build/sample-output/vault-examples-index.md.
