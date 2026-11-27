@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.hypot

/** PAL Welle X5: two anchor lines of different notes do not end on the same point of a node frame. */
class NotePlacerCoAnchorFanOutTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        val frame = rect(330f, 364f, 200f, 100f)

        test("a vertical arrival at the corner moves 16 px along the top border, the last bend follows") {
            val route = listOf(Point(256f, 151f), Point(256f, 163f), Point(330f, 163f), Point(330f, 364f))
            val variants = NotePlacer.fanVariants(route = route, frame = frame)
            variants shouldContain listOf(Point(256f, 151f), Point(256f, 163f), Point(346f, 163f), Point(346f, 364f))
            // moving along the left border would run along the border: no such variant
            variants.map { it.last() } shouldNotContain Point(330f, 380f)
        }

        test("a diagonal direct line only moves its end point, never closer than 8 px to a corner") {
            val route = listOf(Point(282f, 306f), Point(330f, 364f))
            val variants = NotePlacer.fanVariants(route = route, frame = frame)
            variants.map { it.last() } shouldContain Point(346f, 364f)
            for (v in variants) {
                val e = v.last()
                (e.x - frame.left >= 8f || e.y - frame.top >= 8f) shouldBe true
                v.first() shouldBe Point(282f, 306f)
            }
        }

        test("an end that is too close to a corner has no variant beyond the border") {
            val narrow = rect(0f, 0f, 20f, 100f)
            val route = listOf(Point(10f, -40f), Point(10f, 0f))
            NotePlacer.fanVariants(route = route, frame = narrow) shouldBe emptyList()
        }

        test("two notes on the same node never end on one point") {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 900f, height = 600f),
                    nodes = mapOf(NodeId("N") to NodeLayout(bounds = rect(300f, 300f, 160f, 80f))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val fixed =
                object : NoteMeasure {
                    override fun measure(
                        body: String,
                        maxWidth: Float,
                    ): Size = Size(width = 120f, height = 48f)
                }
            val r =
                NotePlacer.place(
                    layout = layout,
                    notes =
                        (0 until 3).map {
                            NotePlacementInput(
                                noteId = "n$it",
                                body = "note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n$it::N", targetId = "N")),
                            )
                        },
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    measure = fixed,
                )
            val ends =
                (0 until 3).map {
                    r.layout.edges
                        .getValue(EdgeId("link:n$it::N"))
                        .polylinePoints()
                        .last()
                }
            for (i in ends.indices) {
                for (j in i + 1 until ends.size) {
                    hypot(ends[i].x - ends[j].x, ends[i].y - ends[j].y) shouldBeGreaterThanOrEqual 4f
                }
            }
        }
    })
