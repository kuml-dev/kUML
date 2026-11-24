package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.sysml2.ActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/** PAL wave I (4): the inputs of an activity merge take slots in an order that avoids crossings, when one exists. */
class ActMergeInputOrderTest :
    FunSpec({
        timeout = 30_000L

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        fun points(r: EdgeRoute): List<Point> = polylinePoints(r) ?: error("unexpected route $r")

        fun route(
            nodes: Map<String, Rect>,
            flows: List<Pair<String, String>>,
        ): Map<EdgeId, EdgeRoute> =
            ActOrthogonalRouter.routeAll(
                nodes = nodes,
                kinds = nodes.keys.associateWith { if (it == "M") ActivityNodeKind.Merge else ActivityNodeKind.Action },
                edges = flows.mapIndexed { i, (s, t) -> ActEdgeEnds(id = "e$i", source = s, target = t) },
                backEdges = emptySet(),
                laneOfNode = emptyMap(),
                corridorX = emptyMap(),
            )

        // Reordering the slots removes the crossing completely in the first fixture; in the second it reduces three
        // crossings to one (the last one is caused by the obstacle detour itself and stays open).
        val crossingFixtures =
            mapOf(
                "one crossing" to 0 to
                    linkedMapOf(
                        "A" to rect(x = 248f, y = 0f, w = 100f, h = 40f),
                        "B" to rect(x = 28f, y = 120f, w = 100f, h = 40f),
                        "C" to rect(x = 233f, y = 120f, w = 100f, h = 40f),
                        "M" to rect(x = 184f, y = 260f, w = 40f, h = 40f),
                    ),
                "three crossings" to 1 to
                    linkedMapOf(
                        "A" to rect(x = 219f, y = 120f, w = 100f, h = 40f),
                        "B" to rect(x = 111f, y = 60f, w = 100f, h = 40f),
                        "C" to rect(x = 222f, y = 60f, w = 100f, h = 40f),
                        "M" to rect(x = 112f, y = 260f, w = 40f, h = 40f),
                    ),
            )
        for ((nameAndLimit, nodes) in crossingFixtures) {
            val (name, limit) = nameAndLimit
            test("merge with three inputs whose order crosses: $name") {
                val routes = route(nodes = nodes, flows = listOf("A" to "M", "B" to "M", "C" to "M"))
                countCrossings(routes.values.map { points(it) }) shouldBeLessThanOrEqual limit
            }
        }

        test("an already crossing-free merge keeps its routes") {
            val nodes =
                linkedMapOf(
                    "A" to rect(x = 0f, y = 0f, w = 100f, h = 40f),
                    "B" to rect(x = 150f, y = 0f, w = 100f, h = 40f),
                    "C" to rect(x = 300f, y = 0f, w = 100f, h = 40f),
                    "M" to rect(x = 190f, y = 200f, w = 40f, h = 40f),
                )
            val routes = route(nodes = nodes, flows = listOf("A" to "M", "B" to "M", "C" to "M"))
            countCrossings(routes.values.map { points(it) }) shouldBe 0
        }

        test("countCrossings counts proper crossings only") {
            val h = listOf(Point(x = 0f, y = 5f), Point(x = 10f, y = 5f))
            val v = listOf(Point(x = 5f, y = 0f), Point(x = 5f, y = 10f))
            val touching = listOf(Point(x = 10f, y = 5f), Point(x = 10f, y = 10f))
            countCrossings(listOf(h, v)) shouldBe 1
            countCrossings(listOf(h, touching)) shouldBe 0
            (countCrossings(listOf(h, v, v)) shouldBeGreaterThan 0)
        }
    })
