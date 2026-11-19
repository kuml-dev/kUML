package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OkfAliasFixTest :
    FunSpec({

        val alias = "Sysml2Bdd"
        val canonical = "Sysml2BlockDefinition"

        test("alias becomes canonical and nothing else changes") {
            val input = "---\ntype: $alias\ntitle: X\n---\nbody"
            OkfAliasFix.canonicalizeType(input) shouldBe "---\ntype: $canonical\ntitle: X\n---\nbody"
        }

        test("canonical id is a no-op") {
            OkfAliasFix.canonicalizeType("---\ntype: $canonical\n---\nb") shouldBe null
        }

        test("unknown id is a no-op") {
            OkfAliasFix.canonicalizeType("---\ntype: Nonsense\n---\nb") shouldBe null
        }

        test("no frontmatter, no type and unclosed frontmatter are no-ops") {
            OkfAliasFix.canonicalizeType("type: $alias\nbody") shouldBe null
            OkfAliasFix.canonicalizeType("---\ntitle: X\n---\nbody") shouldBe null
            OkfAliasFix.canonicalizeType("---\ntype: $alias\nbody") shouldBe null
        }

        test("bytes are preserved apart from the alias") {
            val input =
                "---\n# comment\ntype:   $alias\ntags: [a, b]\n---\nBody mentions $alias.\n```kuml\n$alias\n```\n"
            val expected = input.replaceFirst("type:   $alias", "type:   $canonical")
            OkfAliasFix.canonicalizeType(input) shouldBe expected
        }

        test("CRLF and mixed line endings are preserved") {
            val crlf = "---\r\ntype: $alias\r\ntitle: X\r\n---\r\nbody\r\n"
            val out = OkfAliasFix.canonicalizeType(crlf)!!
            out shouldBe crlf.replace(alias, canonical)
            out.count { it == '\r' } shouldBe crlf.count { it == '\r' }
            val mixed = "---\ntype: $alias\r\ntitle: X\n---\r\nbody"
            OkfAliasFix.canonicalizeType(mixed) shouldBe mixed.replace(alias, canonical)
        }

        test("quotes are preserved") {
            OkfAliasFix.canonicalizeType("---\ntype: '$alias'\n---\n") shouldBe "---\ntype: '$canonical'\n---\n"
            OkfAliasFix.canonicalizeType("---\ntype: \"$alias\"\n---\n") shouldBe "---\ntype: \"$canonical\"\n---\n"
        }

        test("alias only in the body is not touched") {
            OkfAliasFix.canonicalizeType("---\ntype: Concept\n---\ntype: $alias\n") shouldBe null
        }

        test("fix is idempotent") {
            val once = OkfAliasFix.canonicalizeType("---\ntype: $alias\n---\nb")!!
            OkfAliasFix.canonicalizeType(once) shouldBe null
        }

        test("a type line inside a body code fence is not touched") {
            val input = "---\ntype: $alias\n---\n```yaml\ntype: $alias\n```\n"
            OkfAliasFix.canonicalizeType(input) shouldBe input.replaceFirst(alias, canonical)
        }

        test("typeLineNumber returns the 1-based line") {
            OkfAliasFix.typeLineNumber("---\ntitle: X\ntype: $alias\n---\n") shouldBe 3
            OkfAliasFix.typeLineNumber("no frontmatter") shouldBe null
        }
    })
