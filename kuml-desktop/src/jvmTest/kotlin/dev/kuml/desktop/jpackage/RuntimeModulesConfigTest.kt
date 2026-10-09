package dev.kuml.desktop.jpackage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Regression guard for the packaged-app blank-preview bug (0.54.1): Compose's auto-detected
 * jlink runtime omitted `jdk.unsupported`, so the Kotlin scripting stack failed with
 * `ExceptionInInitializerError` (`sun.misc.Unsafe`) on every render in the installed app,
 * while `./gradlew run` (full JDK) and the CLI stayed fine. A unit test cannot restrict the
 * JVM's module graph in-process, so this pins the explicit `modules(...)` list in
 * `build.gradle.kts` — the file Gradle runs with the test working directory = module dir.
 */
class RuntimeModulesConfigTest :
    FunSpec({
        val buildScript = File("build.gradle.kts").readText()

        listOf(
            "jdk.unsupported",
            "jdk.httpserver",
            "java.scripting",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.sql",
            "jdk.zipfs",
        ).forEach { module ->
            test("packaged runtime pins jlink module $module") {
                buildScript shouldContain "\"$module\""
            }
        }
    })
