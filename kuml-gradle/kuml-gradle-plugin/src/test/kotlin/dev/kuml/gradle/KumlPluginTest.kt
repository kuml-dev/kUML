package dev.kuml.gradle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files

class KumlPluginTest :
    FunSpec({

        val sampleScript =
            """
            @file:Suppress("unused")

            classDiagram(name = "Demo") {
                classOf("Foo")
            }
            """.trimIndent()

        fun newProject(scripts: Map<String, String>): java.io.File {
            val dir = Files.createTempDirectory("kuml-gradle-").toFile()
            // settings.gradle.kts
            dir
                .resolve("settings.gradle.kts")
                .writeText("""rootProject.name = "kuml-gradle-test"""")
            // build.gradle.kts mit Plugin
            dir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("dev.kuml")
                }

                kuml {
                    sourceDir.set(file("kuml"))
                    outputDir.set(layout.buildDirectory.dir("kuml"))
                }
                """.trimIndent(),
            )
            val srcDir = dir.resolve("kuml").apply { mkdirs() }
            scripts.forEach { (name, body) -> srcDir.resolve(name).writeText(body) }
            return dir
        }

        test("plugin registers the three kuml tasks") {
            val project = newProject(emptyMap())
            try {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(project)
                        .withPluginClasspath()
                        .withArguments("tasks", "--all")
                        .build()
                result.output shouldContain "kumlRender"
                result.output shouldContain "kumlGenerate"
                result.output shouldContain "kumlValidate"
            } finally {
                project.deleteRecursively()
            }
        }

        test("kumlRender renders *.kuml.kts to SVG under outputDir") {
            val project = newProject(mapOf("hello.kuml.kts" to sampleScript))
            try {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(project)
                        .withPluginClasspath()
                        .withArguments("kumlRender", "--stacktrace")
                        .build()
                result.task(":kumlRender")?.outcome shouldBe TaskOutcome.SUCCESS
                val svg = project.resolve("build/kuml/hello.svg")
                svg.exists() shouldBe true
                svg.readText() shouldContain "<svg"
            } finally {
                project.deleteRecursively()
            }
        }

        test("kumlRender is up-to-date on a second run with no changes") {
            val project = newProject(mapOf("hello.kuml.kts" to sampleScript))
            try {
                GradleRunner
                    .create()
                    .withProjectDir(project)
                    .withPluginClasspath()
                    .withArguments("kumlRender")
                    .build()
                val second =
                    GradleRunner
                        .create()
                        .withProjectDir(project)
                        .withPluginClasspath()
                        .withArguments("kumlRender")
                        .build()
                second.task(":kumlRender")?.outcome shouldBe TaskOutcome.UP_TO_DATE
            } finally {
                project.deleteRecursively()
            }
        }

        test("kumlValidate passes for a diagram without constraints") {
            val project = newProject(mapOf("hello.kuml.kts" to sampleScript))
            try {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(project)
                        .withPluginClasspath()
                        .withArguments("kumlValidate")
                        .build()
                result.task(":kumlValidate")?.outcome shouldBe TaskOutcome.SUCCESS
                result.output shouldContain "no violations"
            } finally {
                project.deleteRecursively()
            }
        }

        // ── "Powered by kUML" branding — watermark default-off, opt-in via extension ──

        test("kumlRender does not render the visible watermark by default") {
            val project = newProject(mapOf("hello.kuml.kts" to sampleScript))
            try {
                GradleRunner
                    .create()
                    .withProjectDir(project)
                    .withPluginClasspath()
                    .withArguments("kumlRender", "--stacktrace")
                    .build()
                val svg = project.resolve("build/kuml/hello.svg")
                svg.readText() shouldContain "<!-- Generated by kUML v"
                svg.readText() shouldNotContain "Powered by kUML"
            } finally {
                project.deleteRecursively()
            }
        }

        test("kuml { watermark.set(true) } renders the visible 'Powered by kUML' label") {
            val dir = Files.createTempDirectory("kuml-gradle-watermark-").toFile()
            dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "kuml-gradle-watermark-test"""")
            dir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("dev.kuml")
                }

                kuml {
                    sourceDir.set(file("kuml"))
                    outputDir.set(layout.buildDirectory.dir("kuml"))
                    watermark.set(true)
                }
                """.trimIndent(),
            )
            val srcDir = dir.resolve("kuml").apply { mkdirs() }
            srcDir.resolve("hello.kuml.kts").writeText(sampleScript)
            try {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(dir)
                        .withPluginClasspath()
                        .withArguments("kumlRender", "--stacktrace")
                        .build()
                result.task(":kumlRender")?.outcome shouldBe TaskOutcome.SUCCESS
                val svg = dir.resolve("build/kuml/hello.svg")
                svg.readText() shouldContain "Powered by kUML"
            } finally {
                dir.deleteRecursively()
            }
        }

        test("kumlGenerate writes Kotlin files via the kotlin codegen") {
            val project = newProject(mapOf("hello.kuml.kts" to sampleScript))
            try {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(project)
                        .withPluginClasspath()
                        .withArguments("kumlGenerate", "--stacktrace")
                        .build()
                result.task(":kumlGenerate")?.outcome shouldBe TaskOutcome.SUCCESS
                // Der Kotlin-Codegen erzeugt mindestens eine .kt-Datei für `class Foo`.
                val generated = project.resolve("build/kuml/generated")
                generated.exists() shouldBe true
                val anyKt =
                    generated.walkTopDown().any { it.isFile && it.name.endsWith(".kt") }
                anyKt shouldBe true
            } finally {
                project.deleteRecursively()
            }
        }
    })
