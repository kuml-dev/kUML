plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("dev.kuml.cli.MainKt")
    applicationName = "kuml"
}

dependencies {
    implementation(libs.clikt)
    implementation(project(":kuml-core:kuml-core-ocl"))
    implementation(libs.kotlinx.serialization.json)

    // Full pipeline dependencies
    implementation(project(":kuml-core:kuml-core-script"))
    // Scripting API needed to access ResultWithDiagnostics / ResultValue
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Reflection for script instance property scanning
    implementation(libs.kotlin.reflect)
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    implementation(project(":kuml-io:kuml-io-svg"))
    implementation(project(":kuml-io:kuml-io-png"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Script compilation is memory-intensive
    jvmArgs("-Xmx512m")
}
