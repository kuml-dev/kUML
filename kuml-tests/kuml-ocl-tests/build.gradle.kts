plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(project(":kuml-core:kuml-core-ocl"))
    testImplementation(project(":kuml-core:kuml-core-model"))
    testImplementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
