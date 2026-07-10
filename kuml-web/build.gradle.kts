plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.kuml.web.MainKt")
    applicationName = "kuml-web"
}

dependencies {
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-core:kuml-core-script"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2"))
    implementation(project(":kuml-metamodel:kuml-metamodel-kerml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN web render
    implementation(project(":kuml-metamodel:kuml-metamodel-erm")) // V3.4.x — ERM web render
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-grid"))
    implementation(project(":kuml-renderer:kuml-themes"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-io:kuml-io-latex"))

    // Scripting (needed for KumlScriptHost)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.reflect)

    // Ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)

    // Clikt (CLI for kuml serve)
    implementation(libs.clikt)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}

// ─────────────────────────────────────────────────────────────────────────────
// Gradle 9 strict duplicate handling — kuml-web transitively pulls in
// runtime-desktop-<version>.jar via both :kuml-renderer:kuml-themes (Compose
// KMP JVM target) and :kuml-io:kuml-io-png. Without an explicit strategy the
// distTar / distZip tasks fail with "Entry … is a duplicate but no duplicate
// handling strategy has been set." EXCLUDE keeps the first copy encountered.
// ─────────────────────────────────────────────────────────────────────────────
distributions {
    main {
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
