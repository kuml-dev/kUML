plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// V3.0.20 — Move-VM-Adapter (Sui JSON-RPC + Aptos REST). JVM-only, KEIN GraalVM-Native-Anspruch.
// - Sui: JSON-RPC 2.0 über java.net.http.HttpClient (analog EVM)
// - Aptos: REST/GET über java.net.http.HttpClient
// - kotlinx-serialization-json zum (De-)Serialisieren
// - java.util.Base64 für BCS-Event-Daten-Dekodierung (Sui)
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
