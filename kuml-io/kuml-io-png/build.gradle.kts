plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-io:kuml-io-svg"))
    api(project(":kuml-renderer:kuml-layout-api"))
    api(project(":kuml-renderer:kuml-themes-core"))
    api(project(":kuml-metamodel:kuml-metamodel-uml"))
    api(project(":kuml-metamodel:kuml-metamodel-c4"))

    implementation(libs.batik.transcoder)
    implementation(libs.batik.codec)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
