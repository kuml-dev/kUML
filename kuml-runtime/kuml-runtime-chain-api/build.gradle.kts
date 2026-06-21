plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

// Pure-Kotlin, Native-Image-tauglich:
// - keine projekt-internen Modul-Deps (kein kuml-metamodel/kuml-core),
// - keine JVM-only-Libraries (kein Batik, ELK, EMF, Ktor),
// - nur kotlinx-coroutines-core (Flow) + java.security.MessageDigest (GraalVM-supported).
dependencies {
    api(libs.kotlinx.coroutines.core) // Flow<ChainEvent> in KumlChainAdapter

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test) // runTest{} für Flow-Contract-Tests
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
