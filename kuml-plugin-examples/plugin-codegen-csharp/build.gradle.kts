plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    implementation(project(":kuml-plugin-api:kuml-plugin-api-codegen"))
    implementation(project(":kuml-plugin-api:kuml-plugin-api-core"))
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
