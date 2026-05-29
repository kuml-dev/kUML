plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
