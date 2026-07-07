plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Exposed extends JavaEE — published API so consumers get javaEeProfile transitively
    api(project(":kuml-profile:kuml-profile-javaee"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(project(":kuml-core:kuml-core-dsl"))
    testImplementation(project(":kuml-io:kuml-io-svg"))
    testImplementation(project(":kuml-renderer:kuml-themes-core"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
