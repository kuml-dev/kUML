package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class MarkdownCommandTest :
    FunSpec({

        val simpleScript =
            """
            |classDiagram(name = "CLI MD Test") {
            |    val a = classOf(name = "A")
            |    val b = classOf(name = "B")
            |    association(source = a, target = b)
            |}
            """.trimMargin()

        fun mdFile(): java.io.File {
            val f = Files.createTempFile("kuml-cli-md", ".md").toFile()
            f.writeText(
                """
                |# Demo
                |
                |```kuml
                |$simpleScript
                |```
                |
                """.trimMargin(),
            )
            return f
        }

        test("inline mode replaces block with inline <svg>") {
            val input = mdFile()
            val out = Files.createTempFile("kuml-cli-out", ".md").toFile()
            val result = KumlCli().test("markdown --input ${input.absolutePath} --output ${out.absolutePath} --mode inline")
            result.statusCode shouldBe 0
            val produced = out.readText()
            produced shouldContain "<svg"
            produced shouldNotContain "```kuml"
            input.delete()
            out.delete()
        }

        test("linked-svg mode writes asset and uses link") {
            val input = mdFile()
            val outDir = Files.createTempDirectory("kuml-cli-out").toFile()
            val out = java.io.File(outDir, "out.md")
            val assets = java.io.File(outDir, "assets")

            val result =
                KumlCli().test(
                    "markdown --input ${input.absolutePath} --output ${out.absolutePath} " +
                        "--mode linked-svg --assets-dir ${assets.absolutePath}",
                )
            result.statusCode shouldBe 0
            out.readText() shouldContain "](" // Markdown image link
            out.readText() shouldContain ".svg)"
            assets.listFiles()?.size shouldBe 1

            input.delete()
            outDir.deleteRecursively()
        }

        test("linked-png mode writes a .png asset") {
            val input = mdFile()
            val outDir = Files.createTempDirectory("kuml-cli-out").toFile()
            val out = java.io.File(outDir, "out.md")
            val assets = java.io.File(outDir, "assets")

            val result =
                KumlCli().test(
                    "markdown --input ${input.absolutePath} --output ${out.absolutePath} " +
                        "--mode linked-png --assets-dir ${assets.absolutePath} --width 600",
                )
            result.statusCode shouldBe 0
            out.readText() shouldContain ".png)"
            val files = assets.listFiles().orEmpty()
            files.size shouldBe 1
            files[0].extension shouldBe "png"

            input.delete()
            outDir.deleteRecursively()
        }
    })
