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
    api(project(":kuml-metamodel:kuml-metamodel-erm")) // V3.4.8 — ErmModel types in ErmToExposedTransformer/ErmExposedGenerator signatures
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-profile:kuml-profile-api"))
    implementation(project(":kuml-codegen:kuml-codegen-api")) // V3.4.8 — ErmCodeGenerator/ErmCodeGeneratorProvider/CodeGenerationException
    implementation(project(":kuml-codegen:kuml-transform-uml-to-erm")) // V3.4.8 — UmlToErmTransformer for the chain wrapper
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-profile:kuml-profile-exposed"))
    testImplementation(project(":kuml-codegen:kuml-gen-sql"))
    testImplementation(project(":kuml-core:kuml-core-dsl")) // V3.4.8 — UML/ERM fixtures for erm-to-exposed + chain tests
    testImplementation(project(":kuml-profile:kuml-profile-erm")) // V3.4.8 — ermMappingProfile for UML-fixture-based chain test
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
