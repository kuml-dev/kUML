plugins {
    // `java-gradle-plugin` is applied explicitly and FIRST, before
    // `kotlin.jvm`, so that the root build.gradle.kts's
    // `pluginManager.withPlugin("java-gradle-plugin")` publication branch
    // fires before the `pluginManager.withPlugin("org.jetbrains.kotlin.jvm")`
    // branch's `hasPlugin("java-gradle-plugin")` guard is evaluated — the
    // guard only sees a correct answer if java-gradle-plugin is already
    // applied by the time kotlin.jvm applies. Getting this order wrong makes
    // `configureKumlPublishing()` run twice for this module (once from each
    // branch), which fails with "The value for this property is final and
    // cannot be changed any further" on the second `publishToMavenCentral()`
    // call.
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    alias(libs.plugins.gradle.plugin.publish)
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
    implementation(project(":kuml-metamodel:kuml-metamodel-bpmn")) // V3.1.6 — BPMN in gradle pipeline
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
                "Renders *.kuml.kts diagrams (UML & C4) to SVG/PNG, generates code " +
                "(Kotlin/Java/SQL) and validates OCL constraints from the Gradle build."
            tags = listOf("kuml", "uml", "c4", "documentation", "diagram", "codegen", "ocl")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx1024m")
}
