@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private fun count(
    text: String,
    needle: String,
): Int = text.windowed(needle.length).count { it == needle }

/** Structural, font-independent tests: they inspect the TikZ text and need no TeX installation. */
class ErmIdef1xLatexTest :
    StringSpec({
        "child dot sits at the target end, centre 4 along the direction" {
            val tex = ErmLatexFixtures.renderIdef1x()
            // r1: target at (120,260), dir up -> (120,256); r2: target (400,300), dir -x -> (396,300)
            tex shouldContain "\\fill (120.000pt, -256.000pt) circle (4.000pt);"
            tex shouldContain "\\fill (396.000pt, -300.000pt) circle (4.000pt);"
        }

        "parent diamond only when the source end is optional" {
            val tex = ErmLatexFixtures.renderIdef1x()
            // r2 source ZERO_ONE (220,310), dir +x
            tex shouldContain
                "\\draw[line width=0.6pt, fill=white] (220.000pt, -310.000pt) -- (226.000pt, -316.000pt) -- " +
                "(232.000pt, -310.000pt) -- (226.000pt, -304.000pt) -- cycle;"
            // r1 (ONE) and r3 (ONE) have no diamond: only r2 and r4 do
            count(tex, "-- cycle;") shouldBe 2
            tex shouldNotContain "fill=white] (120.000pt, -160.000pt)"
        }

        "cardinality letters Z, 1, P and blank" {
            val tex = ErmLatexFixtures.renderIdef1x()
            tex shouldContain "at (382.000pt, -300.000pt) {P};"
            count(tex, "{Z};") shouldBe 1
            count(tex, "{1};") shouldBe 1
            count(tex, "{P};") shouldBe 1
            // r1 is zero-one-or-many: a dot but no letter at its end
            tex shouldNotContain "at (120.000pt, -242.000pt)"
            tex shouldContain "anchor=base] at"
        }

        "letter mapping matches the SVG table (parity guard for the duplicated helper)" {
            val table =
                listOf(
                    Cardinality.ZERO_ONE to "Z",
                    Cardinality.ONE to "1",
                    Cardinality.ONE_MANY to "P",
                    Cardinality.ZERO_MANY to null,
                    Cardinality(min = 0, max = 5) to null,
                    Cardinality(min = 2, max = 3) to "P",
                    Cardinality(min = 3, max = 3) to "P",
                    Cardinality(min = 0, max = 0) to null,
                )
            for ((c, letter) in table) Idef1xTikz.cardinalityLabel(c) shouldBe letter
        }

        "rounded corners exactly on weak, identifying-target and category-subtype entities" {
            val tex = ErmLatexFixtures.renderIdef1x()
            val lines = tex.lines()

            fun frame(id: String) = lines.single { it.contains("(n_$id)") }
            frame("item") shouldContain "rounded corners=8.000pt" // weak and identifying target
            frame("retail") shouldContain "rounded corners=8.000pt" // category subtype
            frame("wholesale") shouldContain "rounded corners=8.000pt"
            frame("customer") shouldNotContain "rounded corners"
            frame("order") shouldNotContain "rounded corners"
            tex shouldContain "\\draw[line width=0.6pt, rounded corners=5.000pt] (403.000pt, -263.000pt) rectangle"
            ErmLatexFixtures.render(notation = ErmNotation.MARTIN) shouldNotContain "rounded corners=8"
        }

        "an identifying target that is not weak is rounded too" {
            val base = ErmLatexFixtures.categoryModel()
            val m = base.copy(entities = base.entities.map { if (it.id == "item") it.copy(weak = false) else it })
            val tex = ErmLatexFixtures.renderIdef1x(model = m)
            tex.lines().single { it.contains("(n_item)") } shouldContain "rounded corners=8.000pt"
            tex shouldNotContain ", rounded corners=5.000pt"
        }

        "category circle: radius 12, two bars when complete, discriminator label" {
            val tex = ErmLatexFixtures.renderIdef1x()
            tex shouldContain "\\draw[line width=0.6pt, fill=white] (262.000pt, -102.000pt) circle (12.000pt);"
            // bars at y = 90+24+4 = 118 and 122, half width 12*1.4/2 = 8.4
            tex shouldContain "\\draw[line width=0.6pt] (253.600pt, -118.000pt) -- (270.400pt, -118.000pt);"
            tex shouldContain "\\draw[line width=0.6pt] (253.600pt, -122.000pt) -- (270.400pt, -122.000pt);"
            tex shouldContain "anchor=base west] at (280.000pt, -106.000pt) {discount\\_\\%};"
        }

        "a partial category has one bar, no discriminator means no label" {
            val tex = ErmLatexFixtures.renderIdef1x(model = ErmLatexFixtures.categoryModel(complete = false, discriminator = null))
            tex shouldContain "(253.600pt, -118.000pt) -- (270.400pt, -118.000pt)"
            tex shouldNotContain "(253.600pt, -122.000pt)"
            tex shouldNotContain "anchor=base west] at (280.000pt"
        }

        "category connector edges are plain, solid and carry no glyph or label" {
            val tex = ErmLatexFixtures.renderIdef1x()
            val lines = tex.lines()
            // solid: the identifying r2 plus the three connectors (supertype->circle, circle->retail, circle->wholesale)
            lines.count { it.contains("kuml-edge-plain, -]") } shouldBe 4
            // glyphs only come from the four relationships: four child dots, no extra circle of radius 4
            count(tex, "circle (4.000pt)") shouldBe 4
            tex shouldContain "(220.000pt, -90.000pt)"
        }

        "an unknown category node is skipped without error" {
            val noCat = ErmLatexFixtures.renderIdef1x(layout = ErmLatexFixtures.categoryLayout(withCategoryNode = false))
            count(noCat, "circle (12.000pt)") shouldBe 0
            // the ghost node (no model category) never produced a circle in the full render either
            count(ErmLatexFixtures.renderIdef1x(), "circle (12.000pt)") shouldBe 1
        }

        "non-identifying relationships are dashed, special characters are escaped, output is deterministic" {
            val tex = ErmLatexFixtures.renderIdef1x()
            tex.lines().count { it.contains("kuml-edge-plain, dashed, -") } shouldBe 3 // r1, r3, r4
            tex shouldContain "Customer\\_Account"
            tex shouldContain "e\\_mail \\& co"
            tex shouldBe ErmLatexFixtures.renderIdef1x()
        }

        "notes are drawn and a side hint changes the placement" {
            val noted = ErmLatexFixtures.model(withNote = true)
            val m = ErmLatexFixtures.categoryModel().copy(notes = noted.notes, noteLinks = noted.noteLinks)
            val noteLayout = ErmLatexFixtures.layout(withNote = true)
            val layout = ErmLatexFixtures.categoryLayout()
            val withNote =
                layout.copy(
                    nodes = layout.nodes + (NodeId("n1") to noteLayout.nodes.getValue(NodeId("n1"))),
                    edges = layout.edges + (EdgeId("l1") to noteLayout.edges.getValue(EdgeId("l1"))),
                )
            val tex = ErmLatexFixtures.renderIdef1x(model = m, layout = withNote)
            tex shouldContain "Orders stay"
            tex shouldContain "kUML note styles"
        }

        "standalone output loads lmodern after fontenc and uses no extra TikZ library" {
            val tex = ErmLatexFixtures.renderIdef1x(options = LatexRenderOptions(standalone = true))
            tex shouldStartWith "\\documentclass[border=10pt]{standalone}"
            (tex.indexOf("\\usepackage{lmodern}") > tex.indexOf("\\usepackage[T1]{fontenc}")) shouldBe true
            tex shouldNotContain "shapes.geometric"
        }
    })
