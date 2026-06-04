plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kuml-profile:kuml-profile-soaml"))
    implementation(project(":kuml-profile:kuml-profile-javaee"))
    implementation(project(":kuml-profile:kuml-profile-spring"))
    implementation(project(":kuml-profile:kuml-profile-openapi"))
    implementation(project(":kuml-profile:kuml-profile-autosar"))
    implementation(project(":kuml-core:kuml-core-dsl"))
}
