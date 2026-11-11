package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.sysml2.ActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** Wave AJ (h) — a final node with two or more incoming flows is entered from the side when that is strictly better. */
class Sysml2AjFinalEntryTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        // Lane L1 (x 0..300) holds A and the final circle, lane L2 (x 300..600) holds B and optionally a foreign node C.
        val a = rect(x = 70f, y = 100f, w = 160f, h = 60f)
        val b = rect(x = 370f, y = 100f, w = 160f, h = 60f)
        val c = rect(x = 370f, y = 200f, w = 160f, h = 60f)
        val fin = rect(x = 138f, y = 300f, w = 24f, h = 24f)

        // Blocks the approach to the right tip of the final (4 px stub plus 2 px router clearance do not fit).
        val blocker = rect(x = 168f, y = 280f, w = 100f, h = 64f)

        fun route(
            withForeign: Boolean,
            withBlocker: Boolean = false,
        ): Pair<Map<EdgeId, EdgeRoute>, Map<String, Rect>> {
            val nodes = linkedMapOf("A" to a, "B" to b, "F" to fin)
            if (withForeign) nodes["C"] = c
            if (withBlocker) nodes["X"] = blocker
            val kinds =
                nodes.keys.associateWith { if (it == "F") ActivityNodeKind.Final else ActivityNodeKind.Action }
            val routes =
                ActOrthogonalRouter.routeAll(
                    nodes = nodes,
                    kinds = kinds,
                    edges = listOf(ActEdgeEnds(id = "af", source = "A", target = "F"), ActEdgeEnds(id = "bf", source = "B", target = "F")),
                    backEdges = emptySet(),
                    laneOfNode = mapOf("A" to "L1", "F" to "L1", "B" to "L2", "C" to "L2"),
                    corridorX = emptyMap(),
                )
            return ActCorridorSeparation.apply(routes) to nodes
        }

        fun points(r: EdgeRoute): List<Point> =
            when (r) {
                is EdgeRoute.Direct -> listOf(r.source, r.target)
                is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                else -> error("unexpected route $r")
            }

        fun bends(p: List<Point>) = maxOf(0, p.size - 2)

        fun crossesForeign(
            p: List<Point>,
            nodes: Map<String, Rect>,
            skip: Set<String>,
        ): Boolean {
            val inset =
                nodes.filterKeys { it !in skip }.values.map {
                    rect(x = it.origin.x + 2f, y = it.origin.y + 2f, w = it.size.width - 4f, h = it.size.height - 4f)
                }
            return IbdObstacleRouter.collisions(path = p, obstacles = inset) > 0
        }

        test("B enters the final at its right tip when nothing is in the way").config(timeout = 60.seconds) {
            val (routes, nodes) = route(withForeign = false)
            val pb = points(routes.getValue(EdgeId("bf")))
            val end = pb.last()
            (abs(end.x - 162f) <= 1f) shouldBe true
            (abs(end.y - 312f) <= 1f) shouldBe true
            (bends(pb) <= 3) shouldBe true
            crossesForeign(pb, nodes, skip = setOf("B", "F")) shouldBe false
            // The flow from A keeps the top entry.
            val pa = points(routes.getValue(EdgeId("af")))
            (abs(pa.last().y - 300f) <= 1f) shouldBe true
        }

        test("a foreign node between B and the final is bypassed: side entry, at most 3 bends, no crossing").config(
            timeout = 60.seconds,
        ) {
            val (routes, nodes) = route(withForeign = true)
            val pb = points(routes.getValue(EdgeId("bf")))
            val end = pb.last()
            (abs(end.x - 162f) <= 1f) shouldBe true
            (abs(end.y - 312f) <= 1f) shouldBe true
            (bends(pb) <= 3) shouldBe true
            crossesForeign(pb, nodes, skip = setOf("B", "F")) shouldBe false
            crossesForeign(points(routes.getValue(EdgeId("af"))), nodes, skip = setOf("A", "F")) shouldBe false
        }

        test("never worse: when the side approach is blocked the top entry is kept").config(timeout = 60.seconds) {
            val (routes, _) = route(withForeign = false, withBlocker = true)
            val pb = points(routes.getValue(EdgeId("bf")))
            (abs(pb.last().y - 300f) <= 1f) shouldBe true
            (abs(pb.last().x - 150f) <= 12f) shouldBe true
        }

        test("a final with a single incoming flow keeps the top entry").config(timeout = 60.seconds) {
            val routes =
                ActOrthogonalRouter.routeAll(
                    nodes = mapOf("B" to b, "F" to fin),
                    kinds = mapOf("B" to ActivityNodeKind.Action, "F" to ActivityNodeKind.Final),
                    edges = listOf(ActEdgeEnds(id = "bf", source = "B", target = "F")),
                    backEdges = emptySet(),
                    laneOfNode = emptyMap(),
                    corridorX = emptyMap(),
                )
            (abs(points(routes.getValue(EdgeId("bf"))).last().y - 300f) <= 1f) shouldBe true
        }
    })
