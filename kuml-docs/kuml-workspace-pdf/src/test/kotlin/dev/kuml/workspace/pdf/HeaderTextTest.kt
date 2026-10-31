package dev.kuml.workspace.pdf

import dev.kuml.workspace.pdf.layout.HeaderText
import dev.kuml.workspace.pdf.layout.TextMeasurer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class HeaderTextTest :
    FunSpec({
        fun measurer(glyphs: Set<Char>? = null) =
            object : TextMeasurer {
                override fun width(
                    text: String,
                    sizePt: Float,
                ) = text.length * 5f

                override fun hasGlyph(c: Char) = glyphs?.contains(c) ?: true
            }
        val suffix = " — Teil 3 von 9 · Zeile 1, Spalte 3"

        test("a short title stays unchanged") {
            HeaderText.fit(
                prefix = "Abbildung: ",
                title = "Kurz",
                suffix = suffix,
                maxWidth = 1000f,
                sizePt = 10.5f,
                m = measurer(),
            ) shouldBe
                "Abbildung: Kurz$suffix"
        }
        test("only the title is shortened, prefix and suffix survive") {
            val r =
                HeaderText.fit(
                    prefix = "Abbildung: ",
                    title = "X".repeat(300),
                    suffix = suffix,
                    maxWidth = 400f,
                    sizePt = 10.5f,
                    m = measurer(),
                )
            r shouldStartWith "Abbildung: "
            r shouldEndWith "…$suffix"
            (r.length * 5f <= 400f) shouldBe true
        }
        test("cut at a word end when at most 20 percent are lost") {
            val r =
                HeaderText.fit(
                    prefix = "",
                    title = "alpha beta gammadeltaepsilonzeta",
                    suffix = "",
                    maxWidth = 65f,
                    sizePt = 10f,
                    m = measurer(),
                )
            r shouldBe "alpha beta…"
        }
        test("cut at the character when the last word is too long") {
            val r = HeaderText.fit(prefix = "", title = "ab " + "x".repeat(100), suffix = "", maxWidth = 75f, sizePt = 10f, m = measurer())
            r shouldStartWith "ab xxx"
            r shouldEndWith "…"
            (r.length * 5f <= 75f) shouldBe true
        }
        test("without an ellipsis glyph three dots are used") {
            val r =
                HeaderText.fit(
                    prefix = "",
                    title = "x".repeat(100),
                    suffix = "",
                    maxWidth = 100f,
                    sizePt = 10f,
                    m = measurer(glyphs = setOf('x')),
                )
            r shouldEndWith "..."
        }
        test("tiny width leaves prefix, ellipsis and suffix") {
            HeaderText.fit(prefix = "A: ", title = "Titel", suffix = " (1/2)", maxWidth = 10f, sizePt = 10f, m = measurer()) shouldBe
                "A: … (1/2)"
        }
        test("truncate shortens plain text") {
            HeaderText.truncate(text = "x".repeat(100), maxWidth = 50f, sizePt = 10f, m = measurer()).let {
                it shouldEndWith "…"
                (it.length * 5f <= 50f) shouldBe true
            }
            HeaderText.truncate(text = "short", maxWidth = 50f, sizePt = 10f, m = measurer()) shouldBe "short"
        }
    })
