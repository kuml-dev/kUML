@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.PortId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun layoutOf(
    nodes: Map<String, Rect>,
    canvas: Size = Size(width = 900f, height = 600f),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = canvas,
    nodes = nodes.entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
    edges = emptyMap(),
    groups = emptyMap(),
)

/** A fixed-size measure, so the tests do not depend on any font. */
private object FixedMeasure : NoteMeasure {
    override fun measure(
        body: String,
        maxWidth: Float,
    ): Size = Size(width = 120f, height = 48f)
}

private fun input(
    id: String,
    vararg anchors: String,
) = NotePlacementInput(noteId = id, body = "note", anchors = anchors.map { NoteAnchorRef(linkId = "link:$id::$it", targetId = it) })

private fun Rect.l() = origin.x

private fun Rect.t() = origin.y

private fun Rect.r() = origin.x + size.width

private fun Rect.b() = origin.y + size.height

private fun Rect.overlaps(o: Rect) = l() < o.r() && o.l() < r() && t() < o.b() && o.t() < b()

/** Atkinson measure: no segment of [route] runs along (within 3 px, over more than 6 px) any rectangle edge or line. */
private fun assertNoRunAlong(
    route: List<Point>,
    rects: List<Rect>,
    lines: List<List<Point>>,
) {
    for (i in 0 until route.size - 1) {
        val last = i == route.size - 2
        for (r in rects) {
            for ((c, d) in rectEdges(r)) {
                runsAlong(a = route[i], b = route[i + 1], c = c, d = d, tailFree = last) shouldBe false
            }
        }
        for (l in lines) {
            for (j in 0 until l.size - 1) {
                runsAlong(a = route[i], b = route[i + 1], c = l[j], d = l[j + 1], tailFree = last) shouldBe false
            }
        }
    }
}

private fun assertTouchesOnlyAtEnd(
    route: List<Point>,
    r: Rect,
) {
    val inner = r.inflated(-0.5f)
    for (i in 0 until route.size - 1) segmentIntersectsRect(a = route[i], b = route[i + 1], r = inner) shouldBe false
}

