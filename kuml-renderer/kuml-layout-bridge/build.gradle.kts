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
    api(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.4 — SysML 2 BDD-Bridge
    implementation(project(":kuml-core:kuml-core-model"))
    // Reflection für LayoutHintWriter.copyWithMetadata — data-class copy() via KFunction.callBy
    implementation(libs.kotlin.reflect)
    // Layout-Keys aus dem DSL-Modul werden NICHT verlinkt — String-Literale duplizieren wir lokal
    // in BridgeLayoutKeys (see spec: „Vertrag in Worten" #6)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.serialization.json)
    // V2.0.26 — LayoutEngineSelectionTest braucht beide Engine-Provider
    testImplementation(project(":kuml-renderer:kuml-layout-grid"))
    testImplementation(project(":kuml-renderer:kuml-layout-elk"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
