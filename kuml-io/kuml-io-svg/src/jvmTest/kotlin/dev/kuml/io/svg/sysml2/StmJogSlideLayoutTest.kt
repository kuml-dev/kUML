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
import dev.kuml.sysml2.StateDefinition
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
 * PAL Welle O: the jog slide on a hand-made layout that has the shape of the Doc 55 trouble spot. `Bottom` sends a transition
 * with a three-part label up to `Top`; its route has a 13.26 px jog 45 px below the target, and the two neighbouring edges
 * leave a corridor of 90.6 px on each side. Only CHAR_W/LINE_H geometry (no fonts), no ELK run.
 */
class StmJogSlideLayoutTest :
    FunSpec({
        timeout = 120_000L

        val main = "transition:Bottom::Top"
        val left = "transition:Top::Bottom"
        val right = "transition:Bottom::Other"

        fun model(): Sysml2Model =
            sysml2Model(name = "Jog") {
                val top = stateDef(name = "Top")
                val bottom = stateDef(name = "Bottom")
                val other = stateDef(name = "Other")
                transition(
                    name = "T55",
                    source = bottom,
                    target = top,
                    trigger = "ANNAHME: Korridor erneut geöffnet",
                    effect = "Timer verwerfen",
                    showName = true,
                )
                transition(name = "n1", source = top, target = bottom)
                transition(name = "n2", source = bottom, target = other)
                stmDiagram(name = "STM") {
                    include(top)
                    include(bottom)
                    include(other)
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

        /** The main route with its jog at [jogY]; source (160, 285) on Bottom, target (173.26, 150) on Top. */
        fun mainRoute(jogY: Float) =
            EdgeRoute.OrthogonalRounded(
                source = Point(160f, 285f),
                target = Point(173.26f, 150f),
                waypoints = listOf(Point(160f, jogY), Point(173.26f, jogY)),
                cornerRadiusPx = 0f,
            )

        fun layout(
            jogY: Float,
            extraEdges: Map<EdgeId, EdgeRoute> = emptyMap(),
        ): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 520f, height = 440f),
                nodes =
                    mapOf(
                        NodeId("Top") to NodeLayout(bounds = rect(20f, 100f, 320f, 50f)),
                        NodeId("Bottom") to NodeLayout(bounds = rect(20f, 285f, 320f, 50f)),
                        NodeId("Other") to NodeLayout(bounds = rect(380f, 285f, 100f, 50f)),
                    ),
                edges =
                    linkedMapOf(
                        EdgeId(main) to mainRoute(jogY),
                        EdgeId(left) to line(90f, 150f, 285f),
                        EdgeId(right) to line(250.6f, 285f, 150f),
                    ) + extraEdges,
                groups = emptyMap(),
            )

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

        fun jogOf(
            l: LayoutResult,
            id: String,
        ): Float =
            (l.edges.getValue(EdgeId(id)) as EdgeRoute.OrthogonalRounded)
                .waypoints
                .first()
                .y

        test("the fixture has the three states and transitions with the ids this test uses") {
            val m = model()
            m.definitions
                .filterIsInstance<StateDefinition>()
                .map { it.id }
                .shouldContain("Top")
            m.usages
                .filterIsInstance<dev.kuml.sysml2.TransitionUsage>()
                .map { it.id }
                .shouldContain(main)
        }

        test("(b) happy path: an unresolved label gets its jog at the target end, endpoints unchanged, other routes unchanged") {
            val r = run()
            val before = layout(jogY = 195f)
            // Without the slide the label is unresolved (the left corridor is 70 px, the lower segment 90 px: the 90 px high variant
            // does not fit beside it); the test below proves the slide; the next line asserts the precondition directly.
            r.unresolved(before) shouldContain main
            val after = r.slide(before)
            r.unresolved(after).contains(main) shouldBe false
            val b = before.edges.getValue(EdgeId(main)) as EdgeRoute.OrthogonalRounded
            val a = after.edges.getValue(EdgeId(main)) as EdgeRoute.OrthogonalRounded
            // The jog moved to 12 px before the target (y = 150 + 12 = 162) and nothing else of the route changed.
            jogOf(after, main) shouldBe 162f
            a.source shouldBe b.source
            a.target shouldBe b.target
            a.waypoints.map { it.x } shouldBe b.waypoints.map { it.x }
            // (d) every other route is identical, and so is the order of the edge map.
            after.edges.keys.toList() shouldBe before.edges.keys.toList()
            after.edges.filterKeys { it.value != main } shouldBe before.edges.filterKeys { it.value != main }
            after.nodes shouldBe before.nodes
            after.canvas shouldBe before.canvas
        }

        test("(c) a foreign parallel segment closer than 16 px to the target-end jog keeps the jog where it is") {
            val r = run()
            // A foreign horizontal edge 10 px below the target-end jog (y = 162): the 16 px rule rejects that position, and the
            // source-end position gives the label no free spot either, so the route stays as it is.

            fun near(y: Float) =
                mapOf(
                    EdgeId("transition:Top::Other") to
                        EdgeRoute.OrthogonalRounded(
                            source = Point(120f, y),
                            target = Point(210f, y),
                            waypoints = emptyList(),
                            cornerRadiusPx = 0f,
                        ),
                )
            val closer = layout(jogY = 195f, extraEdges = near(172f))
            // Precondition: with that edge the T55 label is unresolved before any slide (so the early return for "nothing to do" is not
            // what keeps the layout unchanged).
            r.unresolved(closer) shouldContain main
            // Welle R: the jog itself stays where it is (both jog positions are rejected), but the lateral slide may now free the label
            // by moving the target segment sideways, so the layout is no longer returned unchanged here.
            val after = r.slide(closer)
            jogOf(after, main) shouldBe 195f
            (
                after.edges
                    .getValue(EdgeId(main))
                    .target.x !=
                    closer.edges
                        .getValue(EdgeId(main))
                        .target.x
            ) shouldBe true
            after.edges.filterKeys { it.value != main } shouldBe closer.edges.filterKeys { it.value != main }
            r.unresolved(after).contains(main) shouldBe false
        }

        test("(d) a layout without an unresolved label is returned as the same instance") {
            // A single short straight transition has a free label position: nothing to slide.
            val m =
                sysml2Model(name = "Calm") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "go", source = a, target = b, trigger = "x")
                    stmDiagram(name = "STM") {
                        include(a)
                        include(b)
                    }
                }
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            val calm = Run(m, d, d.visibleStateIds(m))
            val l =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 300f, height = 300f),
                    nodes =
                        mapOf(
                            NodeId("A") to NodeLayout(bounds = rect(20f, 20f, 100f, 40f)),
                            NodeId("B") to NodeLayout(bounds = rect(20f, 200f, 100f, 40f)),
                        ),
                    edges = mapOf(EdgeId("transition:A::B") to line(70f, 60f, 200f)),
                    groups = emptyMap(),
                )
            calm.slide(l) shouldBeSameInstanceAs l
        }

        test("the slide is deterministic and idempotent") {
            val r = run()
            val l = layout(jogY = 195f)
            val one = r.slide(l)
            r.slide(l) shouldBe one
            // The slid layout has nothing left to slide.
            r.slide(one) shouldBeSameInstanceAs one
        }
    })
