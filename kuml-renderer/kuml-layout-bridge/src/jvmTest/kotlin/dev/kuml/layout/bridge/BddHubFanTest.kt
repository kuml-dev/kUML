package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 86 (P-BDD-01/02): the edges of a hub block (4 or more plain edges) are drawn as an orthogonal fan with
 * own ends, own lanes and no detour around the diagram. Geometry only, independent of fonts.
 */
class BddHubFanTest :
    FunSpec({
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

        fun layout(
            count: Int,
            direction: String,
            hubIsTarget: Boolean = false,
        ): LayoutResult {
            val m =
                sysml2Model(name = "Hub") {
                    if (hubIsTarget) {
                        partDef(name = "Hub")
                        for (i in 1..count) partDef(name = "P$i") { refPart(name = "meldet an", typeId = "Hub") }
                    } else {
                        for (i in 1..count) partDef(name = "P$i")
                        partDef(name = "Hub") { for (i in 1..count) refPart(name = "p$i", typeId = "P$i") }
                    }
                    bdd(name = "BDD") {
                        layout {
                            when (direction) {
                                "Down" -> this.direction = Down
                                "Up" -> this.direction = Up
                                "Right" -> this.direction = Right
                                "Left" -> this.direction = Left
                            }
                        }
                        includeById(id = "Hub")
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

        fun crossesNode(
            result: LayoutResult,
            seg: Seg,
            endpoints: Set<String>,
        ): Boolean =
            result.nodes.any { (id, n) ->
                val b = n.bounds
                val x0 = b.origin.x + 1f
                val x1 = b.origin.x + b.size.width - 1f
                val y0 = b.origin.y + 1f
                val y1 = b.origin.y + b.size.height - 1f
                id.value !in endpoints &&
                    max(seg.a.x, seg.b.x) > x0 &&
                    min(seg.a.x, seg.b.x) < x1 &&
                    max(seg.a.y, seg.b.y) > y0 &&
                    min(seg.a.y, seg.b.y) < y1
            }

        for (direction in listOf("Down", "Up", "Right", "Left")) {
            test("$direction: 8 refPart edges of a hub keep inside the diagram and cross no foreign box") {
                val r = layout(count = 8, direction = direction)
                val boxes = r.nodes.values.map { it.bounds }
                val minX = boxes.minOf { it.origin.x }
                val maxX = boxes.maxOf { it.origin.x + it.size.width }
                val minY = boxes.minOf { it.origin.y }
                val maxY = boxes.maxOf { it.origin.y + it.size.height }
                r.edges.size shouldBe 8
                for ((id, route) in r.edges) {
                    val target = id.value.substringAfter("::p").let { "P$it" }
                    for (p in points(route)) {
                        (p.x >= minX - 30f && p.x <= maxX + 30f && p.y >= minY - 30f && p.y <= maxY + 30f).shouldBeTrue()
                    }
                    for (a in points(route).zipWithNext { a, b -> Seg(id.value, a, b) }) {
                        crossesNode(r, a, setOf("Hub", target)).shouldBeFalse()
                    }
                }
            }

            test("$direction: parallel segments of different hub edges keep at least 6 px apart") {
                val r = layout(count = 8, direction = direction)
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
                        if (overlap > 1f) (abs(sb.first - tb.first) >= 6f).shouldBeTrue()
                    }
                }
            }
        }

        test("Down: the edge to a block of a later row drops through the gap between two columns of the row in front") {
            val r = layout(count = 8, direction = "Down")
            val rows =
                r.nodes.values
                    .map { it.bounds.origin.y }
                    .distinct()
                    .sorted()
            (rows.size >= 3).shouldBeTrue()
            val laterRow =
                r.nodes
                    .filter { it.value.bounds.origin.y == rows.last() }
                    .keys
                    .map { it.value.removePrefix("P") }
            for (i in laterRow) {
                val route =
                    r.edges.entries
                        .single { it.key.value.endsWith("::p$i") }
                        .value
                (points(route).size >= 4).shouldBeTrue()
            }
        }

        test("Right: lanes of the fan are at least 14 px apart") {
            val r = layout(count = 8, direction = "Right")
            val xs =
                r.edges.values
                    .flatMap { points(it).zipWithNext { a, b -> if (a.x == b.x && a.y != b.y) a.x else null } }
                    .filterNotNull()
                    .distinct()
                    .sorted()
            for ((a, b) in xs.zipWithNext()) (b - a >= 14f).shouldBeTrue()
        }

        test("a hub that is the target of the edges is fanned as well, edges still run from their own source") {
            val r = layout(count = 6, direction = "Down", hubIsTarget = true)
            val hub =
                r.nodes.entries
                    .single { it.key.value == "Hub" }
                    .value.bounds
            for ((_, route) in r.edges) {
                val end = route.target
                val onHub = end.y == hub.origin.y + hub.size.height || end.y == hub.origin.y
                onHub.shouldBeTrue()
            }
            val ends =
                r.edges.values
                    .map { it.target.x }
                    .sorted()
            for ((a, b) in ends.zipWithNext()) (b - a >= 6f).shouldBeTrue()
        }

        test("fewer than 4 edges leave the layout untouched") {
            val r = layout(count = 3, direction = "Down")
            val again =
                BddHubFan.apply(
                    result = r,
                    graph = dev.kuml.layout.LayoutGraph(nodes = emptyList(), edges = emptyList()),
                    direction = LayoutDirection.TopToBottom,
                )
            again shouldBe r
        }

        test("the pass is deterministic") {
            val a = layout(count = 8, direction = "Down")
            val b = layout(count = 8, direction = "Down")
            a.edges shouldBe b.edges
        }
    })
