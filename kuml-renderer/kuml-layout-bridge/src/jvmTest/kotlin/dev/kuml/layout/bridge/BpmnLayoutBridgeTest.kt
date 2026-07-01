package dev.kuml.layout.bridge

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.layout.GroupId
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BpmnLayoutBridgeTest :
    FunSpec({

        test("expanded SubProcess: LayoutGroup emitted and child nodes carry groupId") {
            val innerTask = BpmnTask(id = "inner-t1", name = "Inner Task")
            val innerFlow =
                SequenceFlow(
                    id = "inner-sf1",
                    name = null,
                    sourceRef = "inner-t1",
                    targetRef = "inner-t1", // self-edge — still wired up correctly
                )
            val subProcess =
                BpmnSubProcess(
                    id = "sp1",
                    name = "Expanded SP",
                    expanded = true,
                    flowElements = listOf("inner-t1"),
                    flowElementNodes = listOf(innerTask),
                    innerSequenceFlows = listOf(innerFlow),
                )
            val outerTask = BpmnTask(id = "outer-t1", name = "Outer Task")
            val outerFlow =
                SequenceFlow(
                    id = "outer-sf1",
                    name = null,
                    sourceRef = "outer-t1",
                    targetRef = "sp1",
                )
            val process =
                BpmnProcess(
                    id = "proc1",
                    name = "Main Process",
                    flowNodes = listOf(outerTask, subProcess),
                    sequenceFlows = listOf(outerFlow),
                )
            val diagram =
                ProcessDiagram(
                    name = "Test Diagram",
                    processId = "proc1",
                    elementIds =
                        listOf(
                            "outer-t1",
                            "sp1",
                            "outer-sf1",
                            "inner-t1",
                            "inner-sf1",
                        ),
                )
            val model = BpmnModel(name = "M", processes = listOf(process))

            val graph = BpmnLayoutBridge.toLayoutGraph(model, diagram)

            // There must be exactly one LayoutGroup for the expanded SubProcess.
            graph.groups shouldHaveSize 1
            val group = graph.groups[0]
            group.id shouldBe GroupId("sp1")
            group.layoutAsCompound shouldBe true
            group.padding.top shouldBe 20f

            val nodeIds = graph.nodes.map { it.id.value }.toSet()
            nodeIds shouldNotBe emptySet<String>()

            // outer-t1 must be present as a top-level node (no groupId)
            val outerNode = graph.nodes.find { it.id == NodeId("outer-t1") }
            outerNode shouldNotBe null
            outerNode!!.groupId shouldBe null

            // inner-t1 must be present with groupId pointing to sp1
            val innerNode = graph.nodes.find { it.id == NodeId("inner-t1") }
            innerNode shouldNotBe null
            innerNode!!.groupId shouldBe GroupId("sp1")

            // The expanded SubProcess is represented ONLY by the LayoutGroup
            // (compound) — no phantom node is emitted. Outer SequenceFlows whose
            // sourceRef/targetRef equals "sp1" are still wired into the graph as
            // edges; the ELK graph builder resolves the "sp1" endpoint to the
            // compound group via its groupMap fallback (same convention as C4
            // container/system boundaries). Emitting a 0×0 phantom *inside* the
            // group used to make outer flows pierce straight through the frame.
            val phantomNode = graph.nodes.find { it.id == NodeId("sp1") }
            phantomNode shouldBe null

            // Edges: inner self-edge (inner-sf1) + outer edge (outer-sf1).
            graph.edges shouldHaveSize 2

            // The outer edge must connect outer-t1 → sp1 (resolved to the group).
            val outerEdge = graph.edges.find { it.id.value == "outer-sf1" }
            outerEdge shouldNotBe null
            outerEdge!!.source.nodeId shouldBe NodeId("outer-t1")
            outerEdge.target.nodeId shouldBe NodeId("sp1")

            // The inner self-edge must be present too.
            val innerEdge = graph.edges.find { it.id.value == "inner-sf1" }
            innerEdge shouldNotBe null
            innerEdge!!.source.nodeId shouldBe NodeId("inner-t1")
            innerEdge.target.nodeId shouldBe NodeId("inner-t1")
        }

        test("collapsed SubProcess: no LayoutGroup, treated as regular top-level node") {
            val subProcess =
                BpmnSubProcess(
                    id = "sp-collapsed",
                    name = "Collapsed SP",
                    expanded = false,
                )
            val process =
                BpmnProcess(
                    id = "proc1",
                    name = "Process",
                    flowNodes = listOf(subProcess),
                )
            val diagram =
                ProcessDiagram(
                    name = "View",
                    processId = "proc1",
                    elementIds = listOf("sp-collapsed"),
                )
            val model = BpmnModel(name = "M", processes = listOf(process))

            val graph = BpmnLayoutBridge.toLayoutGraph(model, diagram)

            graph.groups shouldHaveSize 0
            graph.nodes shouldHaveSize 1
            graph.nodes[0].id shouldBe NodeId("sp-collapsed")
            graph.nodes[0].groupId shouldBe null
        }

        test("empty elementIds shows the whole process (DSL diagram() without include())") {
            // Regression: `diagram(name, processId)` without an explicit include()
            // produces a ProcessDiagram with empty elementIds. The bridge must then
            // include ALL process elements (convention "empty = all", as in
            // Sysml2LayoutBridge) — otherwise the rendered diagram is completely empty.
            val t1 = BpmnTask(id = "t1", name = "First")
            val t2 = BpmnTask(id = "t2", name = "Second")
            val flow = SequenceFlow(id = "sf1", name = null, sourceRef = "t1", targetRef = "t2")
            val process =
                BpmnProcess(
                    id = "p",
                    name = "Process",
                    flowNodes = listOf(t1, t2),
                    sequenceFlows = listOf(flow),
                )
            val diagram = ProcessDiagram(name = "View", processId = "p", elementIds = emptyList())
            val model = BpmnModel(name = "M", processes = listOf(process))

            val graph = BpmnLayoutBridge.toLayoutGraph(model, diagram)

            val nodeIds = graph.nodes.map { it.id.value }.toSet()
            nodeIds shouldBe setOf("t1", "t2")
            graph.edges.map { it.id.value }.toSet() shouldBe setOf("sf1")
        }

        test("empty elementIds also expands SubProcess children") {
            // Regression companion: with empty elementIds, the expanded SubProcess
            // child nodes and inner flows must also be included (they were filtered
            // out by `child.id !in diagram.elementIds` before the fix).
            val inner = BpmnTask(id = "inner", name = "Inner")
            val innerFlow = SequenceFlow(id = "isf", name = null, sourceRef = "inner", targetRef = "inner")
            val sp =
                BpmnSubProcess(
                    id = "sp",
                    name = "Expanded",
                    expanded = true,
                    flowElements = listOf("inner"),
                    flowElementNodes = listOf(inner),
                    innerSequenceFlows = listOf(innerFlow),
                )
            val process = BpmnProcess(id = "p", name = "P", flowNodes = listOf(sp))
            val diagram = ProcessDiagram(name = "V", processId = "p", elementIds = emptyList())
            val model = BpmnModel(name = "M", processes = listOf(process))

            val graph = BpmnLayoutBridge.toLayoutGraph(model, diagram)

            graph.groups shouldHaveSize 1
            val innerNode = graph.nodes.find { it.id == NodeId("inner") }
            innerNode shouldNotBe null
            innerNode!!.groupId shouldBe GroupId("sp")
        }

        test("renderableElements flattens expanded SubProcess children") {
            // Regression: the BPMN process SVG renderer indexes elements by
            // BpmnProcess.renderableElements(). Expanded SubProcess inner nodes must
            // be present, or they are laid out but silently dropped at render time.
            val inner = BpmnTask(id = "inner", name = "Inner")
            val innerFlow = SequenceFlow(id = "isf", name = null, sourceRef = "inner", targetRef = "inner")
            val sp =
                BpmnSubProcess(
                    id = "sp",
                    name = "Expanded",
                    expanded = true,
                    flowElements = listOf("inner"),
                    flowElementNodes = listOf(inner),
                    innerSequenceFlows = listOf(innerFlow),
                )
            val outer = BpmnTask(id = "outer", name = "Outer")
            val process = BpmnProcess(id = "p", name = "P", flowNodes = listOf(outer, sp))

            val ids = process.renderableElements().map { it.id }.toSet()
            ids shouldBe setOf("outer", "sp", "inner", "isf")
        }
    })
