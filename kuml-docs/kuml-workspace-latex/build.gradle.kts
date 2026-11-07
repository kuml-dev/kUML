plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // End-user distributed CLI code path -> JDK 21 (see JDK version policy in the vault CLAUDE.md).
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Public surface: OkfWorkspace / OkfDocument appear on the exporter API.
    api(project(":kuml-docs:kuml-workspace"))
    // ExportScope, ExportMetadata, PdfLanguage and the neutral source model live in the PDF module (no PDFBox types
    // are part of that API); the Markdown parser exists exactly once.
    api(project(":kuml-docs:kuml-workspace-pdf"))
    // LatexFamily is part of the diagram port.
    api(project(":kuml-io:kuml-io-latex"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx1g", "-Djava.awt.headless=true")
}
