plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-renderer:kuml-layout-api"))
    api(project(":kuml-renderer:kuml-themes-core"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-core:kuml-core-model"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
