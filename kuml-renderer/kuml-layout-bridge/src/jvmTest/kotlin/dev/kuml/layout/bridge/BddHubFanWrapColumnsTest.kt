package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nacharbeit 94: `wrapColumns` on a block definition diagram with a wide hub fan. Geometry only, independent of fonts.
 */
class BddHubFanWrapColumnsTest :
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
            direction: String = "Down",
            wrapColumns: Int? = null,
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
                            if (wrapColumns != null) this.wrapColumns = wrapColumns
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

        fun assertNoForeignCrossingAndInBounds(
            r: LayoutResult,
            count: Int,
            checkBounds: Boolean = true,
        ) {
            val boxes = r.nodes.values.map { it.bounds }
            val minX = boxes.minOf { it.origin.x }
            val maxX = boxes.maxOf { it.origin.x + it.size.width }
            val minY = boxes.minOf { it.origin.y }
            val maxY = boxes.maxOf { it.origin.y + it.size.height }
            r.edges.size shouldBe count
            for ((id, route) in r.edges) {
                val pts = points(route)
                val ends =
                    r.nodes
                        .filter { (_, n) ->
                            val bb = n.bounds
                            listOf(pts.first(), pts.last()).any { p ->
                                p.x >= bb.origin.x - 1f &&
                                    p.x <= bb.origin.x + bb.size.width + 1f &&
                                    p.y >= bb.origin.y - 1f &&
                                    p.y <= bb.origin.y + bb.size.height + 1f
                            }
                        }.keys
                        .map { it.value }
                        .toSet()
                for (p in if (checkBounds) points(route) else emptyList()) {
                    (p.x >= minX - 30f && p.x <= maxX + 30f && p.y >= minY - 30f && p.y <= maxY + 30f).shouldBeTrue()
                }
                for (a in points(route).zipWithNext { a, b -> Seg(id.value, a, b) }) {
                    crossesNode(r, a, ends).shouldBeFalse()
                }
            }
        }

        fun childRows(r: LayoutResult): Map<Float, Int> =
            r.nodes
                .filter { it.key.value != "Hub" }
                .values
                .groupingBy { it.bounds.origin.y }
                .eachCount()

        fun assertSpacing(r: LayoutResult) {
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

        test("T1: 8 children with wrapColumns=3 form rows of at most 3 and cross no foreign box") {
            val r = layout(count = 8, wrapColumns = 3)
            val rows = childRows(r)
            (rows.size >= 3).shouldBeTrue()
            rows.values.all { it <= 3 }.shouldBeTrue()
            assertNoForeignCrossingAndInBounds(r, count = 8)
        }

        for (cols in listOf(12, 8)) {
            test("T2: wrapColumns=$cols (not below the child count) leaves the unwrapped layout unchanged") {
                val wrapped = layout(count = 8, wrapColumns = cols)
                val plain = layout(count = 8)
                wrapped.edges shouldBe plain.edges
                wrapped.nodes.mapValues { it.value.bounds } shouldBe plain.nodes.mapValues { it.value.bounds }
                assertNoForeignCrossingAndInBounds(wrapped, count = 8)
            }
        }

        test("T3: wrapColumns=1 puts every child in its own row, no crossings, lanes 6 px apart") {
            val r = layout(count = 8, wrapColumns = 1)
            childRows(r).size shouldBe 8
            assertNoForeignCrossingAndInBounds(r, count = 8)
            assertSpacing(r)
        }

        test("T4: wrapColumns=3 is deterministic") {
            val a = layout(count = 8, wrapColumns = 3)
            val b = layout(count = 8, wrapColumns = 3)
            a.edges shouldBe b.edges
            a.nodes.mapValues { it.value.bounds } shouldBe b.nodes.mapValues { it.value.bounds }
        }

        for (count in listOf(4, 8)) {
            test("T5: without wrapColumns the $count-child fan is unchanged against an explicit null") {
                val a = layout(count = count)
                val b = layout(count = count, wrapColumns = null)
                a.edges shouldBe b.edges
                a.nodes.mapValues { it.value.bounds } shouldBe b.nodes.mapValues { it.value.bounds }
            }
        }

        for (direction in listOf("Down", "Up", "Right", "Left")) {
            test("T6: $direction with wrapColumns=3 crosses no foreign box and stays in bounds") {
                assertNoForeignCrossingAndInBounds(layout(count = 8, direction = direction, wrapColumns = 3), count = 8)
            }
        }

        test("T7: hub as target with wrapColumns=3 ends every edge on the hub, 6 px apart, crossing free") {
            val r = layout(count = 8, wrapColumns = 3, hubIsTarget = true)
            assertNoForeignCrossingAndInBounds(r, count = 8, checkBounds = false)
            val hub =
                r.nodes.entries
                    .single { it.key.value == "Hub" }
                    .value.bounds
            for (route in r.edges.values) {
                val end = route.target
                (end.y == hub.origin.y + hub.size.height || end.y == hub.origin.y).shouldBeTrue()
            }
            val ends =
                r.edges.values
                    .map { it.target.x }
                    .sorted()
            for ((a, b) in ends.zipWithNext()) (b - a >= 6f).shouldBeTrue()
        }

        test("T8: the spec matrix keeps wrapColumns effective for BDD and ignored for sequence diagrams") {
            val spec = LayoutSpec(wrapColumns = 3)
            LayoutSpecRunner.ignoredOptions(spec = spec, kind = SpecDiagramKind.SYSML2_BDD).shouldBeEmpty()
            LayoutSpecRunner.ignoredOptions(spec = spec, kind = SpecDiagramKind.UML_SEQUENCE) shouldContain LayoutOption.WRAP_COLUMNS
        }
    })
