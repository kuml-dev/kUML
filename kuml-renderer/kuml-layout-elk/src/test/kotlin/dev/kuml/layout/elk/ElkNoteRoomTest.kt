package dev.kuml.layout.elk

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.NoteRoom
import dev.kuml.layout.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/** PAL Welle Y4: the note room of a node ([LayoutNode.noteRoom]) is kept free by ELK and reported nowhere else. */
class ElkNoteRoomTest :
    FunSpec({
        val engine = ElkLayoutEngine()
        val hints = LayoutHints(direction = LayoutDirection.TopToBottom)

        fun graph(room: NoteRoom?): LayoutGraph {
            val a = LayoutNode(id = NodeId("A"), intrinsicSize = Size(width = 100f, height = 40f))
            val b = LayoutNode(id = NodeId("B"), intrinsicSize = Size(width = 100f, height = 40f), noteRoom = room)
            val c = LayoutNode(id = NodeId("C"), intrinsicSize = Size(width = 100f, height = 40f))

            fun edge(
                id: String,
                s: String,
                t: String,
            ) = LayoutEdge(id = EdgeId(id), source = EndpointRef(nodeId = NodeId(s)), target = EndpointRef(nodeId = NodeId(t)))
            return LayoutGraph(nodes = listOf(a, b, c), edges = listOf(edge("e1", "A", "B"), edge("e2", "B", "C")))
        }

        fun layout(room: NoteRoom?) = engine.layout(graph = graph(room), hints = hints)

        test("a top room pushes the node's layer down by at least the room and leaves sizes and labels alone") {
            val plain = layout(null)
            val roomy = layout(NoteRoom(top = 80f))
            val gapPlain =
                plain.nodes
                    .getValue(NodeId("B"))
                    .bounds.origin.y -
                    plain.nodes
                        .getValue(NodeId("A"))
                        .bounds.origin.y
            val gapRoomy =
                roomy.nodes
                    .getValue(NodeId("B"))
                    .bounds.origin.y -
                    roomy.nodes
                        .getValue(NodeId("A"))
                        .bounds.origin.y
            (gapRoomy - gapPlain) shouldBeGreaterThanOrEqual 80f
            roomy.nodes
                .getValue(NodeId("B"))
                .bounds.size shouldBe
                plain.nodes
                    .getValue(NodeId("B"))
                    .bounds.size
            roomy.nodes.getValue(NodeId("B")).labels shouldBe emptyMap()
            roomy.canvas.width shouldBe plain.canvas.width
        }

        test("a bottom room pushes the next layer down by at least the room") {
            val plain = layout(null)
            val roomy = layout(NoteRoom(bottom = 60f))
            val gapPlain =
                plain.nodes
                    .getValue(NodeId("C"))
                    .bounds.origin.y -
                    plain.nodes
                        .getValue(NodeId("B"))
                        .bounds.origin.y
            val gapRoomy =
                roomy.nodes
                    .getValue(NodeId("C"))
                    .bounds.origin.y -
                    roomy.nodes
                        .getValue(NodeId("B"))
                        .bounds.origin.y
            (gapRoomy - gapPlain) shouldBeGreaterThanOrEqual 60f
            roomy.nodes.getValue(NodeId("B")).labels shouldBe emptyMap()
        }

        test("top and bottom room of one node both count") {
            val plain = layout(null)
            val roomy = layout(NoteRoom(top = 50f, bottom = 40f))
            val span = { r: dev.kuml.layout.LayoutResult ->
                r.nodes
                    .getValue(NodeId("C"))
                    .bounds.origin.y -
                    r.nodes
                        .getValue(NodeId("A"))
                        .bounds.origin.y
            }
            (span(roomy) - span(plain)) shouldBeGreaterThanOrEqual 90f
        }

        test("without a room the result equals the layout of a graph that never had the field") {
            layout(NoteRoom()) shouldBe layout(null)
        }
    })
