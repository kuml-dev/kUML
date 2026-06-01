plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-renderer:kuml-layout-api"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))
    implementation(project(":kuml-core:kuml-core-model"))
    // Layout-Keys aus dem DSL-Modul werden NICHT verlinkt — String-Literale duplizieren wir lokal
    // in BridgeLayoutKeys (see spec: „Vertrag in Worten" #6)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
