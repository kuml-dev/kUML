package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.HeaderTextWidths
import dev.kuml.layout.Insets
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutGroup
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/** Nacharbeit 58 (e): edges into a composite child no longer run through the title or the entry/exit lines. */
class StmCompositeHeaderAvoidanceTest :
    FunSpec({
        fun pointsOf(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + r.target
                else -> emptyList()
            }

        fun hits(
            pts: List<Point>,
            rect: Rect,
        ): Boolean =
            pts.zipWithNext().any { (a, b) ->
                val x0 = minOf(a.x, b.x)
                val x1 = maxOf(a.x, b.x)
                val y0 = minOf(a.y, b.y)
                val y1 = maxOf(a.y, b.y)
                x1 > rect.origin.x &&
                    x0 < rect.origin.x + rect.size.width &&
                    y1 > rect.origin.y &&
                    y0 < rect.origin.y + rect.size.height
            }

        fun fixture(withWidths: Boolean) =
            sysml2Model(name = "Comp") {
                val i = stateDef(name = "Start", isInitial = true)
                val pre = stateDef(name = "Prepare")
                val act = stateDef(name = "Active", entryAction = "openValve()", exitAction = "closeValve()", invariant = "pressure < 10")
                val a = stateDef(name = "Warm", parent = act)
                val b = stateDef(name = "Hot", parent = act)
                transition(name = "i", source = i, target = pre)
                transition(name = "t1", source = pre, target = a, trigger = "go")
                transition(name = "t2", source = a, target = b, trigger = "heat")
                stmDiagram(name = "Comp") {
                    layout { direction = Down }
                    include(state = i)
                    include(state = pre)
                    include(state = act)
                    include(state = a)
                    include(state = b)
                }
            }.let { m ->
                val d = m.diagrams.filterIsInstance<StmDiagram>().single()
                val g = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = d)
                val graph =
                    if (withWidths) g else g.copy(groups = g.groups.map { it.copy(headerTextWidths = null) })
                val r =
                    LayoutSpecRunner
                        .layout(
                            engine = ElkLayoutEngine(),
                            graph = graph,
                            hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = d),
                            spec = d.layout,
                            kind = SpecDiagramKind.SYSML2_STM,
                        ).result
                graph to r
            }

        test("the edge into the composite child avoids the header texts and stays attached to the child's top edge") {
            val (g, before) = fixture(withWidths = false)
            val (_, after) = fixture(withWidths = true)
            val group = g.groups.first { it.id.value == "Active" }
            val w =
                fixture(withWidths = true)
                    .first.groups
                    .first { it.id.value == "Active" }
                    .headerTextWidths!!
            val frame = after.groups.getValue(GroupId("Active")).bounds
            val title =
                Rect(
                    origin = Point(x = frame.origin.x + frame.size.width / 2f - w.title / 2f, y = frame.origin.y),
                    size = Size(width = w.title, height = 28f),
                )
            val actions =
                Rect(
                    origin = Point(x = frame.origin.x + 4f, y = frame.origin.y + 30f),
                    size = Size(width = w.actions, height = group.padding.top - 30f),
                )
            val edge = g.edges.first { it.target.nodeId == NodeId("Warm") }.id
            // sanity: the unprocessed route does cross the header, the processed one does not
            hits(pointsOf(before.edges.getValue(edge)), title).shouldBeTrue()
            val pts = pointsOf(after.edges.getValue(edge))
            hits(pts, title).shouldBeFalse()
            hits(pts, actions).shouldBeFalse()
            // end point stays on the child's top edge (same y), the line is still attached
            after.edges
                .getValue(edge)
                .target.y shouldBe
                before.edges
                    .getValue(edge)
                    .target.y
            after.edges.getValue(edge).source shouldBe before.edges.getValue(edge).source
        }

        test("without a free x the route stays unchanged") {
            val src = NodeId("s")
            val child = NodeId("c")
            val graph =
                LayoutGraph(
                    nodes =
                        listOf(
                            LayoutNode(id = src, intrinsicSize = Size(width = 40f, height = 20f)),
                            LayoutNode(id = child, intrinsicSize = Size(width = 40f, height = 20f), groupId = GroupId("g")),
                        ),
                    edges = listOf(LayoutEdge(id = EdgeId("e"), source = EndpointRef(nodeId = src), target = EndpointRef(nodeId = child))),
                    groups =
                        listOf(
                            LayoutGroup(
                                id = GroupId("g"),
                                padding = Insets(top = 49f, right = 16f, bottom = 16f, left = 16f),
                                headerTextWidths = HeaderTextWidths(title = 40f, actions = 95f),
                            ),
                        ),
                )
            val route = EdgeRoute.Direct(source = Point(x = 50f, y = 40f), target = Point(x = 50f, y = 200f))
            val result =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 120f, height = 320f),
                    nodes =
                        mapOf(
                            src to NodeLayout(bounds = Rect(origin = Point(x = 30f, y = 20f), size = Size(width = 40f, height = 20f))),
                            child to NodeLayout(bounds = Rect(origin = Point(x = 30f, y = 200f), size = Size(width = 40f, height = 20f))),
                        ),
                    edges = mapOf(EdgeId("e") to route),
                    groups =
                        mapOf(
                            GroupId("g") to
                                GroupLayout(bounds = Rect(origin = Point(x = 0f, y = 100f), size = Size(width = 100f, height = 200f))),
                        ),
                )
            result.avoidCompositeHeaderBand(graph = graph, direction = LayoutDirection.TopToBottom).edges.getValue(EdgeId("e")) shouldBe
                route
        }
    })
