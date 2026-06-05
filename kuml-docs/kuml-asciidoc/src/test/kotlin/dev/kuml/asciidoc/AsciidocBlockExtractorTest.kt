package dev.kuml.asciidoc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class AsciidocBlockExtractorTest :
    FunSpec({

        test("empty document returns no blocks") {
            AsciidocBlockExtractor.extract("").shouldBeEmpty()
            AsciidocBlockExtractor.extract("= Title\n\nJust text.\n").shouldBeEmpty()
        }

        test("extracts a simple [source,kuml] listing block") {
            val doc =
                """
                = Demo

                [source,kuml]
                ----
                classDiagram(name = "X") { classOf("A") }
                ----

                More text.
                """.trimIndent()
            val blocks = AsciidocBlockExtractor.extract(doc)
            blocks.size shouldBe 1
            blocks[0].kind shouldBe AsciidocBlockKind.LISTING
            blocks[0].source shouldBe """classDiagram(name = "X") { classOf("A") }"""
            blocks[0].targetPath shouldBe null
        }

        test("listing-block attributes are parsed") {
            val doc =
                """
                [source,kuml,name="hello",width=800]
                ----
                classDiagram(name = "H") {}
                ----
                """.trimIndent()
            val blocks = AsciidocBlockExtractor.extract(doc)
            blocks.size shouldBe 1
            blocks[0].attributes["name"] shouldBe "hello"
            blocks[0].attributes["width"] shouldBe "800"
            blocks[0].name shouldBe "hello"
            blocks[0].width shouldBe 800
        }

        test("extracts a kuml:: block macro") {
            val doc =
                """
                = Guide

                kuml::diagrams/login.kuml.kts[]

                Next paragraph.
                """.trimIndent()
            val blocks = AsciidocBlockExtractor.extract(doc)
            blocks.size shouldBe 1
            blocks[0].kind shouldBe AsciidocBlockKind.BLOCK_MACRO
            blocks[0].targetPath shouldBe "diagrams/login.kuml.kts"
            blocks[0].source shouldBe ""
        }

        test("block macro with attributes is parsed") {
            val doc = "kuml::diagrams/login.kuml.kts[name=login,width=800]\n"
            val blocks = AsciidocBlockExtractor.extract(doc)
            blocks.size shouldBe 1
            blocks[0].attributes["name"] shouldBe "login"
            blocks[0].attributes["width"] shouldBe "800"
        }

        test("multiple blocks in one document, order preserved") {
            val doc =
                """
                First:

                [source,kuml]
                ----
                classDiagram(name = "A") {}
                ----

                Then:

                kuml::path/to/b.kuml.kts[]

                And last:

                [source,kuml,name="c"]
                ----
                classDiagram(name = "C") {}
                ----
                """.trimIndent()
            val blocks = AsciidocBlockExtractor.extract(doc)
            blocks.size shouldBe 3
            blocks[0].kind shouldBe AsciidocBlockKind.LISTING
            blocks[1].kind shouldBe AsciidocBlockKind.BLOCK_MACRO
            blocks[1].targetPath shouldBe "path/to/b.kuml.kts"
            blocks[2].kind shouldBe AsciidocBlockKind.LISTING
            blocks[2].attributes["name"] shouldBe "c"
        }

        test("[source,asciidoc] listing block is ignored (not kuml)") {
            val doc =
                """
                [source,asciidoc]
                ----
                This is not kuml.
                ----
                """.trimIndent()
            AsciidocBlockExtractor.extract(doc).shouldBeEmpty()
        }

        test("listing-block without fence is ignored") {
            val doc = "[source,kuml]\n\nNo fence follows.\n"
            AsciidocBlockExtractor.extract(doc).shouldBeEmpty()
        }
    })
