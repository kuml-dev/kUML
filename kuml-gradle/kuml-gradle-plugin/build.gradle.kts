plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Runtime dependencies — same set the CLI uses for render/generate/validate.
    // These get embedded in the plugin JAR when published.
    implementation(project(":kuml-core:kuml-core-script"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-core:kuml-core-ocl"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))
    implementation(project(":kuml-metamodel:kuml-metamodel-sysml2")) // V2.0.6 — SysML 2 BDD/IBD in gradle pipeline
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    // Built-in code generators discovered via ServiceLoader at runtime.
    runtimeOnly(project(":kuml-codegen:kuml-gen-kotlin"))
    runtimeOnly(project(":kuml-codegen:kuml-gen-java"))
    runtimeOnly(project(":kuml-codegen:kuml-gen-sql"))

    // Kotlin scripting host bits.
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://kuml.dev"
    vcsUrl = "https://github.com/kuml-dev/kUML"
    plugins {
        create("kumlPlugin") {
            id = "dev.kuml"
            implementationClass = "dev.kuml.gradle.KumlPlugin"
            displayName = "kUML Gradle Plugin"
            description =
                "Rendert *.kuml.kts-Diagramme (UML & C4) zu SVG/PNG, generiert Code (Kotlin/Java/SQL) " +
                "und validiert OCL-Constraints aus dem Gradle-Build heraus."
            tags = listOf("kuml", "uml", "c4", "documentation", "diagram", "codegen", "ocl")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx1024m")
}
