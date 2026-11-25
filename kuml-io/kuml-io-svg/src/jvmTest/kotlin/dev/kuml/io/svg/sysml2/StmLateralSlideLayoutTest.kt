@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.visibleStateIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * PAL Welle R: the lateral slide on a hand-made layout. `Bottom` sends a transition with a three-part label up to `Top` on a
 * straight line at x = 200; the two neighbouring transitions run 55.6 px to the left and to the right of it, which is narrower
 * than the narrowest label variant (70.8 px plus air) on both sides. Shifting the line by 24 px leaves 79.6 px on the other side.
 * Only CHAR_W/LINE_H geometry (no fonts), no ELK run.
 */
class StmLateralSlideLayoutTest :
    FunSpec({
        timeout = 120_000L

        val main = "transition:Bottom::Top"
        val left = "transition:Top::Bottom"
        val right = "transition:Bottom::Top#2"

        fun model(): Sysml2Model =
            sysml2Model(name = "Lateral") {
                val top = stateDef(name = "Top")
                val bottom = stateDef(name = "Bottom")
                transition(
                    name = "T55",
                    source = bottom,
                    target = top,
                    trigger = "ANNAHME: Korridor erneut geöffnet",
                    effect = "Timer verwerfen",
                    showName = true,
                )
                transition(name = "n1", source = top, target = bottom)
                stmDiagram(name = "STM") {
                    include(top)
                    include(bottom)
                }
            }

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        fun line(
            x: Float,
            from: Float,
            to: Float,
        ) = EdgeRoute.OrthogonalRounded(source = Point(x, from), target = Point(x, to), waypoints = emptyList(), cornerRadiusPx = 0f)

        fun layout(extraEdges: Map<EdgeId, EdgeRoute> = emptyMap()): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 400f, height = 440f),
                nodes =
                    mapOf(
                        NodeId("Top") to NodeLayout(bounds = rect(20f, 100f, 320f, 50f)),
                        NodeId("Bottom") to NodeLayout(bounds = rect(20f, 285f, 320f, 50f)),
                    ),
                edges =
                    linkedMapOf(
                        EdgeId(main) to line(200f, 285f, 150f),
                        EdgeId(left) to line(144.4f, 150f, 285f),
                    ) + extraEdges,
                groups = emptyMap(),
            )

        fun withRight() = layout(extraEdges = mapOf(EdgeId(right) to line(255.6f, 285f, 150f)))

        class Run(
            val model: Sysml2Model,
            val diagram: StmDiagram,
            val visible: Set<String>,
        ) {
            val padding = SvgRenderOptions.DEFAULT.paddingPx

            fun unresolved(layout: LayoutResult): List<String> {
                var plan: dev.kuml.layout.bridge.label.Sysml2LabelPlanResult? = null
                val routes = KumlSvgRenderer.sysml2ShiftedRoutes(layout, padding, KumlSvgRenderer.stmRouteTransform(model, visible))
                KumlSvgRenderer.planStmBlocks(
                    routes = routes,
                    layoutEdgeIds = layout.edges.keys.map { it.value },
                    obstacles = stmObstacleNodes(model = model, visibleIds = visible, layoutResult = layout, paddingPx = padding),
                    adapter = StmEdgeAdapter(model = model, diagram = diagram),
                    maxWidthPx = 130f,
                    onFinalPlan = { plan = it },
                )
                return plan!!.unresolved
            }

            fun slide(layout: LayoutResult): LayoutResult =
                KumlSvgRenderer.stmJogSlideLayout(
                    model = model,
                    visible = visible,
                    layout = layout,
                    adapter = StmEdgeAdapter(model = model, diagram = diagram),
                    padding = padding,
                    obstacles = { lr, p ->
                        stmObstacleNodes(model = model, visibleIds = visible, layoutResult = lr, paddingPx = p)
                    },
                    routeTransform = KumlSvgRenderer.stmRouteTransform(model = model, visible = visible),
                    maxWidthPx = 130f,
                )
        }

        fun run(): Run {
            val m = model()
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            return Run(m, d, d.visibleStateIds(m))
        }

        fun xOf(
            l: LayoutResult,
            id: String,
        ): Float =
            l.edges
                .getValue(EdgeId(id))
                .source.x

        test("the fixture has the transition ids this test uses") {
            val m = model()
            m.usages
                .filterIsInstance<dev.kuml.sysml2.TransitionUsage>()
                .map { it.id }
                .shouldContain(main)
        }

        test("happy path: a label without a free position gets its straight route moved sideways, ends stay on their nodes") {
            val r = run()
            val before = withRight()
            r.unresolved(before) shouldContain main
            val after = r.slide(before)
            r.unresolved(after).contains(main) shouldBe false
            val moved = after.edges.getValue(EdgeId(main))
            // The shifts -8, +8, -16 and +16 leave at most 71.6 px beside the line, which is not enough for the label with its air;
            // -24 leaves 79.6 px on the right and is the first position that gives the label a free spot.
            moved.source.x shouldBe 176f
            moved.target.x shouldBe 176f
            moved.source.y shouldBe 285f
            moved.target.y shouldBe 150f
            // Every other route, the node map and the edge order are unchanged.
            after.edges.keys.toList() shouldBe before.edges.keys.toList()
            after.edges.filterKeys { it.value != main } shouldBe before.edges.filterKeys { it.value != main }
            after.nodes shouldBe before.nodes
            after.canvas shouldBe before.canvas
            (xOf(after, main) - xOf(before, main)) shouldBe -24f
        }

        test("negative: a foreign parallel segment closer than 16 px to every useful position keeps the original layout") {
            val r = run()
            // A foreign vertical edge at x = 224 blocks +16 (8 px) and +24; the 8 px steps leave too little room.
            val blocked =
                layout(
                    extraEdges =
                        mapOf(
                            EdgeId(right) to line(255.6f, 285f, 150f),
                            EdgeId("transition:Top::Other") to line(224f, 170f, 265f),
                        ),
                )
            r.unresolved(blocked) shouldContain main
            r.slide(blocked) shouldBeSameInstanceAs blocked
        }

        test("a layout without an unresolved label is returned as the same instance") {
            val r = run()
            // Without the right neighbour the label fits on the right side.
            val free = layout()
            r.unresolved(free).contains(main) shouldBe false
            r.slide(free) shouldBeSameInstanceAs free
        }

        test("the slide is deterministic and idempotent") {
            val r = run()
            val l = withRight()
            val one = r.slide(l)
            r.slide(l) shouldBe one
            r.slide(one) shouldBeSameInstanceAs one
        }
    })
