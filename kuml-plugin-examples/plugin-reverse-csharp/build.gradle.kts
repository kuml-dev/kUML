plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(project(":kuml-plugin-api:kuml-plugin-api-reverse"))
    implementation(project(":kuml-plugin-api:kuml-plugin-api-core"))
    implementation(project(":kuml-codegen:kuml-codegen-reverse-api"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
