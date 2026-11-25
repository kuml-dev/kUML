@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * PAL Welle Q: the crossing count and the route-hit test of [ObstacleIndex] (basis of the stage D improvement in [NotePlacer])
 * and the placement behaviour on a layout where one side of the target connection is crossed by a foreign edge.
 */
class NotePlacerEdgeCrossingsTest :
    FunSpec({
        fun p(
            x: Float,
            y: Float,
        ) = Point(x = x, y = y)

        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        fun budget() = ObstacleIndex.Budget(1_000_000L)

        fun indexWithForeignEdge(): ObstacleIndex =
            ObstacleIndex().also {
                it.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(p(0f, 100f), p(200f, 100f)), ownerId = "F")
                it.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(p(0f, 300f), p(200f, 300f)), ownerId = "OWN")
                it.addPolyline(kind = ObstacleKind.FRAGMENT, pts = listOf(p(0f, 150f), p(200f, 150f)), ownerId = "G")
            }

        test("foreignEdgeCrossings counts a proper crossing of a foreign edge") {
            val n =
                indexWithForeignEdge().foreignEdgeCrossings(
                    pts = listOf(p(100f, 0f), p(100f, 120f)),
                    exemptOwners = emptySet(),
                    budget = budget(),
                )
            n shouldBe 1
        }

        test("foreignEdgeCrossings skips the exempt owner, non-edge lines and routes that do not reach an edge") {
            val idx = indexWithForeignEdge()
            // runs through y=100 (F), y=150 (fragment, ignored) and y=300 (OWN, exempt)
            idx.foreignEdgeCrossings(pts = listOf(p(100f, 0f), p(100f, 400f)), exemptOwners = setOf("OWN"), budget = budget()) shouldBe 1
            idx.foreignEdgeCrossings(pts = listOf(p(100f, 0f), p(100f, 400f)), exemptOwners = setOf("OWN", "F"), budget = budget()) shouldBe
                0
            idx.foreignEdgeCrossings(pts = listOf(p(100f, 0f), p(100f, 90f)), exemptOwners = emptySet(), budget = budget()) shouldBe 0
        }

        test("foreignEdgeCrossings ignores a touch within 0.5 px of the route end and counts every segment crossing") {
            val idx = indexWithForeignEdge()
            idx.foreignEdgeCrossings(pts = listOf(p(100f, 0f), p(100f, 100.2f)), exemptOwners = emptySet(), budget = budget()) shouldBe 0
            // zig-zag over F twice
            val zig = listOf(p(50f, 0f), p(50f, 120f), p(150f, 120f), p(150f, 0f))
            idx.foreignEdgeCrossings(pts = zig, exemptOwners = emptySet(), budget = budget()) shouldBe 2
        }

        test("routeHitsRects detects nodes, foreign notes and labels but not exempt owners or free routes") {
            val idx =
                ObstacleIndex().also {
                    it.addRect(kind = ObstacleKind.NODE, r = rect(100f, 100f, 100f, 50f), ownerId = "N")
                    it.addRect(kind = ObstacleKind.EDGE_LABEL, r = rect(300f, 100f, 60f, 20f), ownerId = "E")
                }
            val throughNode = listOf(p(50f, 125f), p(250f, 125f))
            idx.routeHitsRects(pts = throughNode, exemptOwners = emptySet(), budget = budget()) shouldBe true
            idx.routeHitsRects(pts = throughNode, exemptOwners = setOf("N"), budget = budget()) shouldBe false
            idx.routeHitsRects(pts = listOf(p(330f, 0f), p(330f, 200f)), exemptOwners = emptySet(), budget = budget()) shouldBe true
            idx.routeHitsRects(pts = listOf(p(250f, 0f), p(250f, 200f)), exemptOwners = emptySet(), budget = budget()) shouldBe false
        }

        val fixed =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size = Size(width = 120f, height = 48f)
            }

        /** Edge E (vertical, x=300); a node wall on its left side forces the note to the right; a foreign edge F covers the upper right. */
        fun placeAndCount(): Pair<Int, Int> {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 900f, height = 900f),
                    nodes = emptyMap(),
                    edges =
                        mapOf(
                            EdgeId("E") to EdgeRoute.Direct(source = p(300f, 0f), target = p(300f, 600f)),
                            EdgeId("F") to EdgeRoute.Direct(source = p(340f, 0f), target = p(340f, 330f)),
                        ),
                    groups = emptyMap(),
                )
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            obstacles.addRect(kind = ObstacleKind.NODE, r = rect(0f, -100f, 250f, 800f), ownerId = "WALL")
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes =
                        listOf(
                            NotePlacementInput(
                                noteId = "n",
                                body = "note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n::E", targetId = "E")),
                            ),
                        ),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstacles,
                    config = NotePlacementConfig(),
                    measure = fixed,
                )
            val route =
                r.layout.edges
                    .getValue(EdgeId("link:n::E"))
                    .polylinePoints()
                    .map { p(it.x - r.dx, it.y - r.dy) }
            // measured against the unshifted obstacle index of the original layout
            val crossings = obstacles.foreignEdgeCrossings(pts = route, exemptOwners = setOf("E"), budget = budget())
            val hits = if (obstacles.routeHitsRects(pts = route, exemptOwners = setOf("E", "WALL"), budget = budget())) 1 else 0
            return crossings to hits
        }

        test(
            "regression guard: a note forced to one side keeps an anchor line without foreign edge crossings",
        ).config(timeout = 30.seconds) {
            val (crossings, hits) = placeAndCount()
            crossings shouldBe 0
            hits shouldBe 0
        }
    })
