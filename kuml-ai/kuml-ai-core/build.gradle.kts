plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // ── Published SPI (api: transitively exposed to consumers) ──────────────
    api(project(":kuml-ai:kuml-ai-spi")) // V3.1.15

    // ── Koog (JetBrains AI Agent Framework, Maven Central) ──────────────────
    // koog-agents-jvm is api (transitively exposed) because KumlLlmProvider exposes
    // LLMProvider in its public API, and KumlAiExecutor exposes LLModel/LLMClient.
    api(libs.koog.agents.jvm)

    // Provider client artifacts: runtimeOnly — built-ins instantiate them
    // reflectively so the compile classpath is client-free (tree-shaking).
    // Consumers who want to exclude a provider can remove the client JAR at
    // packaging time; only that provider's factory will fail lazily.  V3.1.15
    runtimeOnly(libs.koog.prompt.executor.openai.client)
    runtimeOnly(libs.koog.prompt.executor.anthropic.client)
    runtimeOnly(libs.koog.prompt.executor.google.client)
    runtimeOnly(libs.koog.prompt.executor.ollama.client)

    // ── kUML-internal ────────────────────────────────────────────────────────
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)

    // ── Optional: JNA for platform keystore access (Windows DPAPI) ──────────
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // ── Tests ────────────────────────────────────────────────────────────────
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Xmx512m")
    // Privacy-Mode tests run locally; live provider tests are opt-in
    systemProperty("kuml.ai.test.live", System.getProperty("kuml.ai.test.live") ?: "false")
    // Exclude @Tag("live") tests unless explicitly opted in
    val liveEnabled = System.getProperty("kuml.ai.test.live") == "true"
    useJUnitPlatform {
        if (!liveEnabled) {
            excludeTags("live")
        }
    }
}
