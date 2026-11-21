@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmNotation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 127: ERM self-references use the shared right-hand loop in the LaTeX output (structural, font independent). */
class ErmLatexSelfReferenceTest :
    StringSpec({
        val coord = Regex("""\((-?[0-9.]+)pt, (-?[0-9.]+)pt\)""")

        /** TikZ point in layout space (y down): the renderer negates y. */
        fun points(line: String): List<Pair<Float, Float>> =
            coord.findAll(line).map { it.groupValues[1].toFloat() to -it.groupValues[2].toFloat() }.toList()

        fun lineWith(
            tex: String,
            needle: String,
        ): String = tex.lines().single { it.contains(needle) }

        val bounds = ErmLatexFixtures.employeeBounds
        val right = bounds.origin.x + bounds.size.width
        val top = bounds.origin.y
        val h = bounds.size.height
        val notations = listOf(ErmNotation.MARTIN, ErmNotation.BACHMAN, ErmNotation.IDEF1X)

        for (notation in notations) {
            "$notation: the loop is drawn on the right side with two rounded corners" {
                val tex = ErmLatexFixtures.renderSelfReference(notation)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                loop.size shouldBe 4
                val (source, wp1, wp2, target) = loop
                source.first shouldBe (right plusOrMinus 0.01f)
                target.first shouldBe (right plusOrMinus 0.01f)
                wp1.first shouldBe (right + 32f plusOrMinus 0.01f)
                wp2.first shouldBe (right + 32f plusOrMinus 0.01f)
                source.second shouldBe (top + 0.30f * h plusOrMinus 0.01f)
                target.second shouldBe (top + 0.70f * h plusOrMinus 0.01f)
                (target.second - source.second) shouldBeGreaterThanOrEqual (0.4f * h - 0.01f)
            }

            "$notation: the glyph boxes at the two loop ends do not overlap" {
                val tex = ErmLatexFixtures.renderSelfReference(notation)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                // A glyph is 16 px high around its anchor; the two anchors must be further apart than that.
                (loop[3].second - loop[0].second) shouldBeGreaterThan 16f
            }

            "$notation: roles sit above and below the loop ends outside the box, the name right of the loop" {
                val tex = ErmLatexFixtures.renderSelfReference(notation)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                val sourceRole = points(lineWith(tex, "{manager}")).single()
                val targetRole = points(lineWith(tex, "{report}")).single()
                val name = points(lineWith(tex, "{manages}")).single()
                sourceRole.second shouldBeLessThan loop[0].second - 8f
                targetRole.second shouldBeGreaterThan loop[3].second + 8f
                sourceRole.first shouldBeGreaterThan right
                targetRole.first shouldBeGreaterThan right
                name.first shouldBeGreaterThan right + 32f
                lineWith(tex, "{manager}") shouldNotBe lineWith(tex, "{report}")
            }

            "$notation: the name has no white background so the loop line stays uninterrupted" {
                val tex = ErmLatexFixtures.renderSelfReference(notation)
                val nameLine = lineWith(tex, "{manages}")
                nameLine shouldNotContain "fill=white"
                (nameLine.contains("anchor=west")) shouldBe true
            }
        }

        "a diagram without a self-reference has no west-anchored name node" {
            for (notation in notations) {
                val tex = ErmLatexFixtures.render(notation = notation)
                tex shouldNotContain "anchor=west] at"
                tex shouldNotContain "anchor=south west] at"
            }
        }
    })
