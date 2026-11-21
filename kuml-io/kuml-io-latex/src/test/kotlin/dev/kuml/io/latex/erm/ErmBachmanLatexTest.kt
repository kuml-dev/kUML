@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexFamily
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.Point
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private fun count(
    text: String,
    needle: String,
): Int = text.windowed(needle.length).count { it == needle }

private fun bachman(
    options: LatexRenderOptions = LatexRenderOptions(),
    withNote: Boolean = false,
    noteHint: NotePlacement? = null,
): String = ErmLatexFixtures.render(notation = ErmNotation.BACHMAN, options = options, withNote = withNote, noteHint = noteHint)

/** Structural, font-independent tests: they inspect the TikZ text and need no TeX installation. */
class ErmBachmanLatexTest :
    StringSpec({
        "many end: filled triangle with its apex on the border, then an open circle behind it" {
            val tex = bachman()
            // r1 target ZERO_MANY at the order border (y=260), line runs down so the glyph extends upwards (y smaller).
            tex shouldContain
                "\\filldraw[line width=0.6pt] (120.000pt, -260.000pt) -- (125.000pt, -248.000pt) -- (115.000pt, -248.000pt) -- cycle;"
            // circle centre 12 + 6 + 4 = 22 above the border
            tex shouldContain "\\draw[line width=0.6pt, fill=white] (120.000pt, -238.000pt) circle (4.000pt);"
        }

        "one end: filled circle on the line, no triangle at that end" {
            val tex = bachman()
            // r1 source ONE at y=160, circle centre 6 + 4 below
            tex shouldContain "\\fill (120.000pt, -170.000pt) circle (4.000pt);"
            tex shouldNotContain "\\filldraw[line width=0.6pt] (120.000pt, -160.000pt)"
        }

        "horizontal relationship: open circle without arrow at the source, triangle plus filled circle at the target" {
            val tex = bachman()
            // r2 source ZERO_ONE at (220,310), direction +x: circle centre at x=230
            tex shouldContain "\\draw[line width=0.6pt, fill=white] (230.000pt, -310.000pt) circle (4.000pt);"
            // r2 target ONE_MANY at (400,300), direction -x: triangle base at x=388, circle at 400-22=378
            tex shouldContain
                "\\filldraw[line width=0.6pt] (400.000pt, -300.000pt) -- (388.000pt, -295.000pt) -- (388.000pt, -305.000pt) -- cycle;"
            tex shouldContain "\\fill (378.000pt, -300.000pt) circle (4.000pt);"
        }

        "glyph counts and no Martin shapes" {
            val tex = bachman()
            count(tex, "\\filldraw") shouldBe 2
            count(tex, "circle (4.000pt)") shouldBe 4
            count(tex, "\\fill (") shouldBe 2
            // no crow's-foot prong line and no bar tick of the Martin notation
            tex shouldNotContain "(115.000pt, -166.000pt) -- (125.000pt, -166.000pt)"
            tex shouldNotContain "(126.000pt, -260.000pt) --"
            tex shouldNotBe ErmLatexFixtures.render(notation = ErmNotation.MARTIN)
        }

        "each cardinality yields its glyph parts" {
            fun glyph(c: Cardinality): String =
                StringBuilder()
                    .also { BachmanTikz.appendGlyph(Point(0f, 0f), 1f to 0f, c, LatexRenderOptions(), it) }
                    .toString()
            count(glyph(Cardinality.ONE), "\\") shouldBe 1
            glyph(Cardinality.ONE) shouldContain "\\fill"
            glyph(Cardinality.ZERO_ONE) shouldContain "fill=white"
            count(glyph(Cardinality.ONE_MANY), "\\fill (") shouldBe 1
            count(glyph(Cardinality.ONE_MANY), "\\filldraw") shouldBe 1
            glyph(Cardinality.ZERO_MANY) shouldContain "\\filldraw"
            glyph(Cardinality.ZERO_MANY) shouldContain "fill=white"
        }

        "solid versus dashed, names, roles and escaping" {
            val tex = bachman()
            val lines = tex.lines()
            lines.count { it.contains("kuml-edge-plain, dashed, -") } shouldBe 1
            lines.count { it.contains("kuml-edge-plain, -]") } shouldBe 1
            tex shouldContain "{places}"
            tex shouldContain "{contains}"
            tex shouldContain "{buyer}"
            tex shouldContain "Customer\\_Account"
            tex shouldContain "e\\_mail \\& co"
        }

        "a weak entity keeps its square inner border" {
            val tex = bachman()
            tex shouldContain "\\draw[line width=0.6pt] (403.000pt, -263.000pt) rectangle (597.000pt, -337.000pt);"
            tex shouldNotContain "rounded corners=8"
            tex shouldNotContain ", rounded corners=5"
        }

        "a note is drawn with its anchor and a side hint moves it" {
            val plain = bachman(withNote = true)
            plain shouldContain "Orders stay"
            plain shouldContain "kuml-note-anchor"
            bachman(withNote = true, noteHint = NotePlacement.BELOW) shouldNotBe plain
        }

        "deterministic, standalone with lmodern after fontenc, shared preamble and scale variants" {
            bachman(withNote = true) shouldBe bachman(withNote = true)
            val standalone = bachman(options = LatexRenderOptions(standalone = true))
            standalone shouldStartWith "\\documentclass[border=10pt]{standalone}"
            (standalone.indexOf("\\usepackage{lmodern}") > standalone.indexOf("\\usepackage[T1]{fontenc}")) shouldBe true
            bachman(options = LatexRenderOptions(sharedPreamble = true)) shouldStartWith "\\begin{tikzpicture}"
            bachman(options = LatexRenderOptions(scale = 0.5)) shouldContain "\\begin{tikzpicture}[scale=0.500"
            LatexFamily.ERM.extraTikzLibraries shouldBe emptyList()
            KumlLatexRenderer.ermLatexSupports(ErmNotation.BACHMAN) shouldBe true
        }
    })
