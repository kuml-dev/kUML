plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(project(":kuml-plugin-api:kuml-plugin-api-theme"))
    implementation(project(":kuml-plugin-api:kuml-plugin-api-core"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
