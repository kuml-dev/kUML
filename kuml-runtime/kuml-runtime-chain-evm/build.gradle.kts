plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// V3.0.2 — EVM-Adapter (JVM-only, KEIN GraalVM-Native-Anspruch):
// - raw JSON-RPC über java.net.http.HttpClient (JDK 21, keine Web3j-Dependency)
// - kotlinx-serialization-json zum (De-)Serialisieren der RPC-Envelopes
// - keccak256 + secp256k1-ecrecover pure-Kotlin (kein Bouncy Castle im Classpath)
dependencies {
    api(project(":kuml-runtime:kuml-runtime-chain-api")) // KumlChainAdapter, ChainEvent, BlockClock, ContractIdentity

    implementation(libs.kotlinx.coroutines.core) // Flow + suspend bridge
    implementation(libs.kotlinx.serialization.json) // JSON-RPC envelope (de)serialization

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test) // runTest{} für suspend/Flow-Tests
    // HTTP-Mock via com.sun.net.httpserver.HttpServer — Teil der JVM, kein Dep nötig.
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
