plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-reverse-api"))
    implementation(project(":kuml-metamodel:kuml-metamodel-erm"))
    implementation(project(":kuml-core:kuml-core-model"))

    // ── JSqlParser (com.github.jsqlparser, Maven Central) ──────────────────────
    implementation(libs.jsqlparser)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
