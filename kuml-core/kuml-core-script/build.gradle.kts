plugins {
    alias(libs.plugins.kotlin.jvm)
    // V0.23.3 — the child-process script sandbox serializes the extracted
    // diagram/model across the IPC boundary (ExtractedDiagramCodec) and uses
    // @Serializable worker request/response envelopes (ScriptWorkerProtocol).
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN CLI-Integration
    api(project(":kuml-metamodel:kuml-metamodel-blueprint")) // V3.1.24 — Blueprint/Journey-Map-Extraktion

    // V0.23.3 — JSON (de)serialization for the child-process IPC protocol.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // kotlin-reflect required by DiagramExtractor (script instance property scanning)
    implementation(libs.kotlin.reflect)

    // V0.23.3 — Welle 6: Windows OS-native isolation (Job Object) via JNA.
    // The JNA declarations compile on every OS (plain Java bindings); they are
    // only bound to kernel32.dll at runtime, which can only succeed on Windows.
    // Same JNA pattern already used by kuml-ai-core's WindowsDpapiBackend.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx512m")
}
