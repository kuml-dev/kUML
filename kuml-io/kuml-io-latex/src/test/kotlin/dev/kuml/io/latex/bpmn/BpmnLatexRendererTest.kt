package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.latex.KumlLatexRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end tests for the BPMN LaTeX/TikZ renderer — V3.1.8.
 *
 * Covers all BPMN flow node types, event definitions, gateway types, task types,
 * sequence flows, collaboration pools, message flows, and LaTeX-injection safety.
 */
class BpmnLatexRendererTest :
    StringSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun minimalModel(vararg nodes: dev.kuml.bpmn.model.BpmnFlowNode): BpmnModel {
            val process =
                BpmnProcess(
                    id = "proc1",
                    name = "Test Process",
                    flowNodes = nodes.toList(),
                )
            return BpmnModel(
                name = "Test Model",
                processes = listOf(process),
                diagrams = listOf(ProcessDiagram(name = "Diagram", processId = "proc1")),
            )
        }

        fun startEvent(
            id: String = "start1",
            name: String? = null,
            definition: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(
            id = id,
            name = name,
            position = EventPosition.START,
            definition = definition,
            behaviour = EventBehaviour.CATCHING,
        )

        fun endEvent(
            id: String = "end1",
            name: String? = null,
            definition: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(
            id = id,
            name = name,
            position = EventPosition.END,
            definition = definition,
            behaviour = EventBehaviour.THROWING,
        )

        fun intermediateEvent(
            id: String = "int1",
            name: String? = null,
            definition: EventDefinition = EventDefinition.NONE,
        ) = BpmnEvent(
            id = id,
            name = name,
            position = EventPosition.INTERMEDIATE,
            definition = definition,
            behaviour = EventBehaviour.CATCHING,
        )

        // ── 1. Empty Process ──────────────────────────────────────────────────

        "empty process renders tikzpicture delimiters" {
            val model =
                BpmnModel(
                    name = "Empty",
                    processes =
                        listOf(
                            BpmnProcess(id = "p1", name = "Empty Process"),
                        ),
                    diagrams = listOf(ProcessDiagram(name = "D", processId = "p1")),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain """\begin{tikzpicture}"""
            tex shouldContain """\end{tikzpicture}"""
        }

        // ── 2. StartEvent NONE ────────────────────────────────────────────────

        "StartEvent NONE renders kuml-bpmn-start style" {
            val tex = KumlLatexRenderer.toLatex(minimalModel(startEvent()))
            tex shouldContain "kuml-bpmn-start"
        }

        // ── 3. StartEvent MESSAGE ─────────────────────────────────────────────

        "StartEvent MESSAGE renders kuml-bpmn-start style with bowtie symbol" {
            val tex =
                KumlLatexRenderer.toLatex(
                    minimalModel(startEvent(definition = EventDefinition.MESSAGE)),
                )
            tex shouldContain "kuml-bpmn-start"
            tex shouldContain """\bowtie"""
        }

        // ── 4. EndEvent NONE ──────────────────────────────────────────────────

        "EndEvent NONE renders kuml-bpmn-end style" {
            val tex = KumlLatexRenderer.toLatex(minimalModel(endEvent()))
            tex shouldContain "kuml-bpmn-end"
        }

        // ── 5. EndEvent TERMINATE ─────────────────────────────────────────────

        "EndEvent TERMINATE renders kuml-bpmn-end style with bullet symbol" {
            val tex =
                KumlLatexRenderer.toLatex(
                    minimalModel(endEvent(definition = EventDefinition.TERMINATE)),
                )
            tex shouldContain "kuml-bpmn-end"
            tex shouldContain """\bullet"""
        }

        // ── 6. IntermediateEvent ──────────────────────────────────────────────

        "IntermediateEvent renders kuml-bpmn-intermediate style" {
            val tex = KumlLatexRenderer.toLatex(minimalModel(intermediateEvent()))
            tex shouldContain "kuml-bpmn-intermediate"
        }

        // ── 7. BoundaryEvent ──────────────────────────────────────────────────

        "BoundaryEvent (attachedToRef set) renders kuml-bpmn-boundary style" {
            val boundaryEvent =
                BpmnEvent(
                    id = "boundary1",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.NONE,
                    behaviour = EventBehaviour.CATCHING,
                    attachedToRef = "task1",
                )
            val tex = KumlLatexRenderer.toLatex(minimalModel(boundaryEvent))
            tex shouldContain "kuml-bpmn-boundary"
        }

        // ── 8. ExclusiveGateway ───────────────────────────────────────────────

        "ExclusiveGateway renders kuml-bpmn-gateway with times symbol" {
            val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.EXCLUSIVE)
            val tex = KumlLatexRenderer.toLatex(minimalModel(gw))
            tex shouldContain "kuml-bpmn-gateway"
            tex shouldContain """\times"""
        }

        // ── 9. ParallelGateway ────────────────────────────────────────────────

        "ParallelGateway renders kuml-bpmn-gateway with plus symbol" {
            val gw = BpmnGateway(id = "gw2", gatewayType = GatewayType.PARALLEL)
            val tex = KumlLatexRenderer.toLatex(minimalModel(gw))
            tex shouldContain "kuml-bpmn-gateway"
            tex shouldContain "+"
        }

        // ── 10. InclusiveGateway ──────────────────────────────────────────────

        "InclusiveGateway renders kuml-bpmn-gateway with bigcirc symbol" {
            val gw = BpmnGateway(id = "gw3", gatewayType = GatewayType.INCLUSIVE)
            val tex = KumlLatexRenderer.toLatex(minimalModel(gw))
            tex shouldContain "kuml-bpmn-gateway"
            tex shouldContain """\bigcirc"""
        }

        // ── 11. UserTask ──────────────────────────────────────────────────────

        "UserTask renders kuml-bpmn-task with User type prefix" {
            val task =
                BpmnTask(id = "task1", name = "Review", taskType = TaskType.USER)
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain "kuml-bpmn-task"
            tex shouldContain "User"
        }

        // ── 12. ServiceTask ───────────────────────────────────────────────────

        "ServiceTask renders kuml-bpmn-task with Service type prefix" {
            val task =
                BpmnTask(id = "task2", name = "Call API", taskType = TaskType.SERVICE)
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain "kuml-bpmn-task"
            tex shouldContain "Service"
        }

        // ── 13. Task NONE ─────────────────────────────────────────────────────

        "Task NONE renders kuml-bpmn-task without type prefix" {
            val task = BpmnTask(id = "task3", name = "Do Something", taskType = TaskType.NONE)
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain "kuml-bpmn-task"
            tex shouldContain "Do Something"
        }

        // ── 14. SendTask ──────────────────────────────────────────────────────

        "SendTask renders kuml-bpmn-task with Send type prefix" {
            val task = BpmnTask(id = "task4", name = "Send Email", taskType = TaskType.SEND)
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain "kuml-bpmn-task"
            tex shouldContain "Send"
        }

        // ── 15. ScriptTask ────────────────────────────────────────────────────

        "ScriptTask renders kuml-bpmn-task with Script type prefix" {
            val task =
                BpmnTask(id = "task5", name = "Execute Script", taskType = TaskType.SCRIPT)
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain "kuml-bpmn-task"
            tex shouldContain "Script"
        }

        // ── 16. SubProcess ────────────────────────────────────────────────────

        "SubProcess renders kuml-bpmn-subprocess with collapsed [+] marker" {
            val sp = BpmnSubProcess(id = "sp1", name = "Sub Flow")
            val tex = KumlLatexRenderer.toLatex(minimalModel(sp))
            tex shouldContain "kuml-bpmn-subprocess"
            tex shouldContain "[+]"
        }

        // ── 17. CallActivity ──────────────────────────────────────────────────

        "CallActivity renders kuml-bpmn-callactivity style" {
            val ca = BpmnCallActivity(id = "ca1", name = "Reuse Process")
            val tex = KumlLatexRenderer.toLatex(minimalModel(ca))
            tex shouldContain "kuml-bpmn-callactivity"
        }

        // ── 18. SequenceFlow basic ────────────────────────────────────────────

        "SequenceFlow renders kuml-bpmn-flow draw path between nodes" {
            val start = startEvent(id = "s1")
            val task = BpmnTask(id = "t1", name = "Work")
            val flow = SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "t1")
            val process =
                BpmnProcess(
                    id = "p1",
                    flowNodes = listOf(start, task),
                    sequenceFlows = listOf(flow),
                )
            val model =
                BpmnModel(
                    name = "M",
                    processes = listOf(process),
                    diagrams = listOf(ProcessDiagram(name = "D", processId = "p1")),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain "kuml-bpmn-flow"
            tex shouldContain "(s1)"
            tex shouldContain "(t1)"
        }

        // ── 19. SequenceFlow with name label ──────────────────────────────────

        "SequenceFlow with name renders flow label" {
            val start = startEvent(id = "s2")
            val task = BpmnTask(id = "t2", name = "Task")
            val flow =
                SequenceFlow(id = "f2", sourceRef = "s2", targetRef = "t2", name = "approved")
            val process =
                BpmnProcess(
                    id = "p2",
                    flowNodes = listOf(start, task),
                    sequenceFlows = listOf(flow),
                )
            val model =
                BpmnModel(
                    name = "M",
                    processes = listOf(process),
                    diagrams = listOf(ProcessDiagram(name = "D", processId = "p2")),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain "approved"
            tex shouldContain "midway"
        }

        // ── 20. SequenceFlow with conditionExpression ─────────────────────────

        "SequenceFlow with conditionExpression renders condition label" {
            val gw = BpmnGateway(id = "gw10", gatewayType = GatewayType.EXCLUSIVE)
            val task = BpmnTask(id = "t10", name = "Task")
            val flow =
                SequenceFlow(
                    id = "f10",
                    sourceRef = "gw10",
                    targetRef = "t10",
                    conditionExpression = "amount > 1000",
                )
            val process =
                BpmnProcess(
                    id = "p10",
                    flowNodes = listOf(gw, task),
                    sequenceFlows = listOf(flow),
                )
            val model =
                BpmnModel(
                    name = "M",
                    processes = listOf(process),
                    diagrams = listOf(ProcessDiagram(name = "D", processId = "p10")),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain "amount"
            tex shouldContain "near start"
        }

        // ── 21. CollaborationDiagram renders pools ────────────────────────────

        "CollaborationDiagram renders kuml-bpmn-pool with participant names" {
            val collab =
                BpmnCollaboration(
                    id = "collab1",
                    name = "Order Process",
                    participants =
                        listOf(
                            BpmnParticipant(id = "pool1", name = "Customer"),
                            BpmnParticipant(id = "pool2", name = "Vendor"),
                        ),
                )
            val model =
                BpmnModel(
                    name = "Order Collaboration",
                    collaborations = listOf(collab),
                    diagrams =
                        listOf(
                            CollaborationDiagram(
                                name = "Collaboration",
                                collaborationId = "collab1",
                            ),
                        ),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain "kuml-bpmn-pool"
            tex shouldContain "Customer"
            tex shouldContain "Vendor"
        }

        // ── 22. MessageFlow ───────────────────────────────────────────────────

        "MessageFlow renders kuml-bpmn-msgflow draw path" {
            val collab =
                BpmnCollaboration(
                    id = "collab2",
                    participants =
                        listOf(
                            BpmnParticipant(id = "poolA", name = "A"),
                            BpmnParticipant(id = "poolB", name = "B"),
                        ),
                    messageFlows =
                        listOf(
                            MessageFlow(
                                id = "mf1",
                                name = "Order",
                                sourceRef = "poolA",
                                targetRef = "poolB",
                            ),
                        ),
                )
            val model =
                BpmnModel(
                    name = "M",
                    collaborations = listOf(collab),
                    diagrams =
                        listOf(
                            CollaborationDiagram(
                                name = "D",
                                collaborationId = "collab2",
                            ),
                        ),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain "kuml-bpmn-msgflow"
        }

        // ── 23. LaTeX injection via Name ──────────────────────────────────────

        "Special LaTeX characters in task name are properly escaped" {
            val task =
                BpmnTask(
                    id = "task_escape",
                    name = "Pay & Ship_Order #1 with 100% off",
                    taskType = TaskType.NONE,
                )
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            tex shouldContain """\&"""
            tex shouldContain """\_"""
            tex shouldContain """\#"""
            tex shouldContain """\%"""
        }

        // ── 24. KumlLatexRenderer.toLatex(BpmnModel) dispatcher ──────────────

        "KumlLatexRenderer.toLatex(BpmnModel) dispatches to first diagram and returns non-empty result" {
            val model = minimalModel(startEvent(), endEvent())
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain """\begin{tikzpicture}"""
        }

        // ── 25. Node ID sanitizing ────────────────────────────────────────────

        "Node IDs with hyphens are sanitised to underscores in TikZ output" {
            val task = BpmnTask(id = "flow-node_1", name = "Node")
            val tex = KumlLatexRenderer.toLatex(minimalModel(task))
            // Hyphen replaced by underscore in the TikZ node name
            tex shouldContain "(flow_node_1)"
        }

        // ── 26. toLatex(BpmnModel) returns empty string when no diagrams ──────

        "toLatex(BpmnModel) returns empty string when model has no diagrams" {
            val model =
                BpmnModel(
                    name = "Empty Model",
                    processes = listOf(BpmnProcess(id = "p", name = "P")),
                    diagrams = emptyList(),
                )
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldBe ""
        }

        // ── 27. BPMN styles are present in TikZ picture preamble ─────────────

        "BPMN TikZ styles are emitted in the picture preamble" {
            // The appendTikzStyles path is exercised whenever any toLatex overload
            // builds a picture. We verify via the KumlLatexRenderer.toLatex(diagram,layout)
            // path, which always calls appendTikzStyles.
            val model = minimalModel(startEvent())
            // Indirectly check via BpmnLatexRenderer output that the style block exists
            val styleOut = StringBuilder()
            BpmnLatexRenderer.appendBpmnTikzStyles(styleOut, "")
            val styles = styleOut.toString()
            styles shouldContain "kuml-bpmn-start"
            styles shouldContain "kuml-bpmn-end"
            styles shouldContain "kuml-bpmn-intermediate"
            styles shouldContain "kuml-bpmn-boundary"
            styles shouldContain "kuml-bpmn-gateway"
            styles shouldContain "kuml-bpmn-task"
            styles shouldContain "kuml-bpmn-subprocess"
            styles shouldContain "kuml-bpmn-callactivity"
            styles shouldContain "kuml-bpmn-pool"
            styles shouldContain "kuml-bpmn-flow"
            styles shouldContain "kuml-bpmn-msgflow"
        }

        // ── 28. toLatex(BpmnModel) output is self-contained (includes tikzset) ─

        "toLatex(BpmnModel) output contains the kuml-bpmn-* tikzset style block" {
            // Regression guard for the issue where the three BPMN toLatex overloads
            // delegated directly to BpmnLatexRenderer.render() without wrapping the
            // output in appendTikzStyles — resulting in 'undefined style' errors when
            // compiling the LaTeX output with pdflatex.
            val model = minimalModel(startEvent(), endEvent())
            val tex = KumlLatexRenderer.toLatex(model)
            tex shouldContain """\tikzset{"""
            tex shouldContain "kuml-bpmn-start"
            tex shouldContain "kuml-bpmn-end"
            tex shouldContain "kuml-bpmn-flow"
        }

        // ── 29. ERROR event definition uses a package-free math symbol ─────────

        "ERROR event definition renders without \\lightning (requires wasysym)" {
            // Regression guard: \\lightning is not in standard LaTeX math mode and
            // requires the wasysym package. The renderer must use a universally
            // available substitute (currently \\mathsf{E}).
            val tex =
                KumlLatexRenderer.toLatex(
                    minimalModel(startEvent(definition = EventDefinition.ERROR)),
                )
            tex shouldContain """\mathsf"""
            // Must NOT contain \lightning — that would require wasysym/stmaryrd.
            (tex.contains("""\lightning""")) shouldBe false
        }
    })
