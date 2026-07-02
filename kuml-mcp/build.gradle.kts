plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("dev.kuml.mcp.MainKt")
    applicationName = "kuml-mcp"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-codegen:kuml-gen-kotlin"))
    implementation(project(":kuml-codegen:kuml-gen-java"))
    implementation(project(":kuml-codegen:kuml-gen-sql"))

    implementation(project(":kuml-core:kuml-core-script"))
    // V2.0.27 — Behaviour-Runtime MCP tools
    implementation(project(":kuml-runtime:kuml-runtime-core"))
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.reflect)
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-core:kuml-core-ocl"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}

// ─────────────────────────────────────────────────────────────────────────────
// V3.2.17 — Bundle the DSL reference handbook pages and curated vault examples
// as classpath resources for the `kuml://dsl/reference` and `kuml://dsl/examples`
// MCP resources (see ResourceRegistry.kt). Declarative `from(...)` copy specs
// (not a custom `doLast`/`doFirst` task) keep this Configuration-Cache-safe —
// see CLAUDE.md "kuml-packaging Exec-Task doFirst" pitfall.
//
// - reference: the four DSL-reference handbook pages (not authoring-mcp.adoc,
//   which documents the MCP server itself, not the DSL).
// - examples: the curated vault-examples Markdown files, sourced from the
//   `kuml-vault-examples-tests` module's test resources (already the
//   classpath-resource mirror of the vault per CLAUDE.md's Classpath-Resource
//   rule — never read absolute vault paths here).
// ─────────────────────────────────────────────────────────────────────────────
val handbookReferenceDir = rootProject.layout.projectDirectory.dir("docs/handbook/modules/reference/pages")
val vaultExamplesDir =
    rootProject.layout.projectDirectory.dir(
        "kuml-tests/kuml-vault-examples-tests/src/test/resources/vault-examples",
    )

tasks.named<ProcessResources>("processResources") {
    from(handbookReferenceDir) {
        include("uml-dsl.adoc", "sysml2.adoc", "c4-dsl.adoc", "bpmn-dsl.adoc")
        into("dsl/reference")
    }
    from(vaultExamplesDir) {
        include("*.md")
        into("dsl/examples")
    }
}
