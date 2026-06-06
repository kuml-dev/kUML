plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // kuml-core-model and kuml-core-dsl must be on the script classpath
    // so that defaultImports (dev.kuml.core.model.*, dev.kuml.core.dsl.*) resolve.
    // UML + C4 metamodels added (V1.0) so c4Model { … } and the UML enums work
    // without explicit `import dev.kuml.c4.dsl.*` in *.kuml.kts scripts.
    api(project(":kuml-core:kuml-core-model"))
    api(project(":kuml-core:kuml-core-dsl"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))
    api(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.4 — SysML 2 BDD-Extraktion

    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // kotlin-reflect required by DiagramExtractor (script instance property scanning)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx512m")
}
