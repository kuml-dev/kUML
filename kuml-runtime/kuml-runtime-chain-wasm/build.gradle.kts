plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// V3.0.22 — Generic WASM contract adapter (Polkadot/Substrate ink! primary, CosmWasm secondary via bridge).
//
// Design split:
//   * CORE utilities (ScaleCodec, InkAbiMetadata, InkEventDecoder) are PURE Kotlin — no java.net.http,
//     no kotlinx-coroutines in the hot path — so they remain GraalVM-Native-Image-tauglich and can be
//     reused by plugin authors who roll their own chain adapter. They depend ONLY on kotlinx-serialization-json
//     (used purely for parsing the ink! metadata JSON document; SCALE itself is hand-rolled byte work).
//   * RPC adapter (SubstrateWasmAdapter, WasmAdapterFactory) is JVM-only: java.net.http.HttpClient + kotlinx-json,
//     analogous to the EVM/Move/Cosmos adapters. NOT GraalVM-Native-tauglich (HttpClient reflection).
//
// This module deliberately ships NO heavy Substrate/Polkadot SDK. SCALE codec + ink! ABI parsing are
// implemented from scratch so the dependency footprint stays tiny and Native-Image friendly.
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
