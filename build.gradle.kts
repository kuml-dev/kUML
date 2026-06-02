import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = "dev.kuml"
    version = "0.1.1"
}

// Apply ktlint to all subprojects that use the Kotlin JVM plugin.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Maven Central publishing — applied to library modules only.
//
// Executable application modules (kuml-cli, kuml-mcp, kuml-llm-bench) ship as
// distZip / Homebrew tarballs and are NOT published as Maven artefacts.
//
// Required environment / Gradle properties for a release:
//   ORG_GRADLE_PROJECT_mavenCentralUsername
//   ORG_GRADLE_PROJECT_mavenCentralPassword
//   ORG_GRADLE_PROJECT_signingInMemoryKey
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
// See docs/release.md for the full setup walkthrough.
// ─────────────────────────────────────────────────────────────────────────────

val nonPublishedModules = setOf(
    "kUML",
    "kuml-cli",
    "kuml-mcp",
    "kuml-llm-bench",
    "kuml-tests",
    "kuml-examples",
    "kuml-packaging",
)

subprojects {
    if (name in nonPublishedModules) return@subprojects

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "com.vanniktech.maven.publish")

        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
            signAllPublications()

            configure(
                KotlinJvm(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = true,
                ),
            )

            coordinates(
                groupId = "dev.kuml",
                artifactId = project.name,
                version = project.version.toString(),
            )

            pom {
                name.set(project.name)
                description.set("kUML — a Kotlin-DSL approach to UML 2.x and C4 modelling. Module: ${project.name}")
                url.set("https://github.com/kuml-dev/kuml")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("ibetchvaia")
                        name.set("Irakli Betchvaia")
                        email.set("ibetchvaia@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/kuml-dev/kuml")
                    connection.set("scm:git:https://github.com/kuml-dev/kuml.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kuml-dev/kuml.git")
                }
            }
        }
    }
}
