plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

description = "Kotlin source → UML reverse engineering engine (PSI-based)"

dependencies {
    api(project(":kuml-codegen:kuml-codegen-reverse-api"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("idea.use.native.fs.for.win", "false")
    systemProperty("idea.io.use.nio2", "true")
}
