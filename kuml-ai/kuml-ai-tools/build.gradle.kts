plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // ── kUML-internal — Core, Metamodelle, Renderer, Runtime ────────────────
    api(project(":kuml-ai:kuml-ai-core"))
    api(project(":kuml-core:kuml-core-model"))
    api(project(":kuml-core:kuml-core-dsl"))
    api(project(":kuml-core:kuml-core-script"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))
    api(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    api(project(":kuml-runtime:kuml-runtime-core"))

    // Rendering: only needed for RenderingTools
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-grid"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-io:kuml-io-json"))

    // MCP Bridge — reflection-based in-process bridge to :kuml-mcp tools.
    // kuml-mcp is application-typed with internal visibilities; we bridge
    // via reflection to avoid making the entire module public. See
    // InProcessMcpTransport.kt for the migration path note.
    implementation(project(":kuml-mcp"))

    // V3.0.25 — Sandbox validator (V2.0.40) + trace entries (V2.0.39).
    implementation(project(":kuml-runtime:kuml-runtime-sandbox"))
    implementation(project(":kuml-runtime:kuml-runtime-trace"))
    // kuml-runtime-core is already transitive via api() above.

    // ── Koog ─────────────────────────────────────────────────────────────────
    implementation(libs.koog.agents.jvm)
    // MCP integration module (McpToolRegistryProvider, McpServerInfo).
    implementation(libs.koog.agents.mcp)

    // ── Serialization / Coroutines ──────────────────────────────────────────
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)

    // ── SQLite (PersistentPatchStore — V3.1.19) ───────────────────────────
    implementation(libs.sqlite.jdbc)

    // ── Tests ────────────────────────────────────────────────────────────────
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":kuml-tests:kuml-renderer-tests"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Xmx768m")
    val liveEnabled = System.getProperty("kuml.ai.test.live") == "true"
    useJUnitPlatform {
        if (!liveEnabled) {
            excludeTags("live")
        }
    }
}
