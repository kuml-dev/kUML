package dev.kuml.style.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

/**
 * Pure-function tests of [wrapKumlScript] — no Analysis API involved, so
 * these run in milliseconds and pin down the offset-mapping invariant
 * ([WrappedKumlScript.prefixLen]) the rest of the worker depends on.
 */
class KumlScriptWrapperTest :
    FunSpec({

        test("prefix contains all 17 default imports") {
            val wrapped = wrapKumlScript("diagram(name = \"X\", type = DiagramType.CLASS) {}")
            KUML_DEFAULT_IMPORTS.forEach { imp ->
                wrapped.wrappedText shouldContain "import $imp"
            }
        }

        test("wraps the body inside a synthetic function") {
            val wrapped = wrapKumlScript("val x = 1")
            wrapped.wrappedText shouldContain "fun __kumlStyleCheckWrapper__() {"
            wrapped.wrappedText shouldContain "val x = 1"
        }

        test("hoists an import line out of the body and blanks it in place") {
            val original = "import dev.kuml.fixture.*\nval x = 1"
            val wrapped = wrapKumlScript(original)
            // Hoisted into the prefix ahead of the function...
            wrapped.wrappedText shouldContain "import dev.kuml.fixture.*\n"
            // ...and the original body line is blanked, not deleted (length-preserving).
            val bodyStart = wrapped.wrappedText.indexOf("fun __kumlStyleCheckWrapper__")
            val body = wrapped.wrappedText.substring(bodyStart)
            body shouldNotContain "import dev.kuml.fixture"
        }

        test("blanks an @file: annotation line in place") {
            val original = "@file:Suppress(\"unused\")\nval x = 1"
            val wrapped = wrapKumlScript(original)
            wrapped.wrappedText shouldNotContain "@file:Suppress"
        }

        test("blanking preserves line count and per-line length (offset-mapping invariant)") {
            val original = "import dev.kuml.fixture.*\n@file:Suppress(\"unused\")\nval x = 1\n"
            val wrapped = wrapKumlScript(original)
            val bodyStart =
                wrapped.wrappedText.indexOf("fun __kumlStyleCheckWrapper__() {\n") +
                    "fun __kumlStyleCheckWrapper__() {\n".length
            val body = wrapped.wrappedText.substring(bodyStart, bodyStart + original.length)
            body.length shouldBe original.length
            // Every original character is either preserved verbatim or
            // replaced with a space at the SAME index — a blanked line never
            // removes or shifts a character, which is what keeps
            // `originalOffset = wrappedOffset - prefixLen` a valid constant
            // subtraction for every UN-blanked line below it.
            for (i in original.indices) {
                val o = original[i]
                val b = body[i]
                (b == o || b == ' ') shouldBe true
            }
            // Sanity: the hoisted lines really were blanked, not left intact.
            body shouldNotContain "import dev.kuml.fixture"
            body shouldNotContain "@file:Suppress"
        }

        test("empty script produces a syntactically valid wrapper") {
            val wrapped = wrapKumlScript("")
            wrapped.wrappedText shouldContain "fun __kumlStyleCheckWrapper__() {"
            wrapped.wrappedText.trimEnd() shouldEndWith "}"
        }
    })
