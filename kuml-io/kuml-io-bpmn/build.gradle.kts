plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-metamodel:kuml-metamodel-bpmn"))
    implementation(kotlin("stdlib"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-metamodel:kuml-metamodel-bpmn"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
