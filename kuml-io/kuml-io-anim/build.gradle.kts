plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-io:kuml-io-png"))
    api(project(":kuml-io:kuml-render-smil"))

    implementation(libs.batik.transcoder)
    implementation(libs.batik.codec)
    implementation(libs.batik.bridge)
    implementation(libs.batik.anim)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
