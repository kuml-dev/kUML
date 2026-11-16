@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.core.model.note.NotePlacement
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun r(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun layoutOf(
    nodes: Map<String, Rect>,
    edges: Map<String, EdgeRoute> = emptyMap(),
) = LayoutResult(
    engineId = LayoutEngineId("test"),
    seed = null,
    canvas = Size(width = 900f, height = 700f),
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

private fun note(
    placement: NotePlacement?,
    vararg anchors: String,
) = NotePlacementInput(
    noteId = "n",
    body = "A note",
    anchors = anchors.map { NoteAnchorRef(linkId = "link:n::$it", targetId = it) },
    placement = placement,
)

private fun place(
    layout: LayoutResult,
    placement: NotePlacement?,
    vararg anchors: String,
    config: NotePlacementConfig = NotePlacementConfig(),
    prepare: (ObstacleIndex) -> Unit = {},
): NotePlacementResult {
    val obstacles = obstaclesOf(layout)
    prepare(obstacles)
    return NotePlacer.place(
        layout = layout,
        notes = listOf(note(placement, *anchors)),
        resolver = AnchorGeometryResolver(layout),
        obstacles = obstacles,
        config = config,
    )
}

private fun NotePlacementResult.noteRect(): Rect =
    layout.nodes
        .getValue(NodeId("n"))
        .bounds

class NotePlacementHintTest :
    FunSpec({
        val a = r(x = 400f, y = 300f, w = 120f, h = 60f)
        val free = layoutOf(mapOf("A" to a))

        test("a free anchor: every side hint puts the note on that side, centred, in the first ring") {
            val gap = NotePlacementConfig().candidateGap
            for (side in listOf(NotePlacement.ABOVE, NotePlacement.BELOW, NotePlacement.LEFT, NotePlacement.RIGHT)) {
                val res = place(free, side, "A")
                val n = res.noteRect()
                res.hintNotHonoured.shouldBeEmpty()
                when (side) {
                    NotePlacement.ABOVE -> {
                        (n.bottom <= a.top + 0.5f) shouldBe true
                        kotlin.math.abs(n.cx - a.cx) shouldBe 0f
                        kotlin.math.abs((a.top - n.bottom) - gap) shouldBe 0f
                    }
                    NotePlacement.BELOW -> {
                        (n.top >= a.bottom - 0.5f) shouldBe true
                        kotlin.math.abs(n.cx - a.cx) shouldBe 0f
                    }
                    NotePlacement.LEFT -> {
                        (n.right <= a.left + 0.5f) shouldBe true
                        kotlin.math.abs(n.cy - a.cy) shouldBe 0f
                    }
                    else -> {
                        (n.left >= a.right - 0.5f) shouldBe true
                        kotlin.math.abs(n.cy - a.cy) shouldBe 0f
                    }
                }
            }
        }

        test("NEAR and null leave the placement byte-for-byte untouched and never warn") {
            val plain = place(free, null, "A")
            val near = place(free, NotePlacement.NEAR, "A")
            near.noteRect() shouldBe plain.noteRect()
            near.layout.edges shouldBe plain.layout.edges
            near.hintNotHonoured.shouldBeEmpty()
        }

        test("several anchors: the half plane is taken against the union of all anchors") {
            val b = r(x = 400f, y = 500f, w = 120f, h = 60f)
            val layout = layoutOf(mapOf("A" to a, "B" to b))
            val above = place(layout, NotePlacement.ABOVE, "A", "B").noteRect()
            (above.bottom <= a.top + 0.5f) shouldBe true
            val below = place(layout, NotePlacement.BELOW, "A", "B").noteRect()
            (below.top >= b.bottom - 0.5f) shouldBe true
        }

        test("a blocked wished side falls back to the default result and reports exactly one miss") {
            val roof = r(x = -400f, y = -400f, w = 1800f, h = 676f)
            val layout = layoutOf(mapOf("A" to a, "Roof" to roof))
            val plain = place(layout, null, "A")
            val hinted = place(layout, NotePlacement.ABOVE, "A")
            hinted.noteRect() shouldBe plain.noteRect()
            hinted.hintNotHonoured shouldHaveSize 1
            val miss = hinted.hintNotHonoured.single()
            miss.noteId shouldBe "n"
            miss.wanted shouldBe NotePlacement.ABOVE
            miss.message().startsWith("placement hint not honoured: n, wanted ABOVE, placed ") shouldBe true
        }

        test("hard rules stay hard: a keep-out band above the anchor is never covered, an edge is never crossed") {
            val band = r(x = 300f, y = 200f, w = 320f, h = 90f)
            val withBand =
                place(free, NotePlacement.ABOVE, "A") { it.addRect(kind = ObstacleKind.KEEP_OUT, r = band, ownerId = "g") }
            withBand.noteRect().intersects(band) shouldBe false

            val edge = EdgeRoute.Direct(source = Point(x = 100f, y = 250f), target = Point(x = 800f, y = 250f))
            val layout = layoutOf(mapOf("A" to a), mapOf("E" to edge))
            val res = place(layout, NotePlacement.ABOVE, "A")
            val n = res.noteRect()
            val crosses = n.top <= 250f && n.bottom >= 250f
            crosses shouldBe false
        }

        test("with an edge clearance the hint obeys the clearance rule") {
            val edge = EdgeRoute.Direct(source = Point(x = 100f, y = 240f), target = Point(x = 800f, y = 240f))
            val layout = layoutOf(mapOf("A" to a), mapOf("E" to edge))
            val config = NotePlacementConfig(edgeClearance = 12f)
            val obstacles = obstaclesOf(layout)
            val res =
                NotePlacer.place(
                    layout = layout,
                    notes = listOf(note(NotePlacement.ABOVE, "A")),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstacles,
                    config = config,
                )
            val n = res.noteRect()
            val probe = obstaclesOf(layout)
            probe.violatesClearance(r = n, clearance = 12f, noteGap = 16f, budget = ObstacleIndex.Budget(1_000_000L)) shouldBe false
        }
    })
