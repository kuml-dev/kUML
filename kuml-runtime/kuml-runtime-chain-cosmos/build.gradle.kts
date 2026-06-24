plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// V3.0.21 — WASM-Chain-Adapter (CosmWasm Cosmos-SDK + Substrate/ink!). JVM-only, KEIN GraalVM-Native-Anspruch.
// - CosmWasm: Tendermint/Cosmos JSON-RPC 2.0 über java.net.http.HttpClient (POST), wasm-Event-Attribute
// - Substrate: substrate JSON-RPC 2.0 über java.net.http.HttpClient (POST), ContractEmitted-Events
// - kotlinx-serialization-json zum (De-)Serialisieren der JSON-RPC-Envelopes
// - java.util.Base64 für Cosmos-Event-Attribut-Werte (base64) bzw. inline-Hex/SCALE-Mini-Decoder (Substrate)
// Bewusst KEIN OkHttp / web3j: konsistent mit chain-evm und chain-move (raw java.net.http.HttpClient,
// keine zusätzliche Dependency, kleinere Angriffsfläche).
dependencies {
    api(project(":kuml-runtime:kuml-runtime-chain-api")) // KumlChainAdapter, ChainEvent, BlockClock, ContractIdentity

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // HTTP-Mock via com.sun.net.httpserver.HttpServer — Teil der JVM, kein Dep nötig.
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
