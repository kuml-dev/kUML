plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":kuml-metamodel:kuml-metamodel-kerml"))
    implementation(project(":kuml-core:kuml-core-model"))
    // V2.0.20b — constraint type-checking uses the typed expression AST
    implementation(project(":kuml-core:kuml-core-expr"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
