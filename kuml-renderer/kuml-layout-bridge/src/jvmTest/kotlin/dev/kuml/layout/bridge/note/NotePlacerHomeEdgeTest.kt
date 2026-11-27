@file:Suppress("RequireNamedArguments") // geometry literals

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** PAL Welle X6: a note with a home strip does not sit on a drawn edge when its strip has a free slot. */
class NotePlacerHomeEdgeTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        val fixed =
            object : NoteMeasure {
                override fun measure(
                    body: String,
                    maxWidth: Float,
                ): Size = Size(width = 60f, height = 40f)
            }

        fun layout(withEdge: Boolean) =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 400f, height = 300f),
                nodes = mapOf(NodeId("N") to NodeLayout(bounds = rect(20f, 20f, 260f, 60f))),
                edges =
                    if (withEdge) {
                        listOf(60f, 140f, 220f).associate {
                            EdgeId("other$it") to EdgeRoute.Direct(source = Point(it, 120f), target = Point(it, 290f))
                        }
                    } else {
                        emptyMap()
                    },
                groups = emptyMap(),
            )

        fun placed(withEdge: Boolean): Rect {
            val l = layout(withEdge)
            val r =
                NotePlacer.place(
                    layout = l,
                    notes =
                        listOf(
                            NotePlacementInput(
                                noteId = "n",
                                body = "note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n::N", targetId = "N")),
                            ),
                        ),
                    resolver = AnchorGeometryResolver(l),
                    obstacles = Sysml2NotePlacement.obstaclesOf(l),
                    measure = fixed,
                    config = NotePlacementConfig(noteHomes = mapOf("n" to rect(0f, 180f, 320f, 60f))),
                )
            return r.layout.nodes[NodeId("n")]!!.bounds
        }

        test("a free slot of the home strip beats a cheaper slot on a drawn edge") {
            val p = placed(withEdge = true)
            val onEdge = listOf(60f, 140f, 220f).any { p.left < it + 1f && p.right > it - 1f }
            (p.toString() to onEdge) shouldBe (p.toString() to false)
            (p.top >= 180f && p.bottom <= 240.5f) shouldBe true
        }

        test("without an edge the note stays in its strip") {
            val p = placed(withEdge = false)
            (p.top >= 180f && p.bottom <= 240.5f && p.left >= 0f && p.right <= 320.5f) shouldBe true
        }

        test("foreignEdgesHitting counts a crossing edge, skips the exempt owner and ignores edges beside the rectangle") {
            val o = ObstacleIndex()
            o.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(Point(50f, 0f), Point(50f, 100f)), ownerId = "e1")
            o.addPolyline(kind = ObstacleKind.EDGE, pts = listOf(Point(500f, 0f), Point(500f, 100f)), ownerId = "e2")
            o.addPolyline(kind = ObstacleKind.ANCHOR_ROUTE, pts = listOf(Point(60f, 0f), Point(60f, 100f)), ownerId = "link")
            val r = rect(0f, 10f, 100f, 40f)
            o.foreignEdgesHitting(r = r, exemptOwners = emptySet(), budget = ObstacleIndex.Budget(1_000L)) shouldBe 1
            o.foreignEdgesHitting(r = r, exemptOwners = setOf("e1"), budget = ObstacleIndex.Budget(1_000L)) shouldBe 0
        }
    })
