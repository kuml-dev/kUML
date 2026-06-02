plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":kuml-renderer:kuml-layout-api"))
    implementation(libs.elk.core)
    implementation(libs.elk.alg.layered)
    // ELK 0.11.0 generierter Code verweist auf Xtext-Runtime-Klassen
    // (org.eclipse.xtext.xbase.lib.CollectionLiterals). Das POM von
    // alg.layered listet diese Abhängigkeit NICHT — explizit nachziehen.
    implementation(libs.xtext.xbase.lib)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
