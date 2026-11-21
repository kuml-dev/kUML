@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 129: which side a self-loop is drawn on. Structural tests on geometry, independent of any font. */
class SelfLoopSideTest :
    FunSpec({
        // node: x 100..300, y 50..130; right band 299..332, left band 68..101
        val node = Rect(origin = Point(100f, 50f), size = Size(200f, 80f))
        val nodeLayout = NodeLayout(bounds = node)

        fun line(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ): EdgeRoute = EdgeRoute.Direct(source = Point(x1, y1), target = Point(x2, y2))

        val farAway = line(500f, 0f, 600f, 300f)
        val throughRight = line(400f, 90f, 320f, 90f) // enters the right band
        val throughLeft = line(0f, 90f, 90f, 90f) // enters the left band

        test("right band free chooses RIGHT") {
            SelfLoopRouter.chooseSide(node, listOf(farAway)) shouldBe SelfLoopSide.RIGHT
            SelfLoopRouter.chooseSide(node, emptyList()) shouldBe SelfLoopSide.RIGHT
        }

        test("RIGHT route is exactly the former route") {
            val expected =
                EdgeRoute.OrthogonalRounded(
                    source = Point(300f, 50f + 80f * 0.30f),
                    target = Point(300f, 50f + 80f * 0.70f),
                    waypoints = listOf(Point(332f, 50f + 80f * 0.30f), Point(332f, 50f + 80f * 0.70f)),
                    cornerRadiusPx = 6f,
                )
            SelfLoopRouter.selfLoopRoute(nodeLayout) shouldBe expected
            SelfLoopRouter.selfLoopRoute(nodeLayout, SelfLoopSide.RIGHT) shouldBe expected
        }

        test("LEFT route mirrors the loop to the left border") {
            val r = SelfLoopRouter.selfLoopRoute(nodeLayout, SelfLoopSide.LEFT)
            r.source shouldBe Point(100f, 50f + 80f * 0.30f)
            r.target shouldBe Point(100f, 50f + 80f * 0.70f)
            r.waypoints shouldBe listOf(Point(68f, 50f + 80f * 0.30f), Point(68f, 50f + 80f * 0.70f))
            r.cornerRadiusPx shouldBe 6f
        }

        test("a segment through the right band chooses LEFT") {
            SelfLoopRouter.chooseSide(node, listOf(throughRight)) shouldBe SelfLoopSide.LEFT
        }

        test("an edge ending exactly on the right border counts as occupied (1 px inset)") {
            SelfLoopRouter.chooseSide(node, listOf(line(400f, 90f, 300f, 90f))) shouldBe SelfLoopSide.LEFT
        }

        test("an edge passing above or below the node does not occupy the band") {
            SelfLoopRouter.chooseSide(node, listOf(line(310f, 0f, 310f, 49f), line(310f, 131f, 310f, 400f))) shouldBe SelfLoopSide.RIGHT
        }

        test("both sides busy stays RIGHT") {
            SelfLoopRouter.chooseSide(node, listOf(throughRight, throughLeft)) shouldBe SelfLoopSide.RIGHT
        }

        test("no room on the left (left - 32 < 0) stays RIGHT") {
            val near = Rect(origin = Point(31f, 50f), size = Size(200f, 80f))
            SelfLoopRouter.chooseSide(near, listOf(line(300f, 90f, 220f, 90f))) shouldBe SelfLoopSide.RIGHT
            val exact = Rect(origin = Point(32f, 50f), size = Size(200f, 80f))
            SelfLoopRouter.chooseSide(exact, listOf(line(300f, 90f, 220f, 90f))) shouldBe SelfLoopSide.LEFT
        }

        test("a spline is checked through its control points") {
            val bezier =
                EdgeRoute.Bezier(Point(400f, 0f), Point(400f, 300f), controlPoints = listOf(Point(320f, 90f), Point(320f, 91f)))
            SelfLoopRouter.chooseSide(node, listOf(bezier)) shouldBe SelfLoopSide.LEFT
        }

        test("the decision is deterministic and independent of route order") {
            val a = SelfLoopRouter.chooseSide(node, listOf(throughRight, farAway, throughLeft))
            val b = SelfLoopRouter.chooseSide(node, listOf(throughLeft, farAway, throughRight))
            val c = SelfLoopRouter.chooseSide(node, listOf(throughLeft, farAway, throughRight))
            a shouldBe b
            b shouldBe c
        }

        test("the chosen LEFT loop crosses no foreign route") {
            val side = SelfLoopRouter.chooseSide(node, listOf(throughRight))
            val loop = SelfLoopRouter.selfLoopRoute(nodeLayout, side)
            val xs = (listOf(loop.source) + loop.waypoints + loop.target).map { it.x }
            // loop stays within [68, 100]; the foreign route spans x in [320, 400]
            (xs.max() <= 100f) shouldBe true
            (xs.min() >= 68f) shouldBe true
        }

        fun rel(
            id: String,
            from: String,
            to: String,
        ) = ErmRelationship(
            id = id,
            name = null,
            sourceEntityId = from,
            targetEntityId = to,
            sourceCardinality = Cardinality.ONE,
            targetCardinality = Cardinality.ZERO_MANY,
        )

        test("planAll plans only self-loops and ignores self-loop routes of other edges as obstacles") {
            val loopRel = rel("loop", "A", "A")
            val loopRel2 = rel("loop2", "A", "A")
            val other = rel("other", "A", "B")
            val layout =
                LayoutResult(
                    engineId = dev.kuml.layout.LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(800f, 400f),
                    nodes = mapOf(NodeId("A") to nodeLayout, NodeId("B") to NodeLayout(Rect(Point(500f, 50f), Size(100f, 80f)))),
                    edges =
                        mapOf(
                            EdgeId("loop") to line(300f, 60f, 332f, 60f),
                            EdgeId("loop2") to line(300f, 100f, 332f, 100f),
                            EdgeId("other") to throughRight,
                        ),
                    groups = emptyMap(),
                )
            val lookup = mapOf("loop" to loopRel, "loop2" to loopRel2, "other" to other)
            val plan = SelfLoopRouter.planAll(layout, lookup::get)
            plan.keys shouldBe setOf("loop", "loop2")
            plan.getValue("loop").side shouldBe SelfLoopSide.LEFT
            // the first self-loop's own route (which lies in the right band) did not influence the second decision
            plan.getValue("loop2").side shouldBe SelfLoopSide.LEFT
            SelfLoopRouter.planAll(layout, lookup::get) shouldBe plan

            // adjust with and without a plan
            val el = loopRel
            val raw = layout.edges.getValue(EdgeId("loop"))
            val nodes: (String) -> NodeLayout? = { layout.nodes[NodeId(it)] }
            SelfLoopRouter.adjust(el, raw, nodeLookup = nodes) shouldBe SelfLoopRouter.selfLoopRoute(nodeLayout)
            SelfLoopRouter.adjust(el, raw, plan, "loop", nodes) shouldBe SelfLoopRouter.selfLoopRoute(nodeLayout, SelfLoopSide.LEFT)
            SelfLoopRouter.adjust(el, raw, plan, "unknown", nodes) shouldBe SelfLoopRouter.selfLoopRoute(nodeLayout)
            SelfLoopRouter.sideOf(plan, "loop") shouldBe SelfLoopSide.LEFT
            SelfLoopRouter.sideOf(null, "loop") shouldBe SelfLoopSide.RIGHT
            SelfLoopRouter.sideOf(plan, "nope") shouldBe SelfLoopSide.RIGHT
        }

        test("a layout without self-loops plans nothing") {
            val layout =
                LayoutResult(
                    engineId = dev.kuml.layout.LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(800f, 400f),
                    nodes = mapOf(NodeId("A") to nodeLayout),
                    edges = mapOf(EdgeId("e") to farAway),
                    groups = emptyMap(),
                )
            SelfLoopRouter.planAll(layout) { rel("e", "A", "B") } shouldBe emptyMap()
        }
    })
