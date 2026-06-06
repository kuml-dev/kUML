plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kuml-profile:kuml-profile-soaml"))
    implementation(project(":kuml-profile:kuml-profile-javaee"))
    implementation(project(":kuml-profile:kuml-profile-spring"))
    implementation(project(":kuml-profile:kuml-profile-openapi"))
    implementation(project(":kuml-profile:kuml-profile-autosar"))
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.3 — Hybrid-Vehicle SysML 2 BDD

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-io:kuml-io-svg"))
    testImplementation(project(":kuml-renderer:kuml-themes-core"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
