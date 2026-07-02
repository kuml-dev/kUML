package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class AsciidocCommandTest :
    FunSpec({

        val simpleScript =
            """
            |classDiagram(name = "CLI AsciiDoc Test") {
            |    val a = classOf(name = "A")
            |    val b = classOf(name = "B")
            |    association(source = a, target = b)
            |}
            """.trimMargin()

        fun adocFile(): java.io.File {
            val f = Files.createTempFile("kuml-cli-adoc", ".adoc").toFile()
            f.writeText(
                """
                |= Demo
                |
                |[source,kuml]
                |----
                |$simpleScript
                |----
                |
                """.trimMargin(),
            )
            return f
        }

        test("inline mode replaces block with inline <svg> passthrough") {
            val input = adocFile()
            val out = Files.createTempFile("kuml-cli-out", ".adoc").toFile()
            val result = KumlCli().test("asciidoc --input ${input.absolutePath} --output ${out.absolutePath} --mode inline")
            result.statusCode shouldBe 0
            val produced = out.readText()
            produced shouldContain "<svg"
            produced shouldContain "++++"
            produced shouldNotContain "[source,kuml]"
            input.delete()
            out.delete()
        }

        test("linked-svg mode writes asset and uses image macro") {
            val input = adocFile()
            val outDir = Files.createTempDirectory("kuml-cli-out").toFile()
            val out = java.io.File(outDir, "out.adoc")
            val assets = java.io.File(outDir, "assets")

            val result =
                KumlCli().test(
                    "asciidoc --input ${input.absolutePath} --output ${out.absolutePath} " +
                        "--mode linked-svg --assets-dir ${assets.absolutePath}",
                )
            result.statusCode shouldBe 0
            out.readText() shouldContain "image::"
            out.readText() shouldContain ".svg["
            assets.listFiles()?.size shouldBe 1

            input.delete()
            outDir.deleteRecursively()
        }

        test("linked-png mode writes a .png asset") {
            val input = adocFile()
            val outDir = Files.createTempDirectory("kuml-cli-out").toFile()
            val out = java.io.File(outDir, "out.adoc")
            val assets = java.io.File(outDir, "assets")

            val result =
                KumlCli().test(
                    "asciidoc --input ${input.absolutePath} --output ${out.absolutePath} " +
                        "--mode linked-png --assets-dir ${assets.absolutePath} --width 600",
                )
            result.statusCode shouldBe 0
            out.readText() shouldContain ".png["
            val files = assets.listFiles().orEmpty()
            files.size shouldBe 1
            files[0].extension shouldBe "png"

            input.delete()
            outDir.deleteRecursively()
        }

        test("directory mode renders .adoc files and copies other files unchanged") {
            val inDir = Files.createTempDirectory("kuml-cli-indir").toFile()
            val pageFile = java.io.File(inDir, "page.adoc")
            pageFile.writeText(
                """
                |= Demo
                |
                |[source,kuml]
                |----
                |$simpleScript
                |----
                |
                """.trimMargin(),
            )
            val navFile = java.io.File(inDir, "nav.adoc")
            navFile.writeText("* xref:page.adoc[Page]\n")

            val outDir = Files.createTempDirectory("kuml-cli-outdir").toFile()

            val result =
                KumlCli().test(
                    "asciidoc --input-dir ${inDir.absolutePath} --output-dir ${outDir.absolutePath} --mode linked-svg",
                )
            result.statusCode shouldBe 0

            val renderedPage = java.io.File(outDir, "page.adoc")
            renderedPage.exists() shouldBe true
            renderedPage.readText() shouldContain "image::"

            val copiedNav = java.io.File(outDir, "nav.adoc")
            copiedNav.exists() shouldBe true
            copiedNav.readText() shouldBe navFile.readText()

            val imagesDir = java.io.File(outDir, "images")
            imagesDir.listFiles()?.size shouldBe 1

            inDir.deleteRecursively()
            outDir.deleteRecursively()
        }

        test("directory mode writes images to the Antora module root, not nested under pages/") {
            // Antora layout: modules/<module>/pages/[sub/]page.adoc — image:: resolves
            // against modules/<module>/images/, a *sibling* of pages/, regardless of
            // nesting depth under pages/.
            val inDir = Files.createTempDirectory("kuml-cli-indir").toFile()
            val pagesDir = java.io.File(inDir, "modules/ROOT/pages/showcases")
            pagesDir.mkdirs()
            val pageFile = java.io.File(pagesDir, "deep-page.adoc")
            pageFile.writeText(
                """
                |= Demo
                |
                |[source,kuml]
                |----
                |$simpleScript
                |----
                |
                """.trimMargin(),
            )
            val outDir = Files.createTempDirectory("kuml-cli-outdir").toFile()

            val result =
                KumlCli().test(
                    "asciidoc --input-dir ${inDir.absolutePath} --output-dir ${outDir.absolutePath} --mode linked-svg",
                )
            result.statusCode shouldBe 0

            val moduleImagesDir = java.io.File(outDir, "modules/ROOT/images")
            moduleImagesDir.listFiles()?.size shouldBe 1

            val nestedImagesDir = java.io.File(outDir, "modules/ROOT/pages/showcases/images")
            nestedImagesDir.exists() shouldBe false

            inDir.deleteRecursively()
            outDir.deleteRecursively()
        }

        test("directory mode honours --mode inline (does not silently force linked-svg)") {
            val inDir = Files.createTempDirectory("kuml-cli-indir").toFile()
            val pageFile = java.io.File(inDir, "page.adoc")
            pageFile.writeText(
                """
                |= Demo
                |
                |[source,kuml]
                |----
                |$simpleScript
                |----
                |
                """.trimMargin(),
            )
            val outDir = Files.createTempDirectory("kuml-cli-outdir").toFile()

            val result =
                KumlCli().test(
                    "asciidoc --input-dir ${inDir.absolutePath} --output-dir ${outDir.absolutePath} --mode inline",
                )
            result.statusCode shouldBe 0

            val renderedPage = java.io.File(outDir, "page.adoc")
            renderedPage.readText() shouldContain "<svg"
            renderedPage.readText() shouldNotContain "image::"

            inDir.deleteRecursively()
            outDir.deleteRecursively()
        }

        test("rejects mixing single-file and directory options") {
            val input = adocFile()
            val outDir = Files.createTempDirectory("kuml-cli-outdir").toFile()
            val result =
                KumlCli().test(
                    "asciidoc --input ${input.absolutePath} --input-dir ${outDir.absolutePath}",
                )
            result.statusCode shouldBe ExitCodes.IO_ERROR
            input.delete()
            outDir.deleteRecursively()
        }
    })
