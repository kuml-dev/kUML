package dev.kuml.cli

import dev.kuml.runtime.chain.ModelHasher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Verifies that [KumlFormatter.canonical] delegates correctly to [ModelHasher.canonicalize]
 * and that the two are byte-identical (single source of truth).
 *
 * Also guards the semantic difference between [KumlFormatter.format] (collapse blank lines)
 * and [KumlFormatter.canonical] (remove all blank lines).
 */
class CanonicalizerTest :
    StringSpec({
        "canonical: removes ALL blank lines (stricter than format)" {
            val input = "model {\n\n    state(\"A\")\n\n\n    state(\"B\")\n\n}\n"
            val formatted = KumlFormatter.format(input)
            val canonical = KumlFormatter.canonical(input)

            // format() collapses to at most one blank line
            formatted shouldBe "model {\n\n    state(\"A\")\n\n    state(\"B\")\n\n}\n"
            // canonical() removes all blank lines
            canonical shouldBe "model {\n    state(\"A\")\n    state(\"B\")\n}\n"
        }

        "canonical: format and canonical differ on multi-blank-line inputs" {
            val input = "a\n\n\nb\n"
            KumlFormatter.format(input) shouldNotBe KumlFormatter.canonical(input)
        }

        "canonical: is idempotent" {
            val inputs =
                listOf(
                    "model { state(\"A\") }",
                    "\t\tmodel {\r\n\r\n\tstate(\"X\")  \r\n}\r\n",
                    "",
                    "   \n\n\t",
                    "a\n\n\nb\n\nc\n",
                )
            for (input in inputs) {
                val once = KumlFormatter.canonical(input)
                KumlFormatter.canonical(once) shouldBe once
            }
        }

        "canonical: already-canonical input is identity" {
            val alreadyCanonical = "model {\n    state(\"A\")\n    state(\"B\")\n}\n"
            KumlFormatter.canonical(alreadyCanonical) shouldBe alreadyCanonical
        }

        "canonical: delegates to ModelHasher.canonicalize (single source of truth)" {
            val inputs =
                listOf(
                    "model { state(\"A\") }",
                    "\t\tindented\r\ncontent  \r\n",
                    "",
                    "a\n\n\nb\n",
                )
            for (input in inputs) {
                KumlFormatter.canonical(input) shouldBe ModelHasher.canonicalize(input)
            }
        }

        "canonical: CRLF normalised to LF" {
            val crlf = "model {\r\n    state(\"A\")\r\n}\r\n"
            val expected = "model {\n    state(\"A\")\n}\n"
            KumlFormatter.canonical(crlf) shouldBe expected
        }
    })
