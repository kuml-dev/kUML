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
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/** PAL wave K: the attach point of an anchor line on a connection keeps its distance from label rectangles. */
class NotePlacerEdgeAnchorClearanceTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        fun layoutOf(length: Float = 400f) =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 900f, height = length + 100f),
                nodes = emptyMap(),
                edges = mapOf(EdgeId("E") to EdgeRoute.Direct(source = Point(x = 100f, y = 0f), target = Point(x = 100f, y = length))),
                groups = emptyMap(),
            )

        val fixed =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size = Size(width = 120f, height = 48f)
            }

        /** Attach points (end of each anchor route) of the notes `n0..` on edge `E`. */
        fun attach(
            labels: List<Rect>,
            clearance: Float?,
            notes: Int = 1,
            length: Float = 400f,
        ): List<Point> {
            val layout = layoutOf(length)
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            labels.forEach { obstacles.addRect(kind = ObstacleKind.EDGE_LABEL, r = it) }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes =
                        (0 until notes).map {
                            NotePlacementInput(
                                noteId = "n$it",
                                body = "note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n$it::E", targetId = "E")),
                            )
                        },
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstacles,
                    config = clearance?.let { NotePlacementConfig(anchorLabelClearance = it) } ?: NotePlacementConfig(),
                    measure = fixed,
                )
            // The placer shifts the layout when a note lands at negative coordinates: undo it.
            return (0 until notes).map {
                val p =
                    r.layout.edges
                        .getValue(EdgeId("link:n$it::E"))
                        .polylinePoints()
                        .last()
                Point(x = p.x - r.dx, y = p.y - r.dy)
            }
        }

        fun dist(
            p: Point,
            r: Rect,
        ): Float {
            val dx = max(0f, max(r.origin.x - p.x, p.x - (r.origin.x + r.size.width)))
            val dy = max(0f, max(r.origin.y - p.y, p.y - (r.origin.y + r.size.height)))
            return sqrt(dx * dx + dy * dy)
        }

        test("clearance 0 keeps the legacy attach point").config(timeout = 30.seconds) {
            val label = rect(115f, 190f, 100f, 20f)
            attach(labels = listOf(label), clearance = 0f) shouldBe attach(labels = listOf(label), clearance = null)
        }

        test("a point 15 px from a label moves, a point 25 px away stays").config(timeout = 30.seconds) {
            val stay = attach(labels = listOf(rect(125f, 190f, 100f, 20f)), clearance = 20f).single()
            stay.y shouldBe 200f
            val near = rect(115f, 190f, 100f, 20f)
            val moved = attach(labels = listOf(near), clearance = 20f).single()
            dist(moved, near) shouldBeGreaterThanOrEqual 20f - 0.01f
            moved.x shouldBe 100f
        }

        test("no admissible point: the legacy point is kept and nothing throws").config(timeout = 30.seconds) {
            val cover = rect(0f, -50f, 300f, 500f)
            val withClearance = attach(labels = listOf(cover), clearance = 20f)
            withClearance.single() shouldBe attach(labels = listOf(cover), clearance = 0f).single()
        }

        test("a very long connection is handled with a capped number of candidates").config(timeout = 30.seconds) {
            val p = attach(labels = listOf(rect(115f, 49_990f, 100f, 20f)), clearance = 20f, length = 100_000f).single()
            p.y shouldBeGreaterThanOrEqual 12f
            p.y shouldBeLessThanOrEqual 100_000f - 12f
        }

        test("two anchors on one connection stay 16 px apart and 12 px from its ends").config(timeout = 30.seconds) {
            val ps = attach(labels = listOf(rect(115f, 190f, 100f, 20f)), clearance = 20f, notes = 2)
            sqrt((ps[0].x - ps[1].x) * (ps[0].x - ps[1].x) + (ps[0].y - ps[1].y) * (ps[0].y - ps[1].y)) shouldBeGreaterThanOrEqual 16f
            ps.forEach {
                it.y shouldBeGreaterThanOrEqual 12f
                it.y shouldBeLessThanOrEqual 388f
            }
        }
    })
