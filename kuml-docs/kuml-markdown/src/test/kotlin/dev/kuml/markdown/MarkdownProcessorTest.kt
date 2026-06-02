package dev.kuml.markdown

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class MarkdownProcessorTest :
    FunSpec({

        // Minimal valid kuml DSL — kept tiny so script compile time stays low.
        val simpleScript =
            """
            |classDiagram(name = "Markdown Test") {
            |    val a = classOf(name = "A")
            |    val b = classOf(name = "B")
            |    association(source = a, target = b)
            |}
            """.trimMargin()

        fun mdWith(body: String) =
            """
            |# Heading
            |
            |Some intro paragraph.
            |
            |```kuml
            |$body
            |```
            |
            |Closing paragraph.
            """.trimMargin()

        test("input without kuml blocks is passed through unchanged") {
            val md = "# Plain\n\nNo diagrams here.\n"
            val result = MarkdownProcessor().process(md, MarkdownOutputMode.InlineSvg)
            result.output shouldBe md
            result.assets shouldHaveSize 0
        }

        test("InlineSvg mode replaces block with raw <svg>") {
            val md = mdWith(simpleScript)
            val result = MarkdownProcessor().process(md, MarkdownOutputMode.InlineSvg)
            result.output shouldContain "<svg"
            result.output shouldContain "</svg>"
            result.output shouldNotContain "```kuml"
            result.assets shouldHaveSize 0
        }

        test("LinkedSvg mode writes file and inserts ![](…) link") {
            val tmpDir = Files.createTempDirectory("kuml-md-test").toFile()
            val md = mdWith(simpleScript)
            val result =
                MarkdownProcessor().process(
                    input = md,
                    mode = MarkdownOutputMode.LinkedSvg(tmpDir),
                    baseName = "demo",
                )
            result.output shouldContain "![Markdown Test]("
            result.output shouldContain ".svg)"
            result.output shouldNotContain "```kuml"
            result.assets shouldHaveSize 1
            result.assets[0].extension shouldBe "svg"
            result.assets[0].length() > 0L
        }

        test("attribute name=… is used as file stem") {
            val tmpDir = Files.createTempDirectory("kuml-md-test").toFile()
            val md =
                """
                |```kuml {name="custom"}
                |$simpleScript
                |```
                """.trimMargin()
            val result =
                MarkdownProcessor().process(
                    input = md,
                    mode = MarkdownOutputMode.LinkedSvg(tmpDir),
                )
            result.assets[0].name shouldBe "custom.svg"
            result.output shouldContain "custom.svg"
        }
    })
