package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FrontmatterParserTest :
    FunSpec({

        test("parses scalar fields") {
            val md =
                """
                |---
                |type: Concept
                |title: Order
                |description: A customer purchase request
                |---
                |# Body
                |Text.
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.present shouldBe true
            fm.type shouldBe "Concept"
            fm.title shouldBe "Order"
            fm.fields["description"] shouldBe "A customer purchase request"
        }

        test("parses inline tags list") {
            val md =
                """
                |---
                |type: Article
                |tags: [shop, overview]
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.tags shouldBe listOf("shop", "overview")
        }

        test("parses block-form tags list") {
            val md =
                """
                |---
                |type: Article
                |tags:
                |  - shop
                |  - overview
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.tags shouldBe listOf("shop", "overview")
        }

        test("no leading frontmatter fence yields present = false") {
            val md = "# Just a heading\n\nNo frontmatter here."
            val fm = FrontmatterParser.parse(md)
            fm.present shouldBe false
            fm.type shouldBe null
            fm.bodyStartLine shouldBe 1
        }

        test("missing closing fence yields present = false") {
            val md =
                """
                |---
                |type: Concept
                |# no closing fence
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.present shouldBe false
        }

        test("bodyStartLine points to the line after the closing fence") {
            val md =
                """
                |---
                |type: Concept
                |---
                |# Body starts here
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            // Lines: 1 "---", 2 "type: Concept", 3 "---", 4 "# Body starts here"
            fm.bodyStartLine shouldBe 4
        }

        test("value containing a colon is preserved (split on first colon only)") {
            val md =
                """
                |---
                |type: Concept
                |timestamp: 2026-06-16T10:00:00Z
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.fields["timestamp"] shouldBe "2026-06-16T10:00:00Z"
        }

        test("quoted scalar values are unquoted") {
            val md =
                """
                |---
                |type: Concept
                |title: "Quoted Title"
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.title shouldBe "Quoted Title"
        }

        test("a double-quoted value un-escapes \\\" back to a literal double quote") {
            val md =
                """
                |---
                |type: Concept
                |title: "He said \"hi\""
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.title shouldBe "He said \"hi\""
        }

        test("a double-quoted value un-escapes \\\\ back to a single backslash") {
            val md =
                """
                |---
                |type: Concept
                |title: "C:\\Users\\irakli"
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.title shouldBe "C:\\Users\\irakli"
        }

        test("a single-quoted value is NOT un-escaped (no writer in this codebase produces one)") {
            val md =
                """
                |---
                |type: Concept
                |title: 'literal \"backslash-quote\"'
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.title shouldBe """literal \"backslash-quote\""""
        }

        test("a backslash outside of double quotes is left alone") {
            val md =
                """
                |---
                |type: Concept
                |title: C:\Users\irakli
                |---
                |Body
                """.trimMargin()
            val fm = FrontmatterParser.parse(md)
            fm.title shouldBe """C:\Users\irakli"""
        }
    })
