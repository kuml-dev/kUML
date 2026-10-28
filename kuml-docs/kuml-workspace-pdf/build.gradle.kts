plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // End-user distributed CLI code path -> JDK 21 (see JDK version policy in the vault CLAUDE.md).
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Public surface: OkfWorkspace / OkfDocument / OkfFinding appear on the exporter API.
    api(project(":kuml-docs:kuml-workspace"))

    implementation(libs.pdfbox)
    implementation(libs.pdfbox.xmpbox)
    implementation(libs.pdfbox.graphics2d)
    implementation(libs.batik.bridge)
    implementation(libs.batik.anim)
    implementation(libs.batik.transcoder)
    implementation(libs.jetbrains.markdown)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx1g", "-Djava.awt.headless=true")
}
