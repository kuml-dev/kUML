@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexFamily
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private fun count(
    text: String,
    needle: String,
): Int = text.windowed(needle.length).count { it == needle }

class ErmLatexRendererTest :
    StringSpec({
        // Nacharbeit 123b: the ERM renderer sets \sffamily\scriptsize\bfseries. In T1 without lmodern that is an EC
        // bitmap-only series and size; a TeX Live without cm-super (the apt set on CI) then fails with
        // "Font ecsx0800 at 525 not found". The standalone preamble must load lmodern after fontenc.
        "standalone preamble loads lmodern after fontenc so bold small sans has Type 1 outlines" {
            val tex = ErmLatexFixtures.render(options = LatexRenderOptions(standalone = true))
            tex shouldContain "\\usepackage{lmodern}"
            tex.indexOf("\\usepackage{lmodern}") shouldBeGreaterThan tex.indexOf("\\usepackage[T1]{fontenc}")
            tex shouldContain "\\sffamily\\scriptsize\\bfseries"
        }

        "snippet has a picture, the entity boxes and no document skeleton" {
            val tex = ErmLatexFixtures.render()
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
            tex shouldNotContain "\\documentclass"
            for (id in listOf("customer", "order", "item")) tex shouldContain "(n_$id)"
        }

        "attribute rows carry PK, FK, unique markers, escaped names and types" {
            val tex = ErmLatexFixtures.render()
            tex shouldContain "{PK}"
            tex shouldContain "{FK}"
            tex shouldContain "{U}"
            tex shouldContain "Customer\\_Account"
            tex shouldContain "\\underline{id} : UUID"
            tex shouldContain "e\\_mail \\& co : VARCHAR(255) NN"
            tex shouldContain "discount\\_\\% : DECIMAL(5,2)"
            tex shouldContain "= 0"
            tex shouldNotContain "e_mail"
        }

        "a weak entity gets a second inner border" {
            val tex = ErmLatexFixtures.render()
            // inner rectangle of OrderItem: (403, -263) rectangle (597, -337)
            tex shouldContain "\\draw[line width=0.6pt] (403.000pt, -263.000pt) rectangle (597.000pt, -337.000pt);"
            count(tex, " rectangle ") shouldBe 1
        }

        "cardinality glyphs sit at the correct end of each relationship" {
            val tex = ErmLatexFixtures.render()
            // r1: source ONE (bar at the customer end), target ZERO_MANY (crow's foot + circle at the order end).
            // The customer end is at y=160 (layout) and the line runs down, so the bar is at y=166.
            tex shouldContain "(115.000pt, -166.000pt) -- (125.000pt, -166.000pt)"
            // crow's foot apex 14 above the order border (y=260-14=246) with its prongs on the border.
            tex shouldContain "(126.000pt, -260.000pt) -- (120.000pt, -246.000pt) -- (114.000pt, -260.000pt)"
            // circle 20 + 4 above the order border
            tex shouldContain "(120.000pt, -236.000pt) circle (4.000pt)"
            // r2: source ZERO_ONE (circle) and target ONE_MANY (bar + crow's foot); two circles in total
            count(tex, "circle (4.000pt)") shouldBe 2
            // r2 target (ONE_MANY, horizontal): crow's foot on the item border and a bar 20 before it
            tex shouldContain "(380.000pt, -295.000pt) -- (380.000pt, -305.000pt)"
        }

        "relationship kind decides solid or dashed, labels use the middle of the route" {
            val tex = ErmLatexFixtures.render()
            val lines = tex.lines()
            lines.count { it.contains("kuml-edge-plain, dashed, -") } shouldBe 1
            lines.count { it.contains("kuml-edge-plain, -]") } shouldBe 1
            tex shouldContain "{places}"
            tex shouldContain "{contains}"
            tex shouldContain "{buyer}"
        }

        "standalone, fragment and shared preamble variants" {
            val standalone = ErmLatexFixtures.render(options = LatexRenderOptions(standalone = true))
            standalone shouldStartWith "\\documentclass[border=10pt]{standalone}"
            standalone.trimEnd().endsWith("\\end{document}") shouldBe true
            standalone shouldContain "\\tikzset{"
            val shared = ErmLatexFixtures.render(options = LatexRenderOptions(sharedPreamble = true))
            shared shouldNotContain "\\tikzset{kuml-class"
            shared shouldStartWith "\\begin{tikzpicture}"
            val scaled = ErmLatexFixtures.render(options = LatexRenderOptions(scale = 0.5))
            scaled shouldContain "\\begin{tikzpicture}[scale=0.500"
        }

        "ERM family needs no extra TikZ libraries and keeps the shared preamble deterministic" {
            LatexFamily.ERM.extraTikzLibraries shouldBe emptyList()
            val a = KumlLatexRenderer.sharedPreamble(linkedSetOf(LatexFamily.ERM, LatexFamily.UML))
            val b = KumlLatexRenderer.sharedPreamble(linkedSetOf(LatexFamily.UML, LatexFamily.ERM))
            a shouldBe b
            KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.ERM)) shouldBe KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.UML))
        }

        "only Chen is refused with the documented message" {
            val ex = shouldThrow<UnsupportedOperationException> { ErmLatexFixtures.render(notation = ErmNotation.CHEN) }
            ex.message shouldBe "ERM notation CHEN is not supported in LaTeX yet (supported: MARTIN, BACHMAN, IDEF1X)"
            ex.message shouldBe KumlLatexRenderer.ermUnsupportedNotationMessage(ErmNotation.CHEN)
            KumlLatexRenderer.ermLatexSupports(ErmNotation.CHEN) shouldBe false
        }

        "Bachman and IDEF1X no longer throw" {
            ErmLatexFixtures.render(notation = ErmNotation.BACHMAN) shouldContain "\\begin{tikzpicture}"
            ErmLatexFixtures.render(notation = ErmNotation.IDEF1X) shouldContain "\\begin{tikzpicture}"
            KumlLatexRenderer.ermLatexNotations.toList() shouldBe listOf(ErmNotation.MARTIN, ErmNotation.BACHMAN, ErmNotation.IDEF1X)
        }

        "output is deterministic" {
            ErmLatexFixtures.render(withNote = true) shouldBe ErmLatexFixtures.render(withNote = true)
        }

        "a note is drawn with its anchor line" {
            val tex = ErmLatexFixtures.render(withNote = true)
            tex shouldContain "% kUML note styles"
            tex shouldContain "(n_n1)"
            tex shouldContain "Orders stay"
            tex shouldContain "kuml-note-anchor"
            ErmLatexFixtures.render() shouldNotContain "kUML note styles"
        }

        "a side hint moves the note (coordinates differ from the unplaced note)" {
            val plain = ErmLatexFixtures.render(withNote = true)
            val hinted = ErmLatexFixtures.render(withNote = true, noteHint = NotePlacement.BELOW)
            hinted shouldNotBe plain
            hinted shouldContain "Orders stay"
        }

        "glyph end directions follow the first and the last segment" {
            val route =
                EdgeRoute.OrthogonalRounded(
                    source = Point(0f, 0f),
                    target = Point(100f, 50f),
                    waypoints = listOf(Point(0f, 50f)),
                    cornerRadiusPx = 0f,
                )
            val d = CrowFootTikz.endDirections(route)
            d.source.first shouldBe (0f plusOrMinus 1e-4f)
            d.source.second shouldBe (1f plusOrMinus 1e-4f)
            // the target end points back along the last segment, i.e. to the left
            d.target.first shouldBe (-1f plusOrMinus 1e-4f)
            d.target.second shouldBe (0f plusOrMinus 1e-4f)
        }

        "each cardinality yields its glyph parts" {
            fun glyph(c: Cardinality): String =
                StringBuilder()
                    .also {
                        CrowFootTikz.appendGlyph(
                            anchor = Point(0f, 0f),
                            dir = 1f to 0f,
                            cardinality = c,
                            options = LatexRenderOptions(),
                            out = it,
                        )
                    }.toString()
            count(glyph(Cardinality.ONE), "\\draw") shouldBe 1
            glyph(Cardinality.ONE) shouldNotContain "circle"
            glyph(Cardinality.ZERO_ONE) shouldContain "circle"
            count(glyph(Cardinality.ZERO_ONE), "\\draw") shouldBe 1
            count(glyph(Cardinality.ONE_MANY), "\\draw") shouldBe 3
            glyph(Cardinality.ONE_MANY) shouldNotContain "circle"
            count(glyph(Cardinality.ZERO_MANY), "\\draw") shouldBe 3
            glyph(Cardinality.ZERO_MANY) shouldContain "circle"
        }
    })
