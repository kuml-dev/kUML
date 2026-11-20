package dev.kuml.desktop.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OkfAliasNormalizeTest :
    FunSpec({
        test("alias type is replaced by the canonical id") {
            normaliseAliasTypeInBuffer("---\ntype: Sysml2Bdd\n---\nbody\n") shouldBe
                "---\ntype: Sysml2BlockDefinition\n---\nbody\n"
        }

        test("canonical type returns null") {
            normaliseAliasTypeInBuffer("---\ntype: Sysml2BlockDefinition\n---\n") shouldBe null
        }

        test("unknown type returns null") {
            normaliseAliasTypeInBuffer("---\ntype: Nonsense\n---\n") shouldBe null
        }

        test("no frontmatter returns null") {
            normaliseAliasTypeInBuffer("# Just text\n") shouldBe null
        }

        test("CRLF, spacing and trailing newline survive; only the alias changes") {
            val input = "---\r\ntitle: T\r\ntype:   Sysml2Bdd  \r\n---\r\nBody\r\n"
            normaliseAliasTypeInBuffer(input) shouldBe
                "---\r\ntitle: T\r\ntype:   Sysml2BlockDefinition  \r\n---\r\nBody\r\n"
        }

        test("a BOM is never dropped, whatever the outcome") {
            val input = "\uFEFF---\ntype: Sysml2Bdd\n---\nx\n"
            val out = normaliseAliasTypeInBuffer(input)
            (out == null || out.startsWith("\uFEFF---\ntype: Sysml2BlockDefinition")) shouldBe true
        }
    })
