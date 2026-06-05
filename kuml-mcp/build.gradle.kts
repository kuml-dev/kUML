plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

application {
    mainClass.set("dev.kuml.mcp.MainKt")
    applicationName = "kuml-mcp"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":kuml-codegen:kuml-codegen-api"))
    implementation(project(":kuml-codegen:kuml-gen-kotlin"))
    implementation(project(":kuml-codegen:kuml-gen-java"))
    implementation(project(":kuml-codegen:kuml-gen-sql"))

    implementation(project(":kuml-core:kuml-core-script"))
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.reflect)
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-core:kuml-core-ocl"))
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
    jvmArgs("-Xmx512m")
}
