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
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-profile:kuml-profile-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-profile:kuml-profile-exposed"))
    testImplementation(project(":kuml-codegen:kuml-gen-sql"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
