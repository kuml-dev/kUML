plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // for the vocabulary JSON contract test
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Public surface: KumlCodeBlock is exposed on OkfDocument.kumlBlocks
    api(project(":kuml-docs:kuml-markdown"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}
