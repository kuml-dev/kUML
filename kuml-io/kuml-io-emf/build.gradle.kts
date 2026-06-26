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
    // Profile API — pure Kotlin, no EMF dep; only the bridge (this module) touches EMF
    implementation(project(":kuml-profile:kuml-profile-api"))

    // Eclipse EMF / UML2 — JVM-only, NIEMALS in kuml-cli oder kuml-packaging einlinken
    implementation(libs.eclipse.emf.ecore)
    implementation(libs.eclipse.emf.ecore.xmi)
    implementation(libs.eclipse.uml2.uml)
    // Transitive UML2 deps — nicht automatisch aufgelöst da Eclipse-Maven-POMs keine Deps deklarieren
    runtimeOnly(libs.eclipse.uml2.types)
    runtimeOnly(libs.eclipse.uml2.common)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // Profile implementations for round-trip tests
    testImplementation(project(":kuml-profile:kuml-profile-autosar"))
    testImplementation(project(":kuml-profile:kuml-profile-spring"))
    testImplementation(project(":kuml-profile:kuml-profile-javaee"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
