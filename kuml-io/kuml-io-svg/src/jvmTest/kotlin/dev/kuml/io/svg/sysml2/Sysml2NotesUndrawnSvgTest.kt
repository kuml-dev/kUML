package dev.kuml.io.svg.sysml2

import dev.kuml.core.model.note.KumlNoteCodes.NoteNotDrawnReason
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.normalizeSeqLayout
import dev.kuml.layout.bridge.note.NoteUndrawnCollector
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.polylinePoints
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Nacharbeit 45: owner notes end on the IBD owner frame, the SEQ anchor line ends on its lifeline, undrawn notes are reported. */
class Sysml2NotesUndrawnSvgTest :
    FunSpec(body = {

        fun elk(): dev.kuml.layout.KumlLayoutEngine {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            return LayoutEngineRegistry.get("elk.layered")!!
        }

        fun ibdSvg(
            model: Sysml2Model,
            ibd: IbdDiagram,
        ): String {
            val lr: LayoutResult =
                elk().layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = ibd), hints = LayoutHints.DEFAULT)
            return KumlSvgRenderer.toSvg(model = model, diagram = ibd, layoutResult = lr, theme = PlainTheme())
        }

        fun bddSvg(
            model: Sysml2Model,
            bdd: BdDiagram,
        ): String {
            val lr: LayoutResult =
                elk().layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = bdd), hints = LayoutHints.DEFAULT)
            return KumlSvgRenderer.toSvg(model = model, diagram = bdd, layoutResult = lr, theme = PlainTheme())
        }

        fun ibdModel(withNested: Boolean = false): Sysml2Model =
            sysml2Model(name = "M") {
                val wheel = partDef(name = "Wheel")
                val axle = partDef(name = "Axle") { part(name = "wheel", typeId = wheel.id) }
                val car =
                    partDef(name = "Car") {
                        part(name = "axle", typeId = axle.id)
                        part(name = "spare", typeId = axle.id)
                    }
                comment(text = "Owner note", "Car", id = "owner")
                if (withNested) comment(text = "Nested wheel", "Axle::wheel", id = "nested")
                comment(text = "Unknown anchor", "Nope", id = "unknown")
                ibd(name = "Car internals", owner = car)
            }

        test("ibd: a note on the owner ends on the owner frame, not on a child part") {
            val model = ibdModel()
            val svg = ibdSvg(model = model, ibd = model.diagrams.single() as IbdDiagram)
            val frame = Regex("""<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" class="kuml-ibd-frame"/>""").find(svg)
            checkNotNull(frame) { "owner note must make the IBD draw its owner frame" }
            val (fx, fy, fw, fh) =
                frame.destructured
                    .toList()
                    .map { it.toFloat() }
                    .let { listOf(it[0], it[1], it[2], it[3]) }
            val link =
                Regex(
                    """<g id="note-link:owner::[^"]+">\s*<line x1="([\d.]+)" y1="([\d.]+)" x2="([\d.]+)" y2="([\d.]+)"""",
                ).find(svg)
            checkNotNull(link) { "the owner note must have an anchor line" }
            val x2 = link.groupValues[3].toFloat()
            val y2 = link.groupValues[4].toFloat()
            val onVertical = (kotlin.math.abs(x2 - fx) <= 0.5f || kotlin.math.abs(x2 - (fx + fw)) <= 0.5f) && y2 in fy..(fy + fh)
            val onHorizontal = (kotlin.math.abs(y2 - fy) <= 0.5f || kotlin.math.abs(y2 - (fy + fh)) <= 0.5f) && x2 in fx..(fx + fw)
            (onVertical || onHorizontal) shouldBe true
        }

        test("ibd: a note on a nested part this diagram does not draw is reported as KUML-NOTE-W002 with reason nested and its line") {
            val model = ibdModel(withNested = true)
            val (svg, undrawn) = NoteUndrawnCollector.collecting { ibdSvg(model = model, ibd = model.diagrams.single() as IbdDiagram) }
            svg shouldNotContain "id=\"nested\""
            svg shouldContain "id=\"owner\""
            // The unknown anchor is KUML-NOTE-W001 only; the drawn owner note is never W002.
            undrawn.map { it.noteId } shouldBe listOf("nested")
            undrawn.single().reason shouldBe NoteNotDrawnReason.NESTED
            undrawn.single().anchorId shouldBe "Axle::wheel"
            undrawn.single().message() shouldBe "note not drawn: nested, anchor Axle::wheel (nested)"
        }

        test("ibd without a nested anchor reports nothing") {
            val model = ibdModel()
            val (_, undrawn) = NoteUndrawnCollector.collecting { ibdSvg(model = model, ibd = model.diagrams.single() as IbdDiagram) }
            undrawn.shouldBeEmpty()
        }

        test("bdd: a note on a part a drawn definition only lists as a row is reported as row-only") {
            val model =
                sysml2Model(name = "M") {
                    val wheel = partDef(name = "Wheel")
                    val car = partDef(name = "Car") { part(name = "wheel", typeId = wheel.id) }
                    comment(text = "On the part row", "Car::wheel", id = "row")
                    bdd(name = "BDD") {
                        include(car)
                        include(wheel)
                    }
                }
            val (_, undrawn) = NoteUndrawnCollector.collecting { bddSvg(model = model, bdd = model.diagrams.single() as BdDiagram) }
            undrawn.single().noteId shouldBe "row"
            undrawn.single().reason shouldBe NoteNotDrawnReason.ROW_ONLY
        }

        test("seq: with the note on the far side the anchor line still ends on the anchor lifeline stem") {
            val model =
                sysml2Model(name = "M") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    val c = lifelineDef(name = "C")
                    message(label = "ping", source = a, target = b, seqNo = 0)
                    message(label = "pong", source = b, target = c, seqNo = 1)
                    comment(text = "Note on the inner lifeline", firstAnchor = b, id = "nb")
                    seqDiagram(name = "SEQ") {
                        include(a)
                        include(b)
                        include(c)
                    }
                }
            val seq = model.diagrams.single() as SeqDiagram
            val raw = elk().layout(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq), hints = LayoutHints.DEFAULT)
            val normalized = Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = raw)
            val placed = KumlSvgRenderer.placeSeqNotesResult(model = model, diagram = seq, layout = normalized)
            val head = normalized.nodes[NodeId("B")]!!.bounds
            val stemX = head.origin.x + head.size.width / 2f
            val end =
                placed.layout.edges.entries
                    .single { it.key.value.startsWith("note-link:nb::") }
                    .value
                    .polylinePoints()
                    .last()
            (kotlin.math.abs(end.x - stemX) <= 0.5f) shouldBe true
            placed.undrawn.shouldBeEmpty()
        }
    })
