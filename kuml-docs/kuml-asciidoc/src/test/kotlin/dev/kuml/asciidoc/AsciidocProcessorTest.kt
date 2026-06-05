package dev.kuml.asciidoc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class AsciidocProcessorTest :
    FunSpec({

        val sampleScript =
            """
            @file:Suppress("unused")

            classDiagram(name = "Demo") {
                classOf("Foo")
            }
            """.trimIndent()

        test("document without kuml blocks is returned unchanged") {
            val processor = AsciidocProcessor()
            val input = "= Title\n\nNo diagrams here.\n"
            val result = processor.process(input, AsciidocOutputMode.InlineSvg)
            result.output shouldBe input
            result.assets.shouldBeEmpty()
        }

        test("listing block is replaced by an Asciidoctor passthrough containing inline SVG") {
            val processor = AsciidocProcessor()
            val input =
                """
                = Demo

                [source,kuml]
                ----
                $sampleScript
                ----

                After.
                """.trimIndent()
            val result = processor.process(input, AsciidocOutputMode.InlineSvg)
            // The processor must emit passthrough fences and inline SVG
            result.output shouldContain "++++"
            result.output shouldContain "<svg"
            // The original source block must be gone
            result.output shouldNotContain "[source,kuml]"
            result.output shouldNotContain "----"
            // Surrounding text is preserved
            result.output shouldContain "After."
        }

        test("LinkedSvg writes an .svg file and replaces the block with an image:: macro") {
            val processor = AsciidocProcessor()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-").toFile()
            try {
                val input =
                    """
                    [source,kuml,name=hello]
                    ----
                    $sampleScript
                    ----
                    """.trimIndent()
                val result =
                    processor.process(
                        input,
                        AsciidocOutputMode.LinkedSvg(assetsDir),
                        baseName = "doc",
                    )
                result.assets shouldHaveSize 1
                result.assets.first().name shouldBe "hello.svg"
                result.assets.first().exists() shouldBe true
                result.assets.first().readText() shouldContain "<svg"
                result.output shouldContain "image::hello.svg["
            } finally {
                assetsDir.deleteRecursively()
            }
        }

        test("LinkedPng writes a .png file (PNG magic bytes)") {
            val processor = AsciidocProcessor()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-png-").toFile()
            try {
                val input =
                    """
                    [source,kuml,name=hello,width=400]
                    ----
                    $sampleScript
                    ----
                    """.trimIndent()
                val result =
                    processor.process(
                        input,
                        AsciidocOutputMode.LinkedPng(assetsDir, widthPx = 1024),
                    )
                result.assets shouldHaveSize 1
                val file = result.assets.first()
                file.name shouldBe "hello.png"
                val bytes = file.readBytes()
                // PNG magic: 89 50 4E 47
                bytes[0] shouldBe 0x89.toByte()
                bytes[1] shouldBe 0x50.toByte()
                result.output shouldContain "image::hello.png["
            } finally {
                assetsDir.deleteRecursively()
            }
        }

        test("kuml:: block macro loads an external file relative to baseDir") {
            val baseDir = Files.createTempDirectory("kuml-asciidoc-macro-").toFile()
            try {
                val scriptFile = baseDir.resolve("hello.kuml.kts")
                scriptFile.writeText(sampleScript)

                val processor = AsciidocProcessor(baseDir = baseDir)
                val input =
                    """
                    = Guide

                    kuml::hello.kuml.kts[]

                    Done.
                    """.trimIndent()
                val result = processor.process(input, AsciidocOutputMode.InlineSvg)
                result.output shouldContain "++++"
                result.output shouldContain "<svg"
                result.output shouldNotContain "kuml::hello.kuml.kts"
            } finally {
                baseDir.deleteRecursively()
            }
        }

        test("multiple blocks are all replaced and ordered") {
            val processor = AsciidocProcessor()
            val input =
                """
                = Doc

                [source,kuml,name=a]
                ----
                $sampleScript
                ----

                Middle.

                [source,kuml,name=b]
                ----
                $sampleScript
                ----
                """.trimIndent()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-multi-").toFile()
            try {
                val result =
                    processor.process(
                        input,
                        AsciidocOutputMode.LinkedSvg(assetsDir),
                    )
                result.assets shouldHaveSize 2
                result.assets.map { it.name } shouldBe listOf("a.svg", "b.svg")
                result.output shouldContain "image::a.svg["
                result.output shouldContain "image::b.svg["
                result.output shouldContain "Middle."
            } finally {
                assetsDir.deleteRecursively()
            }
        }

        test("blocks without a name fall back to baseName-index for asset filenames") {
            val processor = AsciidocProcessor()
            val input =
                """
                [source,kuml]
                ----
                $sampleScript
                ----
                """.trimIndent()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-noname-").toFile()
            try {
                val result =
                    processor.process(
                        input,
                        AsciidocOutputMode.LinkedSvg(assetsDir),
                        baseName = "doc",
                    )
                result.assets shouldHaveSize 1
                result.assets.first().name shouldBe "doc-1.svg"
            } finally {
                assetsDir.deleteRecursively()
            }
        }
    })
