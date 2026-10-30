@file:Suppress("RequireNamedArguments") // geometry literals and vararg test helpers

package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun layoutOf(
    nodes: Map<String, Rect>,
    edges: Map<String, EdgeRoute> = emptyMap(),
    canvas: Size = Size(width = 900f, height = 600f),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = canvas,
    nodes = nodes.entries.associate { (k, v) -> NodeId(k) to NodeLayout(bounds = v) },
    edges = edges.entries.associate { (k, v) -> EdgeId(k) to v },
    groups = emptyMap(),
)

private fun obstaclesOf(layout: LayoutResult): ObstacleIndex {
    val o = ObstacleIndex()
    layout.nodes.forEach { (id, n) -> o.addRect(kind = ObstacleKind.NODE, r = n.bounds, ownerId = id.value) }
    layout.edges.forEach { (id, e) -> o.addPolyline(kind = ObstacleKind.EDGE, pts = e.polylinePoints(), ownerId = id.value) }
    return o
}

private fun input(
    id: String,
    vararg anchors: String,
    body: String = "A note",
) = NotePlacementInput(noteId = id, body = body, anchors = anchors.map { NoteAnchorRef(linkId = "link:$id::$it", targetId = it) })

private fun Rect.touches(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

class NotePlacerTest :
    FunSpec({

        test("a single-anchor note is placed near its node, does not overlap any node and gets a route that ends on the node") {
            val layout =
                layoutOf(
                    mapOf(
                        "A" to rect(x = 100f, y = 100f, w = 120f, h = 60f),
                        "B" to rect(x = 400f, y = 100f, w = 120f, h = 60f),
                    ),
                )
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "A")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            listOf("A", "B").forEach {
                note.touches(
                    r.layout.nodes
                        .getValue(NodeId(it))
                        .bounds,
                ) shouldBe false
            }
            val route =
                r.layout.edges
                    .getValue(EdgeId("link:n::A"))
                    .polylinePoints()
            val end = route.last()
            val a =
                r.layout.nodes
                    .getValue(NodeId("A"))
                    .bounds
            (end.x in a.origin.x..(a.origin.x + a.size.width) && end.y in a.origin.y..(a.origin.y + a.size.height)) shouldBe true
            r.unresolved.shouldBeEmpty()
        }

        test("two anchors of one note give two routes with separated exit points and no collinear overlap") {
            val layout =
                layoutOf(
                    mapOf(
                        "A" to rect(x = 100f, y = 100f, w = 120f, h = 60f),
                        "B" to rect(x = 100f, y = 260f, w = 120f, h = 60f),
                    ),
                )
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "A", "B")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            val r1 =
                r.layout.edges
                    .getValue(EdgeId("link:n::A"))
                    .polylinePoints()
            val r2 =
                r.layout.edges
                    .getValue(EdgeId("link:n::B"))
                    .polylinePoints()
            val d = kotlin.math.hypot((r1.first().x - r2.first().x).toDouble(), (r1.first().y - r2.first().y).toDouble())
            d.toFloat() shouldBeGreaterThanOrEqualTo 12f
            for (i in 0 until r1.size - 1) {
                for (j in 0 until r2.size - 1) {
                    (collinearOverlap(a = r1[i], b = r1[i + 1], c = r2[j], d = r2[j + 1]) <= 8f) shouldBe true
                }
            }
        }

        test("an anchor route and the note stay out of an edge-label rectangle") {
            val edge = EdgeRoute.Direct(Point(x = 100f, y = 300f), Point(x = 500f, y = 300f))
            val layout =
                layoutOf(
                    nodes =
                        mapOf(
                            "A" to rect(x = 40f, y = 270f, w = 60f, h = 60f),
                            "B" to rect(x = 500f, y = 270f, w = 60f, h = 60f),
                        ),
                    edges = mapOf("e" to edge),
                )
            val label = rect(x = 260f, y = 285f, w = 80f, h = 14f)
            val o = obstaclesOf(layout)
            o.addRect(kind = ObstacleKind.EDGE_LABEL, r = label)
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "e", body = "x".repeat(160))),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = o,
                )
            val note =
                r.layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            note.touches(label.inflated(4f)) shouldBe false
            val pts =
                r.layout.edges
                    .getValue(EdgeId("link:n::e"))
                    .polylinePoints()
            for (i in 0 until pts.size - 1) segmentIntersectsRect(a = pts[i], b = pts[i + 1], r = label) shouldBe false
        }

        test("unresolvable anchors are reported and the note goes into a grid with at most four columns") {
            val layout = layoutOf(mapOf("A" to rect(x = 100f, y = 100f, w = 120f, h = 60f)))
            val notes = (1..9).map { input("free$it") } + input("ghost", "Nope")
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = notes,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            r.unresolved shouldBe listOf(UnresolvedAnchor(noteId = "ghost", anchorId = "Nope"))
            val xs =
                notes
                    .map {
                        r.layout.nodes
                            .getValue(NodeId(it.noteId))
                            .bounds.origin.x
                    }.distinct()
            xs.size shouldBeLessThanOrEqual 4
            notes.forEach { n ->
                val b =
                    r.layout.nodes
                        .getValue(NodeId(n.noteId))
                        .bounds
                (b.origin.x >= 0f && b.origin.y >= 0f) shouldBe true
                (b.origin.x + b.size.width <= r.layout.canvas.width + 0.01f) shouldBe true
            }
        }

        test("placement is deterministic") {
            val layout =
                layoutOf(
                    mapOf(
                        "A" to rect(x = 100f, y = 100f, w = 120f, h = 60f),
                        "B" to rect(x = 300f, y = 220f, w = 120f, h = 60f),
                    ),
                )
            val notes = listOf(input("n1", "A", "B"), input("n2", "B"), input("n3"))
            val a =
                NotePlacer.place(
                    layout = layout,
                    notes = notes,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            val b =
                NotePlacer.place(
                    layout = layout,
                    notes = notes,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            a shouldBe b
        }

        test("a larger font (taller notes) still yields overlap-free notes") {
            val tall =
                object : NoteMeasure {
                    override fun measure(
                        body: String,
                        maxWidth: Float,
                    ) = Size(width = 200f, height = 150f)
                }
            val layout =
                layoutOf(
                    mapOf(
                        "A" to rect(x = 100f, y = 100f, w = 120f, h = 60f),
                        "B" to rect(x = 260f, y = 100f, w = 120f, h = 60f),
                    ),
                )
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n1", "A"), input("n2", "B")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                    measure = tall,
                )
            val n1 =
                r.layout.nodes
                    .getValue(NodeId("n1"))
                    .bounds
            val n2 =
                r.layout.nodes
                    .getValue(NodeId("n2"))
                    .bounds
            n1.touches(n2) shouldBe false
            listOf("A", "B").forEach {
                val b =
                    r.layout.nodes
                        .getValue(NodeId(it))
                        .bounds
                n1.touches(b) shouldBe false
                n2.touches(b) shouldBe false
            }
        }

        test("limits: more than eight anchors and more than 200 notes are reported as truncated") {
            val nodes = (1..9).associate { "N$it" to rect(x = 20f + it * 100f, y = 100f, w = 60f, h = 40f) }
            val layout = layoutOf(nodes = nodes, canvas = Size(width = 1200f, height = 400f))
            val nine = input("many", *nodes.keys.toTypedArray())
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(nine),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            r.truncated shouldContain "many"
            r.layout.edges.keys
                .count { it.value.startsWith("link:many::") } shouldBe 8

            val lots = (1..205).map { input("f$it") }
            val r2 =
                NotePlacer.place(
                    layout = layout,
                    notes = lots,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstaclesOf(layout),
                )
            r2.truncated shouldHaveSize 5
            r2.layout.nodes.keys
                .count { it.value.startsWith("f") } shouldBe 200
        }

        test("a note left of the content shifts the whole layout instead of getting negative coordinates") {
            val layout = layoutOf(nodes = mapOf("A" to rect(x = 0f, y = 0f, w = 120f, h = 60f)), canvas = Size(width = 120f, height = 60f))
            val o = ObstacleIndex()
            o.addRect(kind = ObstacleKind.NODE, r = rect(x = 0f, y = 0f, w = 120f, h = 60f), ownerId = "A")
            // Block every side but the left one so the left margin candidate wins.
            o.addRect(kind = ObstacleKind.NODE, r = rect(x = 124f, y = -400f, w = 600f, h = 900f))
            o.addRect(kind = ObstacleKind.NODE, r = rect(x = -400f, y = -400f, w = 1200f, h = 370f))
            o.addRect(kind = ObstacleKind.NODE, r = rect(x = -400f, y = 64f, w = 1200f, h = 400f))
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(input("n", "A")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = o,
                )
            r.layout.nodes.values
                .forEach { (it.bounds.origin.x >= 0f && it.bounds.origin.y >= 0f) shouldBe true }
            r.layout.nodes
                .getValue(NodeId("A"))
                .bounds.origin.x shouldBeGreaterThanOrEqualTo 1f
        }

        test("a hostile number of obstacles stays within the work budget") {
            val layout =
                layoutOf(
                    nodes =
                        (1..40).associate {
                            "N$it" to rect(x = it * 30f, y = it * 20f, w = 20f, h = 20f)
                        },
                    canvas = Size(width = 1500f, height = 1000f),
                )
            val o = obstaclesOf(layout)
            repeat(2000) { o.addRect(kind = ObstacleKind.EDGE_LABEL, r = rect(x = it * 1f, y = it * 1f, w = 5f, h = 5f)) }
            val notes = (1..30).map { input("n$it", "N${it + 1}", "N${(it % 39) + 1}") }
            val cfg = NotePlacementConfig(workBudget = 200_000L)
            val r = NotePlacer.place(layout = layout, notes = notes, resolver = AnchorGeometryResolver(layout), obstacles = o, config = cfg)
            r.layout.nodes.keys
                .count { it.value.startsWith("n") } shouldBe 30
        }
    })
