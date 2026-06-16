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

    // Eclipse EMF / UML2 — JVM-only, NIEMALS in kuml-cli oder kuml-packaging einlinken
    implementation(libs.eclipse.emf.ecore)
    implementation(libs.eclipse.emf.ecore.xmi)
    implementation(libs.eclipse.uml2.uml)
    // Transitive UML2 deps — nicht automatisch aufgelöst da Eclipse-Maven-POMs keine Deps deklarieren
    runtimeOnly(libs.eclipse.uml2.types)
    runtimeOnly(libs.eclipse.uml2.common)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
