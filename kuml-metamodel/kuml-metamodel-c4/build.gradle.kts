plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":kuml-core:kuml-core-model"))
    implementation(libs.kotlinx.serialization.json)
}
