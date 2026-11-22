@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.SelfLoopRouter
import dev.kuml.layout.bridge.SelfLoopSide
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

        val leftBounds = ErmLatexFixtures.employeeBoundsRightBusy
        val leftX = leftBounds.origin.x

        for (notation in notations) {
            "$notation: a busy right side puts the loop, roles and name on the left (Nacharbeit 129)" {
                val tex = ErmLatexFixtures.renderSelfReferenceRightBusy(notation)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                loop.size shouldBe 4
                val (source, wp1, wp2, target) = loop
                source.first shouldBe (leftX plusOrMinus 0.01f)
                target.first shouldBe (leftX plusOrMinus 0.01f)
                wp1.first shouldBe (leftX - 32f plusOrMinus 0.01f)
                wp2.first shouldBe (leftX - 32f plusOrMinus 0.01f)
                val sourceRoleLine = lineWith(tex, "{manager}")
                val targetRoleLine = lineWith(tex, "{report}")
                val nameLine = lineWith(tex, "{manages}")
                sourceRoleLine.contains("anchor=south east") shouldBe true
                targetRoleLine.contains("anchor=north east") shouldBe true
                nameLine.contains("anchor=east") shouldBe true
                nameLine shouldNotContain "fill=white"
                points(sourceRoleLine).single().first shouldBeLessThan leftX
                points(targetRoleLine).single().first shouldBeLessThan leftX
                points(nameLine).single().first shouldBeLessThan leftX - 32f
            }
        }

        // Nacharbeit 132: top and bottom loops
        val vb = ErmLatexFixtures.employeeBoundsVertical
        val vTop = vb.origin.y
        val vBottom = vTop + vb.size.height
        val legL = vb.origin.x + 0.30f * vb.size.width
        val legR = vb.origin.x + 0.70f * vb.size.width

        for (notation in notations) {
            "$notation: right and left busy puts the loop, roles and name above the entity (Nacharbeit 132)" {
                val tex = ErmLatexFixtures.renderSelfReferenceVertical(notation, blockTop = false)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                loop.size shouldBe 4
                val (source, wp1, wp2, target) = loop
                source shouldBe (legL to vTop)
                target shouldBe (legR to vTop)
                wp1.second shouldBe (vTop - 32f plusOrMinus 0.01f)
                wp2.second shouldBe (vTop - 32f plusOrMinus 0.01f)
                val sourceRoleLine = lineWith(tex, "{manager}")
                val targetRoleLine = lineWith(tex, "{report}")
                val nameLine = lineWith(tex, "{manages}")
                sourceRoleLine.contains("anchor=south east") shouldBe true
                targetRoleLine.contains("anchor=south west") shouldBe true
                nameLine.contains("anchor=south]") shouldBe true
                nameLine shouldNotContain "fill=white"
                // roles beside the legs and above the border; name centred above the outer line
                points(sourceRoleLine).single().first shouldBeLessThan legL
                points(targetRoleLine).single().first shouldBeGreaterThan legR
                points(sourceRoleLine).single().second shouldBeLessThan vTop
                points(targetRoleLine).single().second shouldBeLessThan vTop
                points(nameLine).single().first shouldBe ((legL + legR) / 2f plusOrMinus 0.01f)
                points(nameLine).single().second shouldBeLessThan vTop - 32f
                // the glyph anchors of the two ends are far enough apart (0.4 * width)
                (target.first - source.first) shouldBeGreaterThanOrEqual (0.4f * vb.size.width - 0.01f)
            }

            "$notation: right, left and top busy puts the loop, roles and name below the entity (Nacharbeit 132)" {
                val tex = ErmLatexFixtures.renderSelfReferenceVertical(notation, blockTop = true)
                val loop = points(lineWith(tex, "rounded corners=6.000pt"))
                val (source, wp1, wp2, target) = loop
                source shouldBe (legL to vBottom)
                target shouldBe (legR to vBottom)
                wp1.second shouldBe (vBottom + 32f plusOrMinus 0.01f)
                wp2.second shouldBe (vBottom + 32f plusOrMinus 0.01f)
                val sourceRoleLine = lineWith(tex, "{manager}")
                val targetRoleLine = lineWith(tex, "{report}")
                val nameLine = lineWith(tex, "{manages}")
                sourceRoleLine.contains("anchor=north east") shouldBe true
                targetRoleLine.contains("anchor=north west") shouldBe true
                nameLine.contains("anchor=north]") shouldBe true
                points(sourceRoleLine).single().second shouldBeGreaterThan vBottom
                points(targetRoleLine).single().second shouldBeGreaterThan vBottom
                points(nameLine).single().second shouldBeGreaterThan vBottom + 32f
            }

            "$notation: label rectangles of a top loop follow the TikZ anchors (plan equals renderer geometry)" {
                val model = ErmLatexFixtures.selfReferenceVerticalModel(blockTop = false)
                val rel = model.relationships.first { it.id == "r1" }
                val layout = ErmLatexFixtures.selfReferenceVerticalLayout(blockTop = false)
                val plan = SelfLoopRouter.planAll(layout = layout, elementLookup = model.relationships.associateBy { it.id }::get)
                plan.getValue("r1").side shouldBe SelfLoopSide.TOP
                val rects =
                    ErmLatexLabelPlan.selfLoopLabelRects(
                        rel = rel,
                        route = plan.getValue("r1").route,
                        node = layout.nodes.getValue(NodeId("employee")),
                        side = SelfLoopSide.TOP,
                    )
                rects.size shouldBe 3
                // name rect: centred on the loop, bottom edge 4 px above the outer line
                val nameRect = rects[0]
                (nameRect.origin.x + nameRect.size.width / 2f) shouldBe ((legL + legR) / 2f plusOrMinus 0.01f)
                (nameRect.origin.y + nameRect.size.height) shouldBe (vTop - 32f - 4f plusOrMinus 0.01f)
                // role rects: source role right edge left of the left leg, target role left edge right of the right leg
                (rects[1].origin.x + rects[1].size.width) shouldBeLessThan legL
                rects[2].origin.x shouldBeGreaterThan legR
                // a glyph box at each end is 16 px high around its anchor: the two ends do not overlap
                (legR - legL) shouldBeGreaterThan 16f
                // no two label rectangles overlap
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        val a = rects[i]
                        val b = rects[j]
                        val overlap =
                            a.origin.x < b.origin.x + b.size.width &&
                                b.origin.x < a.origin.x + a.size.width &&
                                a.origin.y < b.origin.y + b.size.height &&
                                b.origin.y < a.origin.y + a.size.height
                        overlap shouldBe false
                    }
                }
            }
        }

        "a free right side keeps the east-anchor-free right-hand loop" {
            val tex = ErmLatexFixtures.renderSelfReference(ErmNotation.MARTIN)
            tex shouldNotContain "anchor=east] at"
            tex shouldNotContain "anchor=south east] at"
        }

        "a diagram without a self-reference has no west-anchored name node" {
            for (notation in notations) {
                val tex = ErmLatexFixtures.render(notation = notation)
                tex shouldNotContain "anchor=west] at"
                tex shouldNotContain "anchor=south west] at"
            }
        }
    })
