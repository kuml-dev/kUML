package dev.kuml.jetbrains.asciidoc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class KumlAsciidocBlockParserTest :
    FunSpec({

        test("empty document returns no blocks") {
            KumlAsciidocBlockParser.parse("").shouldBeEmpty()
            KumlAsciidocBlockParser.parse("= Title\n\nJust text.\n").shouldBeEmpty()
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
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].kind shouldBe KumlAsciidocBlock.Kind.LISTING
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
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].attributes["name"] shouldBe "hello"
            blocks[0].attributes["width"] shouldBe "800"
            KumlAsciidocBlockParser.resolveName(blocks[0].attributes) shouldBe "hello"
            KumlAsciidocBlockParser.resolveWidth(blocks[0].attributes) shouldBe "800"
        }

        test("extracts a kuml:: block macro") {
            val doc =
                """
                = Guide

                kuml::diagrams/login.kuml.kts[]

                Next paragraph.
                """.trimIndent()
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].kind shouldBe KumlAsciidocBlock.Kind.BLOCK_MACRO
            blocks[0].targetPath shouldBe "diagrams/login.kuml.kts"
            blocks[0].source shouldBe ""
        }

        test("block macro with attributes is parsed") {
            val doc = "kuml::diagrams/login.kuml.kts[name=login,width=800]\n"
            val blocks = KumlAsciidocBlockParser.parse(doc)
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
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 3
            blocks[0].kind shouldBe KumlAsciidocBlock.Kind.LISTING
            blocks[1].kind shouldBe KumlAsciidocBlock.Kind.BLOCK_MACRO
            blocks[1].targetPath shouldBe "path/to/b.kuml.kts"
            blocks[2].kind shouldBe KumlAsciidocBlock.Kind.LISTING
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
            KumlAsciidocBlockParser.parse(doc).shouldBeEmpty()
        }

        test("listing-block without fence is ignored") {
            val doc = "[source,kuml]\n\nNo fence follows.\n"
            KumlAsciidocBlockParser.parse(doc).shouldBeEmpty()
        }

        test("adjacent blocks without blank line between them") {
            val doc =
                """
                [source,kuml,name="a"]
                ----
                class A {}
                ----
                kuml::b.kuml.kts[name="b"]
                """.trimIndent()
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 2
            blocks[0].attributes["name"] shouldBe "a"
            blocks[1].attributes["name"] shouldBe "b"
        }

        test("listing header immediately followed by another listing header (fence missing)") {
            val doc =
                """
                [source,kuml,name="first"]
                [source,kuml,name="second"]
                ----
                class B {}
                ----
                """.trimIndent()
            // First header is ignored because it's not followed by a fence
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].attributes["name"] shouldBe "second"
        }

        test("Windows-style line endings CRLF do not break parsing") {
            val doc = "[source,kuml]\r\n----\r\nclass C {}\r\n----\r\n"
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].source shouldBe "class C {}"
        }

        test("Windows-style line endings CRLF: multi-line source has clean interior lines") {
            val doc = "[source,kuml]\r\n----\r\nline1\r\nline2\r\n----\r\n"
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].source shouldBe "line1\nline2"
        }

        test("classic Mac line endings (lone CR) do not break parsing") {
            val doc = "[source,kuml]\r----\rline1\rline2\r----\r"
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].source shouldBe "line1\nline2"
        }

        test("CRLF block macro: targetPath is clean") {
            val doc = "kuml::diagrams/login.kuml.kts[]\r\n"
            val blocks = KumlAsciidocBlockParser.parse(doc)
            blocks.size shouldBe 1
            blocks[0].kind shouldBe KumlAsciidocBlock.Kind.BLOCK_MACRO
            blocks[0].targetPath shouldBe "diagrams/login.kuml.kts"
        }
    })
