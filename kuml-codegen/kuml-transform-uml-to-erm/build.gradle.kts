plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-m2m"))
    api(project(":kuml-metamodel:kuml-metamodel-erm"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-profile:kuml-profile-erm")) // ErmProfileNames constants
    implementation(project(":kuml-profile:kuml-profile-api")) // AppliedStereotype/TagValue reading
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-core:kuml-core-dsl")) // UML-DSL zum Fixtures-Bauen
    testImplementation(project(":kuml-metamodel:kuml-metamodel-uml"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
