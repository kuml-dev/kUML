plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // V3.0.3 — KumlBackedContractSpec JSON-Serialisierung
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// Pure-Kotlin, Native-Image-tauglich:
// - keine projekt-internen Modul-Deps (kein kuml-metamodel/kuml-core),
// - keine JVM-only-Libraries (kein Batik, ELK, EMF, Ktor),
// - kotlinx-coroutines-core (Flow) + java.security.MessageDigest (GraalVM-supported),
// - kotlinx-serialization-json (V3.0.3): Native-Image-safe — der Kotlin-Compiler-Plugin
//   generiert Serializer statisch; kein Reflection-Zwang bei @Serializable-Klassen.
dependencies {
    api(libs.kotlinx.coroutines.core) // Flow<ChainEvent> in KumlChainAdapter
    api(libs.kotlinx.serialization.json) // V3.0.3 — KumlBackedContractSpec JSON-Serialisierung

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test) // runTest{} für Flow-Contract-Tests
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
