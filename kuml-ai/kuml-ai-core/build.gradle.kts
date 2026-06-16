plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // ── Koog (JetBrains AI Agent Framework, Maven Central) ──────────────────
    implementation(libs.koog.agents.jvm)
    implementation(libs.koog.prompt.executor.openai.client)
    implementation(libs.koog.prompt.executor.anthropic.client)
    implementation(libs.koog.prompt.executor.google.client)
    implementation(libs.koog.prompt.executor.ollama.client)

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
