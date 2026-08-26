package dev.kuml.asciidoc

import dev.kuml.core.script.ScriptEvaluationException
import io.kotest.assertions.throwables.shouldThrow
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
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
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
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedSvg(assetsDir),
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedPng(assetsDir = assetsDir, widthPx = 1024),
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
                val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedSvg(assetsDir),
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

        test("Blueprint diagrams render inline SVG (V3.2.19 — extractAny multi-model dispatch)") {
            val processor = AsciidocProcessor()
            val blueprintScript =
                """
                blueprint("Demo Journey") {
                    val web = channel("Web", ChannelKind.WEB)
                    val tp = touchpoint("Landing page", channel = web)
                    phase("Discovery") {
                        customer("Visits site", Sentiment.POSITIVE, touchpoints = listOf(tp))
                    }
                    journeyDiagram("Demo Journey Map")
                }
                """.trimIndent()
            val input =
                """
                = Demo

                [source,kuml]
                ----
                $blueprintScript
                ----
                """.trimIndent()
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
            result.output shouldContain "++++"
            result.output shouldContain "<svg"
        }

        test("ERM diagrams render inline SVG in all four notations (V3.4.x)") {
            val processor = AsciidocProcessor()

            fun ermScript(notation: String) =
                """
                ermModel("Demo") {
                    val a = entity("A") { id() }
                    val b = entity("B", weak = true) {
                        foreignKey(name = "a_id", references = a, nullable = false)
                    }
                    relationship(from = a, to = b, kind = RelationshipKind.IDENTIFYING)
                    diagram(name = "Demo", notation = ErmNotation.$notation)
                }
                """.trimIndent()

            listOf("MARTIN", "BACHMAN", "CHEN", "IDEF1X").forEach { notation ->
                val input =
                    """
                    = Demo

                    [source,kuml]
                    ----
                    ${ermScript(notation)}
                    ----
                    """.trimIndent()
                val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
                result.output shouldContain "++++"
                result.output shouldContain "<svg"
            }
        }

        test("C4 diagrams throw a clear ScriptEvaluationException (not yet supported)") {
            val processor = AsciidocProcessor()
            val c4Script =
                """
                c4Model(name = "Demo") {
                    systemContextDiagram(name = "Context") {
                        person(name = "User")
                    }
                }
                """.trimIndent()
            val input =
                """
                [source,kuml]
                ----
                $c4Script
                ----
                """.trimIndent()
            val exception =
                shouldThrow<ScriptEvaluationException> {
                    processor.process(input = input, mode = AsciidocOutputMode.InlineSvg)
                }
            exception.message shouldContain "C4"
        }

        test("withSource=true emits the DSL source as a [source,kotlin] listing before the inline SVG") {
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
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = true)
            result.output shouldContain "[source,kotlin]"
            result.output shouldContain sampleScript
            result.output shouldContain "<svg"
            val sourceIndex = result.output.indexOf("[source,kotlin]")
            val svgIndex = result.output.indexOf("<svg")
            (sourceIndex < svgIndex) shouldBe true
        }

        test("withSource=true works with LinkedSvg") {
            val processor = AsciidocProcessor()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-withsource-svg-").toFile()
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedSvg(assetsDir),
                        baseName = "doc",
                        withSource = true,
                    )
                result.output shouldContain "[source,kotlin]"
                result.output shouldContain sampleScript
                result.output shouldContain "image::hello.svg["
            } finally {
                assetsDir.deleteRecursively()
            }
        }

        test("withSource=true works with LinkedPng") {
            val processor = AsciidocProcessor()
            val assetsDir = Files.createTempDirectory("kuml-asciidoc-withsource-png-").toFile()
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedPng(assetsDir = assetsDir, widthPx = 1024),
                        withSource = true,
                    )
                result.output shouldContain "[source,kotlin]"
                result.output shouldContain sampleScript
                val bytes = result.assets.first().readBytes()
                bytes[0] shouldBe 0x89.toByte()
                bytes[1] shouldBe 0x50.toByte()
            } finally {
                assetsDir.deleteRecursively()
            }
        }

        test("withSource=true reproduces the file content for a kuml:: block macro") {
            val baseDir = Files.createTempDirectory("kuml-asciidoc-withsource-macro-").toFile()
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
                val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = true)
                result.output shouldContain "[source,kotlin]"
                result.output shouldContain sampleScript
                result.output shouldContain "<svg"
            } finally {
                baseDir.deleteRecursively()
            }
        }

        test("showsource=false on a block overrides a processor-level withSource=true") {
            val processor = AsciidocProcessor()
            val input =
                """
                [source,kuml,showsource=false]
                ----
                $sampleScript
                ----
                """.trimIndent()
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = true)
            result.output shouldNotContain "[source,kotlin]"
            result.output shouldContain "<svg"
        }

        test("showsource=true on a block overrides withSource=false") {
            val processor = AsciidocProcessor()
            val input =
                """
                [source,kuml,showsource=true]
                ----
                $sampleScript
                ----
                """.trimIndent()
            val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = false)
            result.output shouldContain "[source,kotlin]"
            result.output shouldContain sampleScript
            result.output shouldContain "<svg"
        }

        test("withSource=true widens the listing delimiter when a kuml:: macro file contains a `----` line") {
            // Regression test: the DSL source for a BLOCK_MACRO block is read verbatim from an
            // external file and is not bounded by the surrounding .adoc document's own fence
            // (unlike LISTING blocks, whose extraction already excludes this case). A source
            // file containing a line that is exactly "----" must not prematurely close the
            // generated [source,kotlin] listing block.
            val baseDir = Files.createTempDirectory("kuml-asciidoc-withsource-macro-dashes-").toFile()
            try {
                val scriptWithDashLine =
                    """
                    @file:Suppress("unused")

                    val banner = ""${'"'}
                    ----
                    ""${'"'}.trimIndent()

                    classDiagram(name = "Demo") {
                        classOf("Foo") {
                            attribute(name = "banner", type = "String", defaultValue = banner)
                        }
                    }
                    """.trimIndent()
                val scriptFile = baseDir.resolve("hello.kuml.kts")
                scriptFile.writeText(scriptWithDashLine)

                val processor = AsciidocProcessor(baseDir = baseDir)
                val input =
                    """
                    = Guide

                    kuml::hello.kuml.kts[]

                    After.
                    """.trimIndent()
                val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = true)

                // The full source (including its embedded "----" line) must appear verbatim,
                // reproduced inside a listing block delimited by a longer dash run ("-----").
                result.output shouldContain scriptWithDashLine
                result.output shouldContain "-----"
                // Content after the block must not have been swallowed into an unterminated
                // listing block.
                result.output shouldContain "After."

                // The listing must actually be well-formed: exactly two occurrences of the
                // 5-dash delimiter (open + close), and the embedded "----" line must sit
                // strictly between them rather than acting as its own delimiter.
                val outputLines = result.output.split('\n')
                val delimiterLines = outputLines.withIndex().filter { it.value == "-----" }
                delimiterLines shouldHaveSize 2
                val (openIdx, closeIdx) = delimiterLines.map { it.index }
                val dashLineIdx = outputLines.indexOfFirst { it == "----" }
                (dashLineIdx in (openIdx + 1) until closeIdx) shouldBe true
            } finally {
                baseDir.deleteRecursively()
            }
        }

        test("withSource=true widens the listing delimiter when a kuml:: macro file has a CRLF-terminated `----` line") {
            // Regression test for the CRLF gap in safeListingDelimiter: a source file whose
            // dash-only line is terminated by "\r\n" (e.g. checked out on/authored on Windows,
            // see CLAUDE.md's documented multi-OS kUML development) must still be recognized as
            // dash-only. Otherwise the delimiter stays at the unwidened 4-dash "----", a real
            // Asciidoctor renderer (which normalizes CRLF when matching delimiter lines) treats
            // the embedded "----\r" line as the closing fence, and everything after it is parsed
            // as live AsciiDoc structure instead of literal listing content.
            val baseDir = Files.createTempDirectory("kuml-asciidoc-withsource-macro-crlf-").toFile()
            try {
                val scriptWithCrlfDashLine =
                    "@file:Suppress(\"unused\")\r\n" +
                        "\r\n" +
                        "val banner = \"\"\"\r\n" +
                        "----\r\n" +
                        "\"\"\".trimIndent()\r\n" +
                        "\r\n" +
                        "classDiagram(name = \"Demo\") {\r\n" +
                        "    classOf(\"Foo\") {\r\n" +
                        "        attribute(name = \"banner\", type = \"String\", defaultValue = banner)\r\n" +
                        "    }\r\n" +
                        "}"
                val scriptFile = baseDir.resolve("hello.kuml.kts")
                scriptFile.writeBytes(scriptWithCrlfDashLine.toByteArray(Charsets.UTF_8))

                val processor = AsciidocProcessor(baseDir = baseDir)
                val input =
                    """
                    = Guide

                    kuml::hello.kuml.kts[]

                    After.
                    """.trimIndent()
                val result = processor.process(input = input, mode = AsciidocOutputMode.InlineSvg, withSource = true)

                // The delimiter must have been widened past plain "----", and content after the
                // block must not have been swallowed into a prematurely closed listing block.
                result.output shouldContain "-----"
                result.output shouldContain "After."

                // Exactly two occurrences of the widened delimiter (open + close), with the
                // embedded dash line sitting strictly between them.
                val outputLines = result.output.split('\n')
                val delimiterLines = outputLines.withIndex().filter { it.value == "-----" }
                delimiterLines shouldHaveSize 2
                val (openIdx, closeIdx) = delimiterLines.map { it.index }
                val dashLineIdx = outputLines.indexOfFirst { it.trimEnd('\r') == "----" }
                (dashLineIdx in (openIdx + 1) until closeIdx) shouldBe true
            } finally {
                baseDir.deleteRecursively()
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
                        input = input,
                        mode = AsciidocOutputMode.LinkedSvg(assetsDir),
                        baseName = "doc",
                    )
                result.assets shouldHaveSize 1
                result.assets.first().name shouldBe "doc-1.svg"
            } finally {
                assetsDir.deleteRecursively()
            }
        }
    })
