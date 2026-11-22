@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.routeMidPoint
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SelfLoopRouter
import dev.kuml.layout.bridge.label.ErmEdgeLabelPlan
import dev.kuml.layout.bridge.label.ErmLabelKeys
import dev.kuml.layout.bridge.label.LabelPlacer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 130: relationship names and role labels of the ERM LaTeX output are planned, not placed at fixed offsets. */
class ErmLatexLabelPlanTest :
    StringSpec({
        val coord = Regex("""\((-?[0-9.]+)pt, (-?[0-9.]+)pt\)""")

        fun overlaps(
            a: Rect,
            b: Rect,
        ): Boolean =
            a.origin.x < b.origin.x + b.size.width &&
                b.origin.x < a.origin.x + a.size.width &&
                a.origin.y < b.origin.y + b.size.height &&
                b.origin.y < a.origin.y + a.size.height

        fun planOf(
            model: dev.kuml.erm.model.ErmModel,
            layout: LayoutResult,
        ): ErmLatexLabelPlan.Result {
            val rels = model.relationships.associateBy { it.id }
            return ErmLatexLabelPlan.compute(
                layout = layout,
                relationships = rels,
                selfLoopPlan = SelfLoopRouter.planAll(layout = layout, elementLookup = rels::get),
            )
        }

        /** The glyph box of every relationship end, as the planner treats it. */
        fun glyphRects(routes: Map<String, EdgeRoute>): List<Rect> =
            routes.values.flatMap {
                val tgt = ErmEdgeLabelPlan.targetTangent(it)
                listOf(
                    ErmEdgeLabelPlan.glyphRect(anchor = it.source, dir = ErmEdgeLabelPlan.sourceTangent(it)),
                    ErmEdgeLabelPlan.glyphRect(anchor = it.target, dir = -tgt.first to -tgt.second),
                )
            }

        "Martin: names and roles are drawn centred at the planned coordinates" {
            val model = ErmLatexFixtures.model()
            val layout = ErmLatexFixtures.layout()
            val plan = planOf(model, layout)
            val tex = ErmLatexFixtures.render()
            val name =
                plan.placed
                    .getValue("r1")
                    .getValue(ErmLabelKeys.CENTER)
                    .rect
            val line = tex.lines().single { it.contains("{places}") }
            line shouldContain "anchor=center"
            line shouldContain "fill=white, inner sep=1pt"
            val (x, y) = coord.find(line)!!.destructured
            x.toFloat() shouldBe (name.origin.x + name.size.width / 2f plusOrMinus 0.01f)
            (-y.toFloat()) shouldBe (name.origin.y + name.size.height / 2f plusOrMinus 0.01f)
            val role = tex.lines().single { it.contains("{buyer}") }
            role shouldContain "anchor=center"
            role shouldContain "fill=white, inner sep=0.5pt"
            role shouldContain "font=\\sffamily\\scriptsize"
        }

        "crowded: the old fixed offsets collide, the planned labels do not" {
            val model = ErmLatexFixtures.crowdedModel()
            val layout = ErmLatexFixtures.crowdedLayout()

            // Old placement: name centred over the middle of the line, 15 px high above it (anchor=south).
            fun oldName(
                text: String,
                route: EdgeRoute,
            ): Rect {
                val mid = routeMidPoint(route)!!
                val w = text.length * 6.6f + 8f
                return Rect(origin = Point(x = mid.first - w / 2f, y = mid.second - 15f), size = Size(width = w, height = 15f))
            }
            val old1 = oldName("places", layout.edges.getValue(dev.kuml.layout.EdgeId("r1")))
            val old2 = oldName("contains", layout.edges.getValue(dev.kuml.layout.EdgeId("r2")))
            overlaps(old1, old2) shouldBe true

            val plan = planOf(model, layout)
            val rects = plan.placed.flatMap { (edge, byKey) -> byKey.map { (key, label) -> "$edge#$key" to label.rect } }
            rects.size shouldBe 6
            for ((id, r) in rects) {
                for (node in layout.nodes.values) overlaps(r, node.bounds) shouldBe false
                for (g in glyphRects(plan.routes)) overlaps(r, g) shouldBe false
                for ((other, o) in rects) if (other != id) overlaps(r, o) shouldBe false
                // No label sits on a line of another relationship.
                val edge = id.substringBefore('#')
                for ((otherEdge, route) in plan.routes) {
                    if (otherEdge == edge) continue
                    (LabelPlacer.rectDistanceToPolyline(r = r, pts = listOf(route.source, route.target)) >= 0.5f) shouldBe true
                }
            }
        }

        "IDEF1X: the cardinality letter stays beside the dot, planned labels avoid its glyph box" {
            val model = ErmLatexFixtures.categoryModel()
            val layout = ErmLatexFixtures.categoryLayout()
            val tex = ErmLatexFixtures.renderIdef1x(model = model, layout = layout)
            // The letter line is produced by the unchanged Idef1xTikz helper.
            val r2 = layout.edges.getValue(dev.kuml.layout.EdgeId("r2"))
            val dirs = CrowFootTikz.endDirections(r2)
            val letter = Idef1xTikz.cardinalityLabel(model.relationships.single { it.id == "r2" }.targetCardinality)!!
            val expected = StringBuilder()
            Idef1xTikz.appendCardinalityLabel(r2.target, dirs.target, letter, LatexRenderOptions(), expected)
            tex shouldContain expected.toString().trim()
            // The letter's anchor (18 px along the line) lies inside the target-end glyph box.
            val point = Point(x = r2.target.x + dirs.target.first * 18f, y = r2.target.y + dirs.target.second * 18f)
            val glyph = ErmEdgeLabelPlan.glyphRect(anchor = r2.target, dir = dirs.target)
            (point.x >= glyph.origin.x && point.x <= glyph.origin.x + glyph.size.width) shouldBe true
            (point.y >= glyph.origin.y && point.y <= glyph.origin.y + glyph.size.height) shouldBe true
            val plan = planOf(model, layout)
            for ((_, byKey) in plan.placed) for ((_, label) in byKey) overlaps(label.rect, glyph) shouldBe false
        }

        "self-reference with a neighbour edge: planned labels avoid the self-loop labels, which keep their lines" {
            val model = ErmLatexFixtures.selfReferenceRightBusyModel()
            val layout = ErmLatexFixtures.selfReferenceRightBusyLayout()
            val plan = planOf(model, layout)
            plan.selfLoopRects.size shouldBe 3
            plan.placed.containsKey("r1") shouldBe false
            val rects = plan.placed.values.flatMap { it.values.map { l -> l.rect } }
            rects.isNotEmpty() shouldBe true
            for (r in rects) for (fixed in plan.selfLoopRects) overlaps(r, fixed) shouldBe false
            val tex = ErmLatexFixtures.renderSelfReferenceRightBusy(ErmNotation.MARTIN)
            // The loop's own labels keep their dedicated anchors (no planned centre anchor).
            tex.lines().single { it.contains("{manages}") } shouldNotContain "anchor=center"
            tex.lines().single { it.contains("{manager}") } shouldNotContain "anchor=center"
            tex.lines().single { it.contains("{report}") } shouldNotContain "anchor=center"
        }

        "fallback: a label without a plan entry keeps the old fixed positions" {
            val model = ErmLatexFixtures.model()
            val rel = model.relationships.single { it.id == "r1" }
            val route = ErmLatexFixtures.layout().edges.getValue(dev.kuml.layout.EdgeId("r1"))
            val out = StringBuilder()
            ErmLatexRenderer.renderRelationship(
                rel = rel,
                route = route,
                notation = ErmNotation.MARTIN,
                options = LatexRenderOptions(),
                out = out,
                planned = null,
            )
            val tex = out.toString()
            tex.lines().single { it.contains("{places}") } shouldContain "anchor=south"
            val role = tex.lines().single { it.contains("{buyer}") }
            role shouldNotContain "anchor=center"
            // Source role: 30 px along the line (downwards), 9 px aside; layout (120, 160) -> (111, 190) -> TikZ y flips.
            val (x, y) = coord.find(role)!!.destructured
            x.toFloat() shouldBe (111f plusOrMinus 0.01f)
            y.toFloat() shouldBe (-190f plusOrMinus 0.01f)
        }
    })
