plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-plugin-api:kuml-plugin-api-core"))
    api(project(":kuml-codegen:kuml-codegen-reverse-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
