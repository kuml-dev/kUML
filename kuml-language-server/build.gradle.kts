plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.kuml.lsp.MainKt")
    // MUST stay "kuml-lsp": KumlCliLocator.resolveLsp walks to
    // kuml-language-server/build/install/kuml-lsp/bin, and a distinct name
    // keeps installDist from overwriting kuml-cli/build/install/kuml/bin/kuml
    // (the Obsidian plugin's dependency path).
    applicationName = "kuml-lsp"
}

dependencies {
    implementation(project(":kuml-lang-support"))
    implementation(libs.lsp4j)

    testImplementation(libs.lsp4j) // client launcher used by the boot test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// ─────────────────────────────────────────────────────────────────────────────
// Gradle 9 strict duplicate handling (preemptive guard). Even though this
// module has no Compose deps today, the `application` plugin's distTar/distZip
// tasks fail hard on any duplicate entry. EXCLUDE keeps the first copy.
// ─────────────────────────────────────────────────────────────────────────────
distributions {
    main {
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
