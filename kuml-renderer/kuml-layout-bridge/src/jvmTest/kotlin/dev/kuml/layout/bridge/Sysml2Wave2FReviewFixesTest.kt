package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/** PAL wave 2F review fixes: room left of the leftmost lifeline, side text between lifelines, region overlap warning. */
class Sysml2Wave2FReviewFixesTest :
    FunSpec({
        fun layoutSeq(model: Sysml2Model): Pair<SeqDiagram, LayoutResult> {
            val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()
            val raw =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq),
                )
            return seq to Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = raw)
        }

        fun centre(
            r: LayoutResult,
            id: String,
        ): Float =
            r.nodes
                .getValue(NodeId(id))
                .bounds
                .let { it.origin.x + it.size.width / 2f }

        test("a long duration constraint on the leftmost lifeline gets room to its left (text start >= 0), idempotent") {
            val expression = "tAntwort - tAnfrage <= 2 s"
            val model =
                sysml2Model(name = "DC") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    message(label = "req", source = a, target = b, seqNo = 0)
                    message(label = "res", source = b, target = a, seqNo = 1)
                    durationConstraint(lifeline = a, from = 0, to = 1, expression = expression)
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val (seq, result) = layoutSeq(model)
            // the renderer draws the text right-aligned at (axis - 5 - 12 - 8); its width is ~6.5 px per character
            val textStart = centre(result, "A") - (5f + 12f + 8f) - ("{$expression}".length * 6.5f)
            (textStart >= 0f) shouldBe true
            Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = result) shouldBe result
        }

        test("a duration constraint on the second lifeline keeps clear of the first lifeline's self-message label") {
            val model =
                sysml2Model(name = "DC2") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    message(label = "a long self message label", source = a, target = a, seqNo = 0)
                    message(label = "req", source = a, target = b, seqNo = 1)
                    durationConstraint(lifeline = b, from = 0, to = 1, expression = "tAntwort - tAnfrage <= 2 s")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val (_, result) = layoutSeq(model)
            val aRight = 32f + 4f + "a long self message label".length * 6.5f
            val bLeft = 5f + 12f + 8f + "tAntwort - tAnfrage <= 2 s".length * 6.5f + 2f * 6.5f
            (centre(result, "B") - centre(result, "A") >= aRight + bLeft) shouldBe true
        }

        test("a time observation label left of lifeline B keeps clear of lifeline A's self-message label") {
            val model =
                sysml2Model(name = "TO") {
                    val a = lifelineDef(name = "A")
                    val b = lifelineDef(name = "B")
                    message(label = "a long self message label", source = a, target = a, seqNo = 0)
                    message(label = "other", source = b, target = b, seqNo = 1)
                    timeObservation(lifeline = b, seqNo = 0, name = "tObservedMoment")
                    seqDiagram(name = "S") {
                        include(a)
                        include(b)
                    }
                }
            val (_, result) = layoutSeq(model)
            val aRight = 32f + 4f + "a long self message label".length * 6.5f
            val bLeft = 10f + ("tObservedMoment".length + 2) * 6.5f
            (centre(result, "B") - centre(result, "A") >= aRight + bLeft) shouldBe true
        }

        test("KUML-LAYOUT-W006 is reported when a region frame cannot be cleared of a foreign node") {
            val model =
                sysml2Model(name = "R") {
                    val lane = activityPartition(name = "Lane")
                    // two interleaved regions in one row: whichever way a node moves, it ends up inside the other frame
                    val r1 = interruptibleRegion(name = "R1")
                    val r2 = interruptibleRegion(name = "R2")
                    val a = actionDef(name = "A", partition = lane, region = r1)
                    val b = actionDef(name = "B", partition = lane, region = r2)
                    val c = actionDef(name = "C", partition = lane, region = r1)
                    val d = actionDef(name = "D", partition = lane, region = r2)
                    actDiagram(name = "D") {
                        include(a)
                        include(b)
                        include(c)
                        include(d)
                        layout { sameLayer(a, b, c, d) }
                    }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val raw =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = act),
                )
            val norm = Sysml2LayoutBridge.normalizeActLayout(model = model, diagram = act, layoutResult = raw)
            val warnings = norm.warnings
            warnings.filter { it.code == LayoutCodes.REGION_OVERLAP }.shouldNotBeEmpty()
        }

        test("no W006 when no region exists") {
            val model =
                sysml2Model(name = "N") {
                    val lane = activityPartition(name = "Lane")
                    val a = actionDef(name = "A", partition = lane)
                    actDiagram(name = "D") { include(a) }
                }
            val act = model.diagrams.filterIsInstance<ActDiagram>().single()
            val raw =
                ElkLayoutEngine().layout(
                    graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = act),
                    hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = act),
                )
            Sysml2LayoutBridge
                .normalizeActLayout(model = model, diagram = act, layoutResult = raw)
                .warnings
                .filter { it.code == LayoutCodes.REGION_OVERLAP }
                .shouldBeEmpty()
        }
    })
