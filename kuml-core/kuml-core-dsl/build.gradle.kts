plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":kuml-core:kuml-core-model"))
    // Kotlin K2 (2.4.0+) is strict about implementation visibility: DSL entry-points
    // like `umlModel{}` / `c4Model{}` return types from these metamodels, so they
    // must be on the compile classpath of downstream modules — hence `api`, not
    // `implementation`. Same goes for the profile API surface.
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))
    api(project(":kuml-profile:kuml-profile-api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
