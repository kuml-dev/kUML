plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-api"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
