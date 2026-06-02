plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // kuml-core-model and kuml-core-dsl must be on the script classpath
    // so that defaultImports (io.kuml.core.model.*, io.kuml.core.dsl.*) resolve.
    api(project(":kuml-core:kuml-core-model"))
    api(project(":kuml-core:kuml-core-dsl"))

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
