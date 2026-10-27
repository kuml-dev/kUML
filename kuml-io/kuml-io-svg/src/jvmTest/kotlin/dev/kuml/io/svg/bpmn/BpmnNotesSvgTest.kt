package dev.kuml.io.svg.bpmn

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.bpmn.BpmnContentSizeProvider
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.renderer.theme.core.PlainTheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BpmnNotesSvgTest :
    FunSpec(body = {

        fun engine(): dev.kuml.layout.KumlLayoutEngine {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            return LayoutEngineRegistry.get("elk.layered")!!
        }

        test("process: text annotation renders as bracket with a dotted association, no fold") {
            val model =
                bpmnModel(name = "Orders") {
                    process(id = "p", name = "Order") {
                        val s = startEvent(name = "Start")
                        val t = task(name = "Check order")
                        val e = endEvent(name = "Done")
                        s flowsTo t flowsTo e
                        textAnnotation(text = "Checks <stock> & \"credit\"", t, id = "ann1")
                        note(text = "free-standing", id = "ann2")
                    }
                    diagram(name = "Order", processId = "p")
                }
            val d = model.diagrams.single() as ProcessDiagram
            val process = model.processes.single()
            val graph = BpmnLayoutBridge.toLayoutGraph(model = model, diagram = d, sizeProvider = BpmnContentSizeProvider(model))
            val lr = engine().layout(graph = graph, hints = LayoutHints.DEFAULT)
            val size = lr.nodes[NodeId("ann1")]!!.bounds.size
            (size.width >= NoteLayout.DEFAULT_W && size.height >= NoteLayout.DEFAULT_H) shouldBe true
            val kd = KumlDiagram(name = "Order", type = DiagramType.BPMN_PROCESS, elements = process.renderableElements())
            val svg = KumlSvgRenderer.toSvg(diagram = kd, layoutResult = lr, theme = PlainTheme())
            SampleOutput.write(filename = "bpmn/notes-process.svg", content = svg)
            svg shouldContain "class=\"kuml-bpmn-text-annotation\""
            svg shouldContain "class=\"kuml-bpmn-association\""
            svg shouldContain "id=\"ann1\""
            svg shouldContain "id=\"ann2\""
            svg shouldContain "&lt;stock&gt; &amp;"
            svg shouldNotContain "<stock>"
            svg shouldNotContain "class=\"kuml-comment-fold\""
        }

        test("collaboration: annotation outside the pools with an association into a pool") {
            val model =
                bpmnModel(name = "Collab") {
                    process(id = "p") {
                        val s = startEvent(name = "Start")
                        val t = task(name = "Work")
                        s flowsTo t
                        textAnnotation(text = "see handbook", t, id = "ann1")
                    }
                    collaboration(id = "c") {
                        pool(name = "Company", id = "poolA") { process("p") }
                        pool(name = "Customer", id = "poolB")
                        textAnnotation(text = "collab note", "p_task_2", id = "ann2")
                    }
                    collaborationDiagram(name = "C", collaborationId = "c")
                }
            val d = model.diagrams.single() as CollaborationDiagram
            val graph = BpmnLayoutBridge.toLayoutGraph(model = model, diagram = d, sizeProvider = BpmnContentSizeProvider(model))
            val lr = engine().layout(graph = graph, hints = LayoutHints.DEFAULT)
            lr.nodes.containsKey(NodeId("ann1")) shouldBe true
            lr.nodes.containsKey(NodeId("ann2")) shouldBe true
            val svg = KumlSvgRenderer.toSvg(model = model, diagram = d, layoutResult = lr, theme = PlainTheme())
            SampleOutput.write(filename = "bpmn/notes-collaboration.svg", content = svg)
            svg shouldContain "id=\"ann1\""
            svg shouldContain "id=\"ann2\""
            svg shouldContain "class=\"kuml-bpmn-association\""
        }
    })
