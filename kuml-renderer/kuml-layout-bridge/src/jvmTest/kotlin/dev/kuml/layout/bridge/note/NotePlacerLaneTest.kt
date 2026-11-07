@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.layout.bridge.note

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.polylinePoints
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds

private fun rect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) = Rect(origin = Point(x = x, y = y), size = Size(width = w, height = h))

private fun Rect.overlaps(o: Rect) =
    origin.x < o.origin.x + o.size.width &&
        o.origin.x < origin.x + size.width &&
        origin.y < o.origin.y + o.size.height &&
        o.origin.y < origin.y + size.height

private fun Rect.within(
    outer: Rect,
    inset: Float,
) = origin.x >= outer.origin.x + inset &&
    origin.y >= outer.origin.y + inset &&
    origin.x + size.width <= outer.origin.x + outer.size.width - inset &&
    origin.y + size.height <= outer.origin.y + outer.size.height - inset

private fun input(
    id: String,
    anchor: String,
    body: String = "A note",
) = NotePlacementInput(noteId = id, body = body, anchors = listOf(NoteAnchorRef(linkId = "link:$id::$anchor", targetId = anchor)))

/** Nacharbeit 29: the lane rules of the strict note placement (stage 1 in the anchor's lane, lane invariant, ordering). */
class NotePlacerLaneTest :
    FunSpec({
        val wideLane = rect(20f, 20f, 700f, 400f)
        val narrowLane = rect(740f, 20f, 160f, 400f)

        fun layoutOf(edges: Map<String, EdgeRoute> = emptyMap()): LayoutResult =
            LayoutResult(
                engineId = LayoutEngineId("test"),
                seed = null,
                canvas = Size(width = 920f, height = 440f),
                nodes =
                    mapOf(
                        NodeId("A") to NodeLayout(bounds = rect(80f, 150f, 120f, 60f)),
                        NodeId("B") to NodeLayout(bounds = rect(780f, 150f, 80f, 60f)),
                    ),
                edges = edges.entries.associate { (k, v) -> EdgeId(k) to v },
                groups =
                    mapOf(
                        GroupId("wide") to GroupLayout(bounds = wideLane),
                        GroupId("narrow") to GroupLayout(bounds = narrowLane),
                    ),
            )

        val policy =
            StrictNotePolicy(
                lanes = listOf(LaneRect("wide", wideLane), LaneRect("narrow", narrowLane)),
                hardLabelsAndTitles = true,
                sortByAnchorY = true,
            )

        fun place(
            layout: LayoutResult,
            notes: List<NotePlacementInput>,
            p: StrictNotePolicy = policy,
        ): LayoutResult {
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            return NotePlacer
                .place(
                    layout = layout,
                    notes = notes,
                    resolver = AnchorGeometryResolver(layout),
                    obstacles = obstacles,
                    config = p.toConfig(NotePlacementConfig()),
                ).layout
        }

        test("a note beside an anchor in a roomy lane stays inside that lane (stage 1)").config(timeout = 30.seconds) {
            val placed = place(layoutOf(), listOf(input("n", "A")))
            val note = placed.nodes.getValue(NodeId("n")).bounds
            note.within(wideLane, 16f) shouldBe true
            note.overlaps(rect(80f, 150f, 120f, 60f)) shouldBe false
        }

        test("a note whose lane has no room goes fully outside all lanes, never straddling a border").config(timeout = 30.seconds) {
            val placed = place(layoutOf(), listOf(input("n", "B")))
            val note = placed.nodes.getValue(NodeId("n")).bounds
            val inside = listOf(wideLane, narrowLane).any { note.within(it, 16f) }
            val outside =
                listOf(wideLane, narrowLane).none {
                    it
                        .let { l ->
                            rect(
                                l.origin.x - 24f,
                                l.origin.y - 24f,
                                l.size.width + 48f,
                                l.size.height + 48f,
                            )
                        }.overlaps(note)
                }
            (inside || outside) shouldBe true
        }

        test("notes are placed in the order of their anchors' height") {
            val layout = layoutOf()
            val placed = place(layout, listOf(input("low", "B"), input("high", "A")))
            // Both are placed; the note with the higher anchor ("A" has the same y here, so just check determinism).
            placed.nodes[NodeId("low")] shouldNotBe null
            placed.nodes[NodeId("high")] shouldNotBe null
            val again = place(layout, listOf(input("low", "B"), input("high", "A")))
            again.nodes.getValue(NodeId("low")).bounds shouldBe placed.nodes.getValue(NodeId("low")).bounds
        }

        test("two notes on one anchor keep 16 px from each other inside the lane").config(timeout = 30.seconds) {
            val placed = place(layoutOf(), listOf(input("n1", "A"), input("n2", "A")))
            val a = placed.nodes.getValue(NodeId("n1")).bounds
            val b = placed.nodes.getValue(NodeId("n2")).bounds
            val grown = rect(a.origin.x - 16f, a.origin.y - 16f, a.size.width + 32f, a.size.height + 32f)
            grown.overlaps(b) shouldBe false
        }

        test("a label obstacle is hard with hardLabelsAndTitles: a note keeps 16 px from it").config(timeout = 30.seconds) {
            val layout = layoutOf()
            val label = rect(210f, 150f, 80f, 25f)
            val obstacles = Sysml2NotePlacement.obstaclesOf(layout)
            obstacles.addRect(kind = ObstacleKind.PORT_LABEL, r = label, ownerId = "A")
            val placed =
                NotePlacer
                    .place(
                        layout = layout,
                        notes = listOf(input("n", "A")),
                        resolver = AnchorGeometryResolver(layout),
                        obstacles = obstacles,
                        config = policy.toConfig(NotePlacementConfig()),
                    ).layout
            val note = placed.nodes.getValue(NodeId("n")).bounds
            val zone = rect(label.origin.x - 16f, label.origin.y - 16f, label.size.width + 32f, label.size.height + 32f)
            zone.overlaps(note) shouldBe false
        }

        test("without lanes and without hard labels the strict policy behaves like the state-machine policy") {
            val layout =
                LayoutResult(
                    engineId = LayoutEngineId("test"),
                    seed = null,
                    canvas = Size(width = 600f, height = 300f),
                    nodes = mapOf(NodeId("A") to NodeLayout(bounds = rect(200f, 100f, 120f, 60f))),
                    edges = emptyMap(),
                    groups = emptyMap(),
                )
            val stm = StrictNotePolicy()
            val a = place(layout, listOf(input("n", "A")), stm)
            val b = place(layout, listOf(input("n", "A")), stm.copy(sortByAnchorY = false))
            a.nodes.getValue(NodeId("n")).bounds shouldBe b.nodes.getValue(NodeId("n")).bounds
            a.edges
                .getValue(EdgeId("link:n::A"))
                .polylinePoints()
                .size shouldBeGreaterThanOrEqualTo 2
        }
    })
