// JDOM2 + AUTOSAR ARXML — JVM-only, NIEMALS in kuml-cli (GraalVM Native Image) oder kuml-packaging einlinken — analog kuml-io-emf.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-profile:kuml-profile-autosar"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2"))

    // JDOM2 XML — JVM-only, NIEMALS in kuml-cli oder kuml-packaging einlinken
    implementation(libs.jdom2)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
