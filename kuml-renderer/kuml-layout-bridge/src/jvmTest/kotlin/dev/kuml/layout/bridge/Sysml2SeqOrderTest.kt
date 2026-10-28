package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** C3-2 — Lifeline-Reihenfolge links→rechts folgt der Deklaration, unabhängig von Engine und Hints. */
class Sysml2SeqOrderTest :
    StringSpec({
        val model =
            sysml2Model(name = "Order") {
                val a = lifelineDef(name = "a")
                val b = lifelineDef(name = "A considerably longer lifeline name number two")
                val c = lifelineDef(name = "c")
                val d = lifelineDef(name = "Another really quite long lifeline name here!")
                message(label = "m0", source = a, target = b, seqNo = 0)
                message(label = "m1", source = c, target = d, seqNo = 1)
                seqDiagram(name = "S") {
                    include(a)
                    include(b)
                    include(c)
                    include(d)
                }
            }
        val seq = model.diagrams.filterIsInstance<SeqDiagram>().single()

        fun centres(result: LayoutResult): List<Float> =
            seq.elementIds.map {
                result.nodes
                    .getValue(NodeId(it))
                    .bounds
                    .let { b -> b.origin.x + b.size.width / 2f }
            }

        "normalizeSeqLayout orders lifelines by declaration even if the engine shuffled them" {
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val engine = ElkLayoutEngine()
            val raw = engine.layout(graph = graph, hints = LayoutHints.DEFAULT)
            val fixed = Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = raw)
            val xs = centres(fixed)
            xs.zipWithNext().all { (l, r) -> l < r } shouldBe true
            // y bleibt, Breiten bleiben
            for (id in seq.elementIds) {
                fixed.nodes
                    .getValue(NodeId(id))
                    .bounds.origin.y shouldBe
                    raw.nodes
                        .getValue(NodeId(id))
                        .bounds.origin.y
                fixed.nodes
                    .getValue(NodeId(id))
                    .bounds.size shouldBe
                    raw.nodes
                        .getValue(NodeId(id))
                        .bounds.size
            }
        }

        "normalizeSeqLayout repairs a deliberately reversed layout and keeps the canvas wide enough" {
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val raw = ElkLayoutEngine().layout(graph = graph, hints = LayoutHints.DEFAULT.copy(preserveNodeOrder = true))
            // künstlich umdrehen
            val reversed =
                raw.copy(
                    nodes =
                        raw.nodes.entries.toList().let { list ->
                            val xsSorted = list.map { it.value.bounds.origin }.sortedBy { it.x }.reversed()
                            list
                                .mapIndexed { i, e ->
                                    e.key to e.value.copy(bounds = e.value.bounds.copy(origin = xsSorted[i]))
                                }.toMap()
                        },
                )
            val fixed = Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = reversed)
            centres(fixed).zipWithNext().all { (l, r) -> l < r } shouldBe true
            val right = fixed.nodes.values.maxOf { it.bounds.origin.x + it.bounds.size.width }
            (fixed.canvas.width >= right) shouldBe true
        }

        "an already ordered layout is returned unchanged (pixel-identical images)" {
            val graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = seq)
            val ordered =
                ElkLayoutEngine().layout(graph = graph, hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq))
            Sysml2LayoutBridge.normalizeSeqLayout(model = model, diagram = seq, layoutResult = ordered) shouldBe ordered
        }

        "layoutHintsFor: SEQ keeps node order, REQ/STM/ACT carry the CLI spacings, others use the default" {
            Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = seq).preserveNodeOrder shouldBe true
            val req = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = ReqDiagram(name = "r", elementIds = emptyList())).spacing
            req.nodeToNode shouldBe 80f
            req.edgeToEdge shouldBe 20f
            req.layerToLayer shouldBe 100f
            val stm = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = StmDiagram(name = "s", elementIds = emptyList())).spacing
            stm.nodeToNode shouldBe 80f
            stm.edgeToEdge shouldBe 28f
            stm.groupPadding shouldBe 24f
            val act = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = ActDiagram(name = "a", elementIds = emptyList())).spacing
            act.nodeToNode shouldBe 100f
            act.layerToLayer shouldBe 100f
            Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = BdDiagram(name = "b", elementIds = emptyList())) shouldBe
                LayoutHints.DEFAULT
        }
    })
