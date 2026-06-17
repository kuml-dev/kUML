plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-plugin-api:kuml-plugin-api-core"))
    api(project(":kuml-plugin-api:kuml-plugin-api-theme"))
    api(project(":kuml-plugin-api:kuml-plugin-api-renderer"))
    api(project(":kuml-plugin-api:kuml-plugin-api-layout"))
    api(project(":kuml-plugin-api:kuml-plugin-api-codegen"))
    api(project(":kuml-plugin-api:kuml-plugin-api-reverse"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-codegen:kuml-codegen-reverse-api"))

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