class NoteAnchorRoutingTest :
    FunSpec({

        test("runsAlong: near and far, oblique, tail exemption and the exact limits") {
            val a = Point(0f, 0f)
            val b = Point(100f, 0f)
            // parallel and 2 px away over 100 px: runs along
            runsAlong(a, b, Point(0f, 2f), Point(100f, 2f)) shouldBe true
            // 4 px away: not along
            runsAlong(a, b, Point(0f, 4f), Point(100f, 4f)) shouldBe false
            // exactly 3 px away: along (limit is inclusive)
            runsAlong(a, b, Point(0f, 3f), Point(100f, 3f)) shouldBe true
            // perpendicular crossing is fine
            runsAlong(a, b, Point(50f, -20f), Point(50f, 20f)) shouldBe false
            // oblique, crossing at a shallow angle: only a few px are within the tolerance
            runsAlong(a, b, Point(0f, 40f), Point(100f, 0f)) shouldBe false
            // overlap of exactly 6 px is not enough, 7 px is
            runsAlong(a, b, Point(94f, 1f), Point(200f, 1f)) shouldBe false
            runsAlong(a, b, Point(93f, 1f), Point(200f, 1f)) shouldBe true
            // the last 4 px before the anchor end are exempt
            runsAlong(Point(0f, 0f), Point(100f, 0f), Point(94f, 1f), Point(200f, 1f), tailFree = true) shouldBe false
            runsAlong(Point(0f, 0f), Point(100f, 0f), Point(80f, 1f), Point(200f, 1f), tailFree = true) shouldBe true
        }

        test("port anchor: the line ends at the middle of the outer side of the square, never inside, never along a border") {
            val part = rect(x = 100f, y = 100f, w = 200f, h = 100f)
            val port = rect(x = 294f, y = 144f, w = 12f, h = 12f) // sticks out on the east side
            val layout = layoutOf(mapOf("P" to part))
            val extras = mapOf("P::out" to AnchorGeometry.Port(box = port, outward = Side.E))
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            obstacles.addRect(kind = ObstacleKind.PORT, r = port)
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "P::out")),
                    resolver = AnchorGeometryResolver(layout, extras),
                    obstacles = obstacles,
                    measure = FixedMeasure,
                )
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            val route =
                r.layout.edges
                    .getValue(EdgeId("link:n::P::out"))
                    .polylinePoints()
            route.last() shouldBe Point(x = port.r(), y = port.t() + 6f)
            note.overlaps(port.inflated(4f)) shouldBe false
            note.overlaps(part) shouldBe false
            assertTouchesOnlyAtEnd(route, port)
            assertNoRunAlong(route, listOf(part, port, note), emptyList())
            (route.size - 1).shouldBeLessThanOrEqual(3)
            // at least 16 px between the note and the square, in the direction the port sticks out
            (note.l() - port.r()) shouldBeGreaterThanOrEqualTo 16f
        }

        test("port anchor on the west and the south side of a part, with the part to the east") {
            val part = rect(x = 300f, y = 100f, w = 200f, h = 100f)
            val west = rect(x = 294f, y = 144f, w = 12f, h = 12f)
            val south = rect(x = 394f, y = 194f, w = 12f, h = 12f)
            val layout = layoutOf(mapOf("P" to part))
            val extras =
                mapOf(
                    "P::w" to AnchorGeometry.Port(box = west, outward = Side.W),
                    "P::s" to AnchorGeometry.Port(box = south, outward = Side.S),
                )
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            listOf(west, south).forEach { obstacles.addRect(kind = ObstacleKind.PORT, r = it) }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n1", "P::w"), input("n2", "P::s")),
                    resolver = AnchorGeometryResolver(layout, extras),
                    obstacles = obstacles,
                    measure = FixedMeasure,
                )
            val rW =
                r.layout.edges
                    .getValue(EdgeId("link:n1::P::w"))
                    .polylinePoints()
            val rS =
                r.layout.edges
                    .getValue(EdgeId("link:n2::P::s"))
                    .polylinePoints()
            rW.last() shouldBe Point(x = west.l(), y = west.t() + 6f)
            rS.last() shouldBe Point(x = south.l() + 6f, y = south.b())
            for ((route, port) in listOf(rW to west, rS to south)) {
                assertTouchesOnlyAtEnd(route, port)
                assertNoRunAlong(route, listOf(part, port), emptyList())
            }
            listOf("n1", "n2").forEach { id ->
                val n =
                    r.layout.nodes
                        .getValue(NodeId(id))
                        .bounds
                n.overlaps(part) shouldBe false
                n.overlaps(west.inflated(4f)) shouldBe false
                n.overlaps(south.inflated(4f)) shouldBe false
            }
        }

        test("stem anchor: one short horizontal line, beside the lifeline, clear of heads and messages") {
            val heads = mapOf("A" to rect(120f, 20f, 100f, 40f), "B" to rect(320f, 20f, 100f, 40f), "C" to rect(520f, 20f, 100f, 40f))
            val layout = layoutOf(heads, canvas = Size(700f, 500f))
            val stemX = 370f
            val extras = mapOf("B" to AnchorGeometry.Stem(x = stemX, top = 60f, bottom = 500f))
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            val lifelines = listOf(170f, 370f, 570f)
            lifelines.forEach { x ->
                obstacles.addPolyline(ObstacleKind.LIFELINE, listOf(Point(x, 60f), Point(x, 500f)), ownerId = if (x == stemX) "B" else null)
            }
            val messages = listOf(Point(170f, 100f) to Point(370f, 100f), Point(370f, 140f) to Point(570f, 140f))
            messages.forEach { (a, b) -> obstacles.addPolyline(ObstacleKind.EDGE, listOf(a, b)) }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "B")),
                    resolver = AnchorGeometryResolver(layout, extras),
                    obstacles = obstacles,
                    measure = FixedMeasure,
                )
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            val route =
                r.layout.edges
                    .getValue(EdgeId("link:n::B"))
                    .polylinePoints()
            route shouldHaveSize 2
            route.first().y shouldBe route.last().y
            route.last().x shouldBe stemX
            abs(route.first().x - route.last().x).shouldBeLessThanOrEqual(40f)
            note.overlaps(heads.getValue("A")) shouldBe false
            note.overlaps(heads.getValue("B")) shouldBe false
            note.overlaps(heads.getValue("C")) shouldBe false
            lifelines.forEach { x -> (x > note.l() && x < note.r() && note.b() > 60f) shouldBe false }
            messages.forEach { (a, b) -> segmentIntersectsRect(a, b, note.inflated(4f)) shouldBe false }
            assertNoRunAlong(route, listOf(note), lifelines.map { listOf(Point(it, 60f), Point(it, 500f)) })
        }

        test("stem anchor squeezed between close lifelines still never lies on top of a line") {
            val heads = mapOf("A" to rect(20f, 20f, 60f, 40f), "B" to rect(100f, 20f, 60f, 40f), "C" to rect(180f, 20f, 60f, 40f))
            val layout = layoutOf(heads, canvas = Size(300f, 400f))
            val extras = mapOf("B" to AnchorGeometry.Stem(x = 130f, top = 60f, bottom = 400f))
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            val stems = listOf(50f, 130f, 210f).map { listOf(Point(it, 60f), Point(it, 400f)) }
            stems.forEach { obstacles.addPolyline(ObstacleKind.LIFELINE, it, ownerId = null) }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "B")),
                    resolver = AnchorGeometryResolver(layout, extras),
                    obstacles = obstacles,
                    measure = FixedMeasure,
                )
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            val route =
                r.layout.edges
                    .getValue(EdgeId("link:n::B"))
                    .polylinePoints()
            (route.size - 1).shouldBeLessThanOrEqual(4)
            assertNoRunAlong(route, listOf(note), stems)
        }

        test("two anchors of one note get two different routes whose exit points are at least 16 px apart") {
            val layout = layoutOf(mapOf("A" to rect(100f, 100f, 100f, 60f), "B" to rect(100f, 300f, 100f, 60f)))
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "A", "B")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    measure = FixedMeasure,
                )
            val r1 =
                r.layout.edges
                    .getValue(EdgeId("link:n::A"))
                    .polylinePoints()
            val r2 =
                r.layout.edges
                    .getValue(EdgeId("link:n::B"))
                    .polylinePoints()
            (r1 == r2) shouldBe false
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            assertNoRunAlong(r1, listOf(note), listOf(r2))
            if (r1.first().x == r2.first().x) abs(r1.first().y - r2.first().y) shouldBeGreaterThanOrEqualTo 16f
        }

        test("two anchors on the same node do not lie on top of each other") {
            val layout = layoutOf(mapOf("A" to rect(300f, 200f, 120f, 80f)))
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "A", "A")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    measure = FixedMeasure,
                )
            val routes =
                r.layout.edges.keys
                    .map {
                        r.layout.edges
                            .getValue(it)
                            .polylinePoints()
                    }
            // identical link ids collapse into one edge in the layout map: both refs use link:n::A
            routes.size shouldBe 1
        }

        test("placement is deterministic") {
            val layout = layoutOf(mapOf("A" to rect(100f, 100f, 100f, 60f), "B" to rect(400f, 300f, 100f, 60f)))
            val a =
                NotePlacer.place(
                    layout,
                    listOf(input("n", "A", "B")),
                    AnchorGeometryResolver(layout),
                    Sysml2NotePlacement.obstaclesOf(layout),
                    FixedMeasure,
                )
            val b =
                NotePlacer.place(
                    layout,
                    listOf(input("n", "A", "B")),
                    AnchorGeometryResolver(layout),
                    Sysml2NotePlacement.obstaclesOf(layout),
                    FixedMeasure,
                )
            a.layout shouldBe b.layout
        }

        test("an exhausted work budget still yields routes that do not run along their note or target") {
            val layout = layoutOf((0 until 30).associate { "N$it" to rect(20f + (it % 6) * 140f, 20f + (it / 6) * 100f, 100f, 60f) })
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "N3"), input("m", "N17")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    measure = FixedMeasure,
                    config = NotePlacementConfig(workBudget = 50L),
                )
            for ((note, target) in listOf("n" to "N3", "m" to "N17")) {
                val route =
                    r.layout.edges
                        .getValue(EdgeId("link:$note::$target"))
                        .polylinePoints()
                val n =
                    r.layout.nodes
                        .getValue(NodeId(note))
                        .bounds
                assertNoRunAlong(
                    route,
                    listOf(
                        n,
                        r.layout.nodes
                            .getValue(NodeId(target))
                            .bounds,
                    ),
                    emptyList(),
                )
            }
        }

        test("many anchors and many notes terminate within the caps") {
            val nodes = (0 until 20).associate { "N$it" to rect(20f + (it % 5) * 150f, 20f + (it / 5) * 120f, 110f, 70f) }
            val layout = layoutOf(nodes)
            val notes =
                (0 until 40).map { i -> input("n$i", *(0 until 12).map { "N${(i + it) % 20}" }.toTypedArray()) }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = notes,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    measure = FixedMeasure,
                )
            r.truncated.size shouldBeGreaterThanOrEqualTo 40
            r.layout.nodes.keys
                .count { it.value.startsWith("n") } shouldBe 40
        }

        test("translating a layout does not move IBD-local port positions") {
            val node = NodeLayout(bounds = rect(100f, 100f, 200f, 100f), ports = mapOf(PortId("p") to Point(97f, 50f)))
            val layout = layoutOf(emptyMap()).copy(nodes = mapOf(NodeId("A") to node))
            val moved = layout.translated(dx = 164f, dy = 0f, shiftPorts = false)
            moved.nodes
                .getValue(NodeId("A"))
                .bounds.origin.x shouldBe 264f
            moved.nodes
                .getValue(NodeId("A"))
                .ports
                .getValue(PortId("p")) shouldBe Point(97f, 50f)
            val absolute = layout.translated(dx = 164f, dy = 0f)
            absolute.nodes
                .getValue(NodeId("A"))
                .ports
                .getValue(PortId("p")) shouldBe Point(261f, 50f)
        }
    })
