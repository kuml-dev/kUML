package dev.kuml.io.latex

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LatexEscapeTest :
    StringSpec({

        "passes through plain ASCII unchanged" {
            escapeLatex("Order") shouldBe "Order"
            escapeLatex("hello world") shouldBe "hello world"
        }

        "escapes every LaTeX-special character" {
            escapeLatex("a_b") shouldBe "a\\_b"
            escapeLatex("100%") shouldBe "100\\%"
            escapeLatex("a&b") shouldBe "a\\&b"
            escapeLatex("\$money") shouldBe "\\\$money"
            escapeLatex("a#1") shouldBe "a\\#1"
            escapeLatex("{x}") shouldBe "\\{x\\}"
            escapeLatex("a~b") shouldBe "a\\textasciitilde{}b"
            escapeLatex("a^b") shouldBe "a\\textasciicircum{}b"
            escapeLatex("a<b>c") shouldBe "a\\textless{}b\\textgreater{}c"
            escapeLatex("\\foo") shouldBe "\\textbackslash{}foo"
        }

        "maps French guillemets to the babel/textcomp macros (stereotype-safe)" {
            // Stereotypes everywhere — must survive escaping intact in the
            // produced TikZ block.
            escapeLatex("«SoftwareComponent»") shouldBe "\\guillemotleft{}SoftwareComponent\\guillemotright{}"
        }

        "fmtCoord uses dot decimal separator regardless of locale" {
            fmtCoord(1.5f) shouldBe "1.500"
            fmtCoord(-0.001f) shouldBe "-0.001"
            fmtCoord(0f) shouldBe "0.000"
        }

        "fmtCoord rounds to three decimal places" {
            fmtCoord(1.23456789f) shouldBe "1.235"
        }

        "fmtCoord guards against NaN/Infinity" {
            // The layout engine *shouldn't* produce these, but a defensive renderer
            // is friendlier than a TeX-side `! Missing number` from `\node at (NaNpt, …)`.
            fmtCoord(Float.NaN) shouldBe "0.000"
            fmtCoord(Float.POSITIVE_INFINITY) shouldBe "0.000"
            fmtCoord(Float.NEGATIVE_INFINITY) shouldBe "0.000"
        }
    })
