package dev.kuml.markdown

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe

class CodeBlockExtractorTest :
    FunSpec({

        test("extracts a single plain kuml block") {
            val md =
                """
                |# Title
                |
                |```kuml
                |diagram(name = "X") { }
                |```
                |
                |trailing text
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks shouldHaveSize 1
            blocks[0].source shouldBe "diagram(name = \"X\") { }"
            blocks[0].attributes.shouldBeEmpty()
            blocks[0].startLine shouldBe 3
            blocks[0].endLine shouldBe 5
        }

        test("extracts multiple blocks in order") {
            val md =
                """
                |```kuml
                |first
                |```
                |
                |```kuml
                |second
                |```
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks shouldHaveSize 2
            blocks[0].source shouldBe "first"
            blocks[1].source shouldBe "second"
        }

        test("parses brace-delimited attribute map") {
            val md =
                """
                |```kuml {name="hello" width=800}
                |body
                |```
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks shouldHaveSize 1
            blocks[0].attributes shouldContain ("name" to "hello")
            blocks[0].attributes shouldContain ("width" to "800")
            blocks[0].name shouldBe "hello"
            blocks[0].width shouldBe 800
        }

        test("parses bare attributes without braces") {
            val md =
                """
                |```kuml name=foo width=600
                |body
                |```
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks[0].attributes["name"] shouldBe "foo"
            blocks[0].attributes["width"] shouldBe "600"
        }

        test("ignores non-kuml fenced blocks") {
            val md =
                """
                |```kotlin
                |val x = 1
                |```
                |
                |```kuml
                |body
                |```
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks shouldHaveSize 1
            blocks[0].source shouldBe "body"
        }

        test("preserves multi-line script source verbatim") {
            val md =
                """
                |```kuml
                |line1
                |line2
                |line3
                |```
                """.trimMargin()

            val blocks = CodeBlockExtractor.extract(md)
            blocks[0].source shouldBe "line1\nline2\nline3"
        }
    })
