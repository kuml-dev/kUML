@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Nacharbeit 38: the flow-axis preference of the strict note placement. */
class NotePlacerFlowAxisTest :
    FunSpec({
        fun rect(
            x: Float,
            y: Float,
            w: Float,
            h: Float,
        ) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

        val anchorBox = rect(300f, 300f, 120f, 60f)

        fun layoutOf(extra: Map<NodeId, NodeLayout> = emptyMap()): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 720f, height = 660f),
                nodes = mapOf(NodeId("A") to NodeLayout(bounds = anchorBox)) + extra,
                edges = emptyMap(),
                groups = emptyMap(),
            )

        fun place(
            layout: LayoutResult,
            flow: FlowAxis,
        ): Rect {
            val policy = StrictNotePolicy(hardLabelsAndTitles = true, flow = flow)
            return NotePlacer
                .place(
                    layout = layout,
                    notes =
                        listOf(
                            NotePlacementInput(
                                noteId = "n",
                                body = "A note",
                                anchors = listOf(NoteAnchorRef(linkId = "link:n::A", targetId = "A")),
                            ),
                        ),
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = Sysml2NotePlacement.obstaclesOf(layout),
                    config = policy.toConfig(NotePlacementConfig()),
                ).layout.nodes
                .getValue(NodeId("n"))
                .bounds
        }

        fun beside(r: Rect): Boolean =
            r.origin.x >= anchorBox.origin.x + anchorBox.size.width || r.origin.x + r.size.width <= anchorBox.origin.x

        test("horizontal flow with free space on all sides puts the note above or below").config(timeout = 30.seconds) {
            beside(place(layout = layoutOf(), flow = FlowAxis.HORIZONTAL)) shouldBe false
        }

        test("vertical flow with free space on all sides puts the note beside the anchor").config(timeout = 30.seconds) {
            beside(place(layout = layoutOf(), flow = FlowAxis.VERTICAL)) shouldBe true
        }

        test("the preference is soft: with no room across the flow the note goes along it, with a hard rule still obeyed")
            .config(timeout = 30.seconds) {
                // Blocks above and below the anchor so that only the left or right side remains close to the anchor.
                val blockers =
                    mapOf(
                        NodeId("up") to NodeLayout(bounds = rect(0f, 0f, 720f, 270f)),
                        NodeId("down") to NodeLayout(bounds = rect(0f, 390f, 720f, 270f)),
                    )
                val layout = layoutOf(blockers)
                val note = place(layout = layout, flow = FlowAxis.HORIZONTAL)
                for (n in layout.nodes.values) {
                    val b = n.bounds
                    val overlap =
                        note.origin.x - 16f < b.origin.x + b.size.width &&
                            b.origin.x < note.origin.x + note.size.width + 16f &&
                            note.origin.y - 16f < b.origin.y + b.size.height &&
                            b.origin.y < note.origin.y + note.size.height + 16f
                    overlap shouldBe false
                }
            }

        test("without a flow axis the placement is the same as before (FlowAxis.NONE costs nothing)").config(timeout = 30.seconds) {
            val none = place(layout = layoutOf(), flow = FlowAxis.NONE)
            val legacy =
                NotePlacer
                    .place(
                        layout = layoutOf(),
                        notes =
                            listOf(
                                NotePlacementInput(
                                    noteId = "n",
                                    body = "A note",
                                    anchors = listOf(NoteAnchorRef(linkId = "link:n::A", targetId = "A")),
                                ),
                            ),
                        resolver = AnchorGeometryResolver(layoutOf()),
                        obstacles = Sysml2NotePlacement.obstaclesOf(layoutOf()),
                        config = StrictNotePolicy(hardLabelsAndTitles = true).toConfig(NotePlacementConfig()),
                    ).layout.nodes
                    .getValue(NodeId("n"))
                    .bounds
            none shouldBe legacy
        }
    })
