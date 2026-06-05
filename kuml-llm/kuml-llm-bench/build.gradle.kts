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
    mainClass.set("dev.kuml.llm.bench.MainKt")
    applicationName = "kuml-bench"
}

dependencies {
    implementation(project(":kuml-llm:kuml-llm-core"))
    implementation(project(":kuml-llm:kuml-llm-anthropic"))
    implementation(project(":kuml-core:kuml-core-script"))
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
}
