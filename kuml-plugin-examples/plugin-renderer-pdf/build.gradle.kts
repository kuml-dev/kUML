plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(project(":kuml-plugin-api:kuml-plugin-api-renderer"))
    implementation(project(":kuml-plugin-api:kuml-plugin-api-core"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation("org.apache.pdfbox:pdfbox:3.0.2")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
