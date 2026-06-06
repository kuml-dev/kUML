plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-renderer:kuml-layout-api"))
    // Bewusst KEINE ELK-/EMF-/Xtext-Abhängigkeiten — die ganze Engine ist
    // pure-Kotlin und damit GraalVM-Native-Image-tauglich (V1.1 §"Grid-Layout-
    // Engine").
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
