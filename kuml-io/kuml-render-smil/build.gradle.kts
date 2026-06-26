plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-runtime:kuml-runtime-core")) // TraceEntry, TraceFile, Trace
    implementation(kotlin("stdlib"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-runtime:kuml-runtime-core"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
