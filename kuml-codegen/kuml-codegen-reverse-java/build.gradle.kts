plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-codegen:kuml-codegen-reverse-api"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))

    // ── JavaParser (com.github.javaparser, Maven Central) ─────────────────────
    implementation(libs.javaparser.core)
    implementation(libs.javaparser.symbol.solver.core)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xmx1g") // JavaParser Symbol Solver needs heap for JRE reflection resolver
}
