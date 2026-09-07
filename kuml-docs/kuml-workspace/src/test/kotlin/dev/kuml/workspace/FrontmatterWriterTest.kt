package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FrontmatterWriterTest :
    FunSpec({

        test("replaces the value of an existing key, leaving all other lines byte-identical") {
            val md =
                """
                |---
                |type: Concept
                |title: Order
                |---
                |# Body
                """.trimMargin()

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe
                """
                |---
                |type: Article
                |title: Order
                |---
                |# Body
                """.trimMargin()
        }

        test("a YAML comment in the frontmatter block survives a setField") {
            val md =
                """
                |---
                |# hand-written
                |type: Concept
                |---
                |Body
                """.trimMargin()

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe
                """
                |---
                |# hand-written
                |type: Article
                |---
                |Body
                """.trimMargin()
        }

        test("unknown keys and their relative order survive a setField") {
            val md =
                """
                |---
                |resource: some-resource
                |type: Concept
                |timestamp: 2026-06-16T10:00:00Z
                |---
                |Body
                """.trimMargin()

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe
                """
                |---
                |resource: some-resource
                |type: Article
                |timestamp: 2026-06-16T10:00:00Z
                |---
                |Body
                """.trimMargin()
        }

        test("a missing key is inserted immediately before the closing fence, not after") {
            val md =
                """
                |---
                |type: Concept
                |---
                |Body
                """.trimMargin()

            val result = FrontmatterWriter.setField(markdown = md, key = "title", value = "Order")

            result shouldBe
                """
                |---
                |type: Concept
                |title: Order
                |---
                |Body
                """.trimMargin()
        }

        test("no frontmatter block at all: a new block is created at the top, body unchanged") {
            val md = "# Just a heading\n\nSome prose."

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Concept")

            result shouldBe "---\ntype: Concept\n---\n# Just a heading\n\nSome prose."
        }

        test("an unterminated opening fence counts as 'no frontmatter' — the stray '---' becomes body") {
            val md = "---\ntype: Concept\n# no closing fence"

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe "---\ntype: Article\n---\n---\ntype: Concept\n# no closing fence"
        }

        test("escaping: a value containing a colon, hash, quote, or surrounding whitespace round-trips") {
            val md = "---\ntype: Concept\n---\nBody"

            listOf("Foo: Bar", "#tag", "He said \"hi\"", " leading space", "trailing space ").forEach { raw ->
                val written = FrontmatterWriter.setField(markdown = md, key = "title", value = raw)
                val parsed = FrontmatterParser.parse(written)
                parsed.title shouldBe raw
            }
        }

        test(
            "escaping: a value that itself starts and ends with a single quote round-trips " +
                "(regression — FrontmatterParser.unquote unconditionally strips a matching " +
                "outer single-quote pair, so an unquoted 'Freiheit' would come back as Freiheit)",
        ) {
            val md = "---\ntype: Concept\n---\nBody"

            listOf("'Freiheit'", "'", "''", "'a'").forEach { raw ->
                val written = FrontmatterWriter.setField(markdown = md, key = "title", value = raw)
                val parsed = FrontmatterParser.parse(written)
                parsed.title shouldBe raw
            }
        }

        test("a CRLF document stays entirely CRLF after setField — no line is silently switched to LF") {
            val md = "---\r\ntype: Concept\r\ntitle: Order\r\n---\r\nBody\r\n"

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe "---\r\ntype: Article\r\ntitle: Order\r\n---\r\nBody\r\n"
        }

        test(
            "a document with mostly LF lines and a single embedded CRLF (e.g. a pasted " +
                "Windows code block) keeps every LF line as LF after setField — regression " +
                "for the whole-file CRLF flip bug",
        ) {
            val md = "---\ntype: Concept\n---\n```\r\nWindows code\r\n```\nMore LF prose\n"

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe "---\ntype: Article\n---\n```\r\nWindows code\r\n```\nMore LF prose\n"
        }

        test("a document with mostly CRLF lines and a single embedded LF keeps that LF line untouched after setField") {
            val md = "---\r\ntype: Concept\r\n---\r\nBody line one\nBody line two\r\n"

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe "---\r\ntype: Article\r\n---\r\nBody line one\nBody line two\r\n"
        }

        test("inserting a missing key into a CRLF frontmatter block uses CRLF for the new line, not LF") {
            val md = "---\r\ntype: Concept\r\n---\r\nBody\r\n"

            val result = FrontmatterWriter.setField(markdown = md, key = "title", value = "Order")

            result shouldBe "---\r\ntype: Concept\r\ntitle: Order\r\n---\r\nBody\r\n"
        }

        test("a missing trailing newline stays missing") {
            val md = "---\ntype: Concept\n---\nBody"

            val result = FrontmatterWriter.setField(markdown = md, key = "type", value = "Article")

            result shouldBe "---\ntype: Article\n---\nBody"
            result.endsWith("\n") shouldBe false
        }

        test("round-trip: setField followed by FrontmatterParser.parse recovers every OkfType.id exactly") {
            val md = "---\ntype: Concept\n---\nBody"

            OkfType.entries.forEach { type ->
                val written = FrontmatterWriter.setField(markdown = md, key = "type", value = type.id)
                FrontmatterParser.parse(written).type shouldBe type.id
            }
        }

        test("removeField removes exactly one line, no-op when the key is absent") {
            val md =
                """
                |---
                |type: Concept
                |title: Order
                |---
                |Body
                """.trimMargin()

            val removed = FrontmatterWriter.removeField(markdown = md, key = "title")
            removed shouldBe
                """
                |---
                |type: Concept
                |---
                |Body
                """.trimMargin()

            val noOp = FrontmatterWriter.removeField(markdown = md, key = "does-not-exist")
            noOp shouldBe md
        }

        test("removeField is a no-op when there is no frontmatter block at all") {
            val md = "# Just a heading\n\nProse."
            FrontmatterWriter.removeField(markdown = md, key = "type") shouldBe md
        }
    })
