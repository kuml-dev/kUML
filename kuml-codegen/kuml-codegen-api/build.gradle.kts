plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-core:kuml-core-model"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // For the loadFromClasspath test — picks up the kotlin provider from
    // the built-in plugin's META-INF/services file.
    testImplementation(project(":kuml-codegen:kuml-gen-kotlin"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
