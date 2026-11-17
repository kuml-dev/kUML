package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeHints
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 95: block definition diagrams with `direction = Up` (the natural direction for inheritance: the general
 * block lies below its specialisations). Probes the generalisation bus, the hub fan (hub as source and as target) and
 * the allocation docking. Geometry only, independent of fonts.
 */
class BddDirectionUpTest :
    FunSpec({
        val tol = 1.5f

        data class Seg(
            val edge: String,
            val a: Point,
            val b: Point,
        ) {
            val horizontal get() = a.y == b.y
        }

        fun points(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + r.target
                is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + r.target
                is EdgeRoute.Bezier -> listOf(r.source, r.target)
            }

        fun segments(result: LayoutResult): List<Seg> =
            result.edges.flatMap { (id, r) -> points(r).zipWithNext { a, b -> Seg(id.value, a, b) } }

        fun layoutOf(
            kind: String,
            count: Int,
            direction: String,
            wrapColumns: Int? = null,
        ): LayoutResult {
            val m =
                sysml2Model(name = "Up") {
                    when (kind) {
                        "hubSource" -> {
                            for (i in 1..count) partDef(name = "P$i")
                            partDef(name = "Hub") { for (i in 1..count) refPart(name = "p$i", typeId = "P$i") }
                        }
                        "hubTarget" -> {
                            partDef(name = "Hub")
                            for (i in 1..count) partDef(name = "P$i") { refPart(name = "meldet an", typeId = "Hub") }
                        }
                        else -> {
                            partDef(name = "Base")
                            for (i in 1..count) partDef(name = "P$i", specializesId = "Base")
                        }
                    }
                    bdd(name = "BDD") {
                        layout {
                            wrapColumns?.let { this.wrapColumns = it }
                            when (direction) {
                                "Down" -> this.direction = Down
                                "Up" -> this.direction = Up
                                "Right" -> this.direction = Right
                                "Left" -> this.direction = Left
                            }
                        }
                        includeById(id = if (kind == "bus") "Base" else "Hub")
                        for (i in 1..count) includeById(id = "P$i")
                    }
                }
            val diagram = m.diagrams.filterIsInstance<BdDiagram>().single()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = m, diagram = diagram)
            val hints = Sysml2LayoutBridge.layoutHintsFor(model = m, diagram = diagram)
            return LayoutSpecRunner
                .layout(engine = ElkLayoutEngine(), graph = graph, hints = hints, spec = diagram.layout, kind = SpecDiagramKind.SYSML2_BDD)
                .result
        }

        fun box(
            r: LayoutResult,
            id: String,
        ): Rect =
            r.nodes.entries
                .single { it.key.value == id }
                .value.bounds

        fun crossesNode(
            result: LayoutResult,
            seg: Seg,
            endpoints: Set<String>,
        ): Boolean =
            result.nodes.any { (id, n) ->
                val b = n.bounds
                id.value !in endpoints &&
                    max(seg.a.x, seg.b.x) > b.origin.x + 1f &&
                    min(seg.a.x, seg.b.x) < b.origin.x + b.size.width - 1f &&
                    max(seg.a.y, seg.b.y) > b.origin.y + 1f &&
                    min(seg.a.y, seg.b.y) < b.origin.y + b.size.height - 1f
            }

        fun top(b: Rect) = b.origin.y

        fun bottom(b: Rect) = b.origin.y + b.size.height

        fun near(
            a: Float,
            b: Float,
        ) = abs(a - b) <= tol

        fun onBox(
            p: Point,
            b: Rect,
        ) = p.x >= b.origin.x - tol && p.x <= b.origin.x + b.size.width + tol && p.y >= b.origin.y - tol && p.y <= bottom(b) + tol

        fun assertNoForeignCrossing(
            r: LayoutResult,
            hub: String,
        ) {
            for ((id, route) in r.edges) {
                val pts = points(route)
                (pts.size >= 2).shouldBeTrue()
                val ends = r.nodes.keys.filter { n -> onBox(pts.first(), box(r, n.value)) || onBox(pts.last(), box(r, n.value)) }
                for (s in pts.zipWithNext { a, b -> Seg(id.value, a, b) }) {
                    crossesNode(r, s, ends.map { it.value }.toSet() + hub).shouldBeFalse()
                }
            }
        }

        fun assertLanesApart(
            r: LayoutResult,
            sharedTrunk: Boolean = false,
        ) {
            val segs = segments(r).filter { it.a != it.b }
            for (s in segs) {
                for (t in segs) {
                    if (s.edge >= t.edge || s.horizontal != t.horizontal) continue
                    val (sa, sb, ta, tb) =
                        if (s.horizontal) {
                            listOf(s.a.x to s.b.x, s.a.y to s.b.y, t.a.x to t.b.x, t.a.y to t.b.y)
                        } else {
                            listOf(s.a.y to s.b.y, s.a.x to s.b.x, t.a.y to t.b.y, t.a.x to t.b.x)
                        }
                    val overlap =
                        min(max(sa.first, sa.second), max(ta.first, ta.second)) -
                            max(min(sa.first, sa.second), min(ta.first, ta.second))
                    // the generalisation bus deliberately shares one trunk line (distance 0), everything else keeps 6 px
                    val d = abs(sb.first - tb.first)
                    if (overlap > 1f) (d >= 6f || (sharedTrunk && d < 1f)).shouldBeTrue()
                }
            }
        }

        test("Up: hub as source, 8 edges, no foreign box crossed and everything inside the diagram") {
            val r = layoutOf(kind = "hubSource", count = 8, direction = "Up")
            r.edges.size shouldBe 8
            (r.edges.values.count { points(it).size >= 3 } >= 4).shouldBeTrue() // a fan was drawn, not only straight lines
            val boxes = r.nodes.values.map { it.bounds }
            val minX = boxes.minOf { it.origin.x }
            val maxX = boxes.maxOf { it.origin.x + it.size.width }
            val minY = boxes.minOf { it.origin.y }
            val maxY = boxes.maxOf { bottom(it) }
            for ((id, route) in r.edges) {
                val target = "P" + id.value.substringAfter("::p")
                for (p in points(route)) {
                    (p.x >= minX - 30f && p.x <= maxX + 30f && p.y >= minY - 30f && p.y <= maxY + 30f).shouldBeTrue()
                }
                for (s in points(route).zipWithNext { a, b -> Seg(id.value, a, b) }) {
                    crossesNode(r, s, setOf("Hub", target)).shouldBeFalse()
                }
            }
        }

        test("Up: hub as source, flow axis: hub below its blocks, edges start at the hub top and end at the block bottoms") {
            val r = layoutOf(kind = "hubSource", count = 8, direction = "Up")
            val hub = box(r, "Hub")
            for ((id, route) in r.edges) {
                near(route.source.y, top(hub)).shouldBeTrue()
                val b = box(r, "P" + id.value.substringAfter("::p"))
                (bottom(b) <= top(hub) + tol).shouldBeTrue()
                near(route.target.y, bottom(b)).shouldBeTrue()
            }
        }

        test("Up: hub as target, 6 edges end on the hub top or bottom, 6 px apart, and start on their own block") {
            val r = layoutOf(kind = "hubTarget", count = 6, direction = "Up")
            r.edges.size shouldBe 6
            val hub = box(r, "Hub")
            for ((_, route) in r.edges) {
                (near(route.target.y, top(hub)) || near(route.target.y, bottom(hub))).shouldBeTrue()
                r.nodes.keys
                    .filter { it.value != "Hub" }
                    .any { onBox(route.source, box(r, it.value)) }
                    .shouldBeTrue()
            }
            val ends =
                r.edges.values
                    .map { it.target.x }
                    .sorted()
            for ((a, b) in ends.zipWithNext()) (b - a >= 6f).shouldBeTrue()
        }

        test("Up: parallel segments of different edges keep at least 6 px apart (hub source, hub target, bus)") {
            for (kind in listOf("hubSource", "hubTarget", "bus")) {
                val r = layoutOf(kind = kind, count = 8, direction = "Up")
                r.edges.isNotEmpty().shouldBeTrue()
                assertLanesApart(r = r, sharedTrunk = kind == "bus")
            }
        }

        test("Up: generalisation bus, general block below, trunk end on its top, child ends on child bottoms") {
            val r = layoutOf(kind = "bus", count = 5, direction = "Up")
            r.edges.size shouldBe 5
            val base = box(r, "Base")
            val childTops = (1..5).map { top(box(r, "P$it")) }
            (childTops.all { bottom(box(r, "P1")) <= top(base) + tol }).shouldBeTrue()
            assertNoForeignCrossing(r, hub = "Base")
            val trunkEnds = r.edges.values.map { it.target }
            for (p in trunkEnds) near(p.y, top(base)).shouldBeTrue()
            for ((_, route) in r.edges) {
                val child = (1..5).map { box(r, "P$it") }.single { onBox(route.source, it) }
                near(route.source.y, bottom(child)).shouldBeTrue()
            }
            // shared trunk: the horizontal run near the supertype lies at least 6 px away from both box sides
            val horizontals = segments(r).filter { it.horizontal && it.a != it.b }
            horizontals.isNotEmpty().shouldBeTrue()
            for (s in horizontals) {
                val y = s.a.y
                (y <= top(base) - 6f || y >= bottom(base) + 6f || abs(y - top(base)) < tol).shouldBeTrue()
            }
        }

        test("Up: allocation edges keep the route of the layout engine, Right docks them") {
            fun node(
                id: String,
                x: Float,
                y: Float,
            ) = NodeId(id) to NodeLayout(bounds = Rect(origin = Point(x = x, y = y), size = Size(width = 100f, height = 60f)))

            fun graphOf() =
                LayoutGraph(
                    nodes =
                        listOf(
                            LayoutNode(id = NodeId("Sw"), intrinsicSize = Size(width = 100f, height = 60f)),
                            LayoutNode(id = NodeId("Eng"), intrinsicSize = Size(width = 100f, height = 60f)),
                        ),
                    edges =
                        listOf(
                            LayoutEdge(
                                id = EdgeId("alloc:1"),
                                source = EndpointRef(nodeId = NodeId("Sw")),
                                target = EndpointRef(nodeId = NodeId("Eng")),
                                hints = EdgeHints(targetAnchorOffset = 40f),
                            ),
                        ),
                )

            fun resultOf(
                sw: Pair<NodeId, NodeLayout>,
                eng: Pair<NodeId, NodeLayout>,
                route: EdgeRoute,
            ) = LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 400f, height = 400f),
                nodes = mapOf(sw, eng),
                edges = mapOf(EdgeId("alloc:1") to route),
                groups = emptyMap(),
            )

            val vertical =
                resultOf(
                    sw = node("Sw", 0f, 200f),
                    eng = node("Eng", 0f, 0f),
                    route = EdgeRoute.Direct(source = Point(x = 50f, y = 200f), target = Point(x = 50f, y = 60f)),
                )
            BddAllocationDocking.apply(result = vertical, graph = graphOf(), direction = LayoutDirection.BottomToTop) shouldBe vertical
            BddAllocationDocking.apply(result = vertical, graph = graphOf(), direction = LayoutDirection.TopToBottom) shouldBe vertical

            val horizontal =
                resultOf(
                    sw = node("Sw", 0f, 0f),
                    eng = node("Eng", 250f, 100f),
                    route = EdgeRoute.Direct(source = Point(x = 100f, y = 30f), target = Point(x = 250f, y = 130f)),
                )
            BddAllocationDocking.apply(
                result = horizontal,
                graph = graphOf(),
                direction = LayoutDirection.LeftToRight,
            ) shouldNotBe horizontal
        }

        test("Up: deterministic for hub fan and bus") {
            for (kind in listOf("hubSource", "hubTarget", "bus")) {
                layoutOf(kind = kind, count = 6, direction = "Up").edges shouldBe layoutOf(kind = kind, count = 6, direction = "Up").edges
            }
        }

        test("Up: wrapColumns = 3 with 8 blocks gives at least 3 rows, no foreign box crossed, ends on the bottoms") {
            val r = layoutOf(kind = "hubSource", count = 8, direction = "Up", wrapColumns = 3)
            r.edges.size shouldBe 8
            val rows =
                r.nodes
                    .filter { it.key.value != "Hub" }
                    .values
                    .map { it.bounds.origin.y }
                    .distinct()
            (rows.size >= 3).shouldBeTrue()
            for ((id, route) in r.edges) {
                val target = "P" + id.value.substringAfter("::p")
                near(route.target.y, bottom(box(r, target))).shouldBeTrue()
                for (s in points(route).zipWithNext { a, b -> Seg(id.value, a, b) }) {
                    crossesNode(r, s, setOf("Hub", target)).shouldBeFalse()
                }
            }
        }

        test("Down: bus keeps its structural geometry, general block above, ends on the child tops") {
            val r = layoutOf(kind = "bus", count = 5, direction = "Down")
            val base = box(r, "Base")
            for ((_, route) in r.edges) {
                near(route.target.y, bottom(base)).shouldBeTrue()
                val child = (1..5).map { box(r, "P$it") }.single { onBox(route.source, it) }
                near(route.source.y, top(child)).shouldBeTrue()
            }
        }
    })
