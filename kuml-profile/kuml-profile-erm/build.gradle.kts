plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-profile:kuml-profile-api"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-core:kuml-core-dsl"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
