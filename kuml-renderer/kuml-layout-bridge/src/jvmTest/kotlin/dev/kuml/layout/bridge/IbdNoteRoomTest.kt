package dev.kuml.layout.bridge

import dev.kuml.core.model.note.NotePlacement
import dev.kuml.layout.NodeId
import dev.kuml.layout.NoteRoom
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** PAL Welle Y4: which notes of an IBD reserve room above or below their part box, and how much. */
class IbdNoteRoomTest :
    FunSpec({
        val long = "A fairly long note text that wraps over several lines so the measured note is clearly higher than one line"

        fun ibdModel(notes: Sysml2Model.() -> Unit = {}): Pair<Sysml2Model, IbdDiagram> {
            val model =
                sysml2Model(name = "Room") {
                    val sig = portDef(name = "Sig")
                    val link = connectionDef(name = "Link")
                    val src = partDef(name = "Source") { port(name = "out", typeId = sig.id, direction = PortDirection.Out) }
                    val dst = partDef(name = "Sink") { port(name = "inp", typeId = sig.id, direction = PortDirection.In) }
                    val sys =
                        partDef(name = "Sys") {
                            part(name = "a", typeId = src.id)
                            part(name = "b", typeId = dst.id)
                            connect(name = "I1", typeId = link.id, sourceEndId = "Sys::a::out", targetEndId = "Sys::b::inp")
                        }
                    comment(text = "above a first", "Sys::a", id = "n1", placement = NotePlacement.ABOVE)
                    comment(text = long, "Sys::a", id = "n2", placement = NotePlacement.ABOVE)
                    comment(text = "above a third", "Sys::a", id = "n3", placement = NotePlacement.ABOVE)
                    comment(text = "below a", "Sys::a", id = "n4", placement = NotePlacement.BELOW)
                    comment(text = "on the connector", "Sys::I1", id = "n5", placement = NotePlacement.ABOVE)
                    comment(text = "on a port", "Sys::a::out", id = "n6", placement = NotePlacement.BELOW)
                    comment(text = "two anchors", "Sys::a", "Sys::b", id = "n7", placement = NotePlacement.ABOVE)
                    comment(text = "right of b", "Sys::b", id = "n8", placement = NotePlacement.RIGHT)
                    comment(text = "free", "Sys::b", id = "n9")
                    ibd(name = "Sys", owner = sys) {}
                }
            return model to model.diagrams.filterIsInstance<IbdDiagram>().single()
        }

        test("only a note with exactly one top-level part anchor and an ABOVE or BELOW hint is eligible, two per side at most") {
            val (model, diagram) = ibdModel()
            val reserved = IbdNoteRoom.eligible(model = model, diagram = diagram)
            val above = reserved.single { it.nodeId == "Sys::a" && it.side == NotePlacement.ABOVE }
            above.notes.map { it.first } shouldBe listOf("n1", "n2")
            val below = reserved.single { it.nodeId == "Sys::a" && it.side == NotePlacement.BELOW }
            below.notes.map { it.first } shouldBe listOf("n4")
            // n3 (third on a side), n5 (connector), n6 (port), n7 (two anchors), n8 (RIGHT), n9 (no hint) take the general placement
            Sysml2LayoutBridge.ibdNoteRoomNoteIds(model = model, diagram = diagram) shouldContainExactlyInAnyOrder listOf("n1", "n2", "n4")
        }

        test("the room is anchor gap, the notes with gaps between them and the clearance, capped") {
            val (model, diagram) = ibdModel()
            val reserved = IbdNoteRoom.eligible(model = model, diagram = diagram)
            val above = reserved.single { it.side == NotePlacement.ABOVE }
            val expected =
                IbdNoteRoom.ANCHOR_GAP_PX + above.notes.sumOf { it.second.height.toDouble() }.toFloat() + IbdNoteRoom.GAP_PX +
                    IbdNoteRoom.CLEARANCE_PX
            IbdNoteRoom.heightOf(above.notes) shouldBe expected
            val rooms = IbdNoteRoom.rooms(reserved)
            rooms.getValue(NodeId("Sys::a")) shouldBe
                NoteRoom(top = expected, bottom = IbdNoteRoom.heightOf(reserved.single { it.side == NotePlacement.BELOW }.notes))
            val tall = List(40) { "x$it" to above.notes.first().second }
            IbdNoteRoom.heightOf(tall) shouldBe IbdNoteRoom.CAP_PX
            IbdNoteRoom.heightOf(above.notes) shouldBeLessThanOrEqual IbdNoteRoom.CAP_PX
        }

        test("onlyNoteIds restricts the reservation to the notes that missed their hint") {
            val (model, diagram) = ibdModel()
            val rooms = Sysml2LayoutBridge.ibdNoteRooms(model = model, diagram = diagram, onlyNoteIds = setOf("n4"))
            rooms.getValue(NodeId("Sys::a")).top shouldBe 0f
            (rooms.getValue(NodeId("Sys::a")).bottom > 0f) shouldBe true
            Sysml2LayoutBridge.ibdNoteRooms(model = model, diagram = diagram, onlyNoteIds = setOf("n5", "n9")).shouldBeEmpty()
        }

        test("a diagram without a qualifying note has no rooms, and an empty room map returns the very same graph") {
            val model =
                sysml2Model(name = "Plain") {
                    val t = partDef(name = "T")
                    val sys = partDef(name = "S") { part(name = "p", typeId = t.id) }
                    ibd(name = "S", owner = sys) {}
                }
            val diagram = model.diagrams.filterIsInstance<IbdDiagram>().single()
            Sysml2LayoutBridge.ibdNoteRooms(model = model, diagram = diagram).shouldBeEmpty()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            graph.withNoteRooms(emptyMap()) shouldBeSameInstanceAs graph
        }

        test("withNoteRooms sets the room on the named nodes only") {
            val (model, diagram) = ibdModel()
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            val roomy = graph.withNoteRooms(Sysml2LayoutBridge.ibdNoteRooms(model = model, diagram = diagram))
            roomy.nodes
                .single { it.id == NodeId("Sys::a") }
                .noteRoom!!
                .top shouldBe
                IbdNoteRoom.heightOf(IbdNoteRoom.eligible(model = model, diagram = diagram).first { it.side == NotePlacement.ABOVE }.notes)
            roomy.nodes.filter { it.id != NodeId("Sys::a") }.all { it.noteRoom == null } shouldBe true
            graph.nodes.all { it.noteRoom == null } shouldBe true
        }
    })
