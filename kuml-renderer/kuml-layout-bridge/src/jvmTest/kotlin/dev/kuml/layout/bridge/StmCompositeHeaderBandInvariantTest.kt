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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * PAL wave E1 (14b): no segment of an edge into a composite child passes through the header strip of the frame,
 * whatever the shape of the incoming route (vertical, diagonal, from the left or the right).
 */
class StmCompositeHeaderBandInvariantTest :
    FunSpec({
        timeout = 30_000L

        val frame = Rect(origin = Point(x = 200f, y = 100f), size = Size(width = 300f, height = 220f))
        val childBox = Rect(origin = Point(x = 300f, y = 220f), size = Size(width = 80f, height = 30f))

        fun graph() =
            LayoutGraph(
                nodes =
                    listOf(
                        LayoutNode(id = NodeId("s"), intrinsicSize = Size(width = 40f, height = 20f)),
                        LayoutNode(id = NodeId("c"), intrinsicSize = Size(width = 80f, height = 30f), groupId = GroupId("g")),
                    ),
                edges =
                    listOf(
                        LayoutEdge(
                            id = EdgeId("e"),
                            source = EndpointRef(nodeId = NodeId("s")),
                            target = EndpointRef(nodeId = NodeId("c")),
                        ),
                    ),
                groups =
                    listOf(
                        LayoutGroup(
                            id = GroupId("g"),
                            padding = Insets(top = 73f, right = 16f, bottom = 16f, left = 16f),
                            headerTextWidths = HeaderTextWidths(title = 60f, actions = 95f, sideEntryGap = 24f),
                        ),
                    ),
            )

        fun result(
            sourceX: Float,
            route: EdgeRoute,
        ) = LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = null,
            canvas = Size(width = 700f, height = 400f),
            nodes =
                mapOf(
                    NodeId("s") to NodeLayout(bounds = Rect(origin = Point(x = sourceX, y = 20f), size = Size(width = 40f, height = 20f))),
                    NodeId("c") to NodeLayout(bounds = childBox),
                ),
            edges = mapOf(EdgeId("e") to route),
            groups = mapOf(GroupId("g") to GroupLayout(bounds = frame)),
        )

        val routes =
            mapOf(
                "vertical through the title" to
                    EdgeRoute.Direct(source = Point(x = 350f, y = 40f), target = Point(x = 340f, y = 220f)),
                "vertical left of the title" to
                    EdgeRoute.Direct(source = Point(x = 230f, y = 40f), target = Point(x = 230f, y = 220f)),
                "diagonal from the left" to
                    EdgeRoute.Direct(source = Point(x = 100f, y = 40f), target = Point(x = 320f, y = 220f)),
                "diagonal from the right" to
                    EdgeRoute.Direct(source = Point(x = 600f, y = 40f), target = Point(x = 360f, y = 220f)),
                "orthogonal with a bend above the frame" to
                    EdgeRoute.OrthogonalRounded(
                        source = Point(x = 120f, y = 40f),
                        target = Point(x = 340f, y = 220f),
                        waypoints = listOf(Point(x = 120f, y = 80f), Point(x = 340f, y = 80f)),
                        cornerRadiusPx = 6f,
                    ),
            )

        for ((name, route) in routes) {
            test("no segment crosses the header strip: $name") {
                val g = graph()
                val header = headerRect(group = g.groups.single(), frame = frame)
                val before = result(sourceX = 100f, route = route)
                crossesHeader(pts = polylinePoints(route)!!, header = header).shouldBeTrue()
                val after = before.avoidCompositeHeaderBand(graph = g, direction = LayoutDirection.TopToBottom)
                val pts = polylinePoints(after.edges.getValue(EdgeId("e")))!!
                crossesHeader(pts = pts, header = header).shouldBeFalse()
                pts.last() shouldBe Point(x = route.let { polylinePoints(it)!!.last().x }, y = 220f)
                pts.first() shouldBe polylinePoints(route)!!.first()
            }
        }

        test("segmentIntersectsRect handles diagonal, touching and outside segments") {
            val r = Rect(origin = Point(x = 0f, y = 0f), size = Size(width = 100f, height = 20f))
            segmentIntersectsRect(a = Point(x = -10f, y = -10f), b = Point(x = 110f, y = 30f), r = r).shouldBeTrue()
            segmentIntersectsRect(a = Point(x = 50f, y = -10f), b = Point(x = 50f, y = 40f), r = r).shouldBeTrue()
            segmentIntersectsRect(a = Point(x = -10f, y = 20f), b = Point(x = 110f, y = 20f), r = r).shouldBeFalse()
            segmentIntersectsRect(a = Point(x = -10f, y = -5f), b = Point(x = 110f, y = -5f), r = r).shouldBeFalse()
        }
    })
