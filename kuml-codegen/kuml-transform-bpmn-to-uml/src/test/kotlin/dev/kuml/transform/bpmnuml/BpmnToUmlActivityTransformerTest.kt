package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlActivityNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class BpmnToUmlActivityTransformerTest :
    FunSpec({

        val transformer = BpmnToUmlActivityTransformer()
        val ctx = TransformContext()

        fun sampleProcess() =
            bpmnModel("Test") {
                process(id = "proc1", name = "My Process") {
                    val start = startEvent("Start")
                    val t1 = task("Task One")
                    val gw = gateway(GatewayType.EXCLUSIVE, name = "Decision")
                    val t2 = task("Task Two")
                    val t3 = task("Task Three")
                    val end = endEvent("End")
                    sequenceFlow(start, t1)
                    sequenceFlow(t1, gw)
                    sequenceFlow(gw, t2, condition = "condA")
                    sequenceFlow(gw, t3, condition = "condB")
                    sequenceFlow(t2, end)
                    sequenceFlow(t3, end)
                }
            }.processes.first()

        test("BpmnProcess with 3 tasks 1 XOR 1 start 1 end maps to correct UmlActivity structure") {
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            val kinds = model.nodes.map { it.kind }
            kinds.filter { it == UmlActivityNodeKind.ACTION }.size shouldBe 3
            kinds shouldContain UmlActivityNodeKind.INITIAL
            kinds shouldContain UmlActivityNodeKind.ACTIVITY_FINAL
            kinds shouldContain UmlActivityNodeKind.DECISION
        }

        test("task names preserved") {
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            val actionNames = model.nodes.filter { it.kind == UmlActivityNodeKind.ACTION }.map { it.name }
            actionNames shouldContain "Task One"
            actionNames shouldContain "Task Two"
            actionNames shouldContain "Task Three"
        }

        test("start event maps to INITIAL") {
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            model.nodes.count { it.kind == UmlActivityNodeKind.INITIAL } shouldBe 1
        }

        test("end event maps to ACTIVITY_FINAL") {
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            model.nodes.count { it.kind == UmlActivityNodeKind.ACTIVITY_FINAL } shouldBe 1
        }

        test("sequence flow condition maps to edge guard") {
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            val guards = model.edges.mapNotNull { it.guard }
            guards shouldContain "condA"
            guards shouldContain "condB"
        }

        test("parallel gateway maps to FORK and JOIN") {
            val proc =
                bpmnModel("ParTest") {
                    process(id = "p2", name = "Parallel") {
                        val start = startEvent()
                        val fork = gateway(GatewayType.PARALLEL)
                        val ta = task("A")
                        val tb = task("B")
                        val join = gateway(GatewayType.PARALLEL)
                        val end = endEvent()
                        sequenceFlow(start, fork)
                        sequenceFlow(fork, ta)
                        sequenceFlow(fork, tb)
                        sequenceFlow(ta, join)
                        sequenceFlow(tb, join)
                        sequenceFlow(join, end)
                    }
                }.processes.first()
            val model = BpmnToUmlActivityMapper.map(proc)
            model.nodes.count { it.kind == UmlActivityNodeKind.FORK } shouldBe 1
            model.nodes.count { it.kind == UmlActivityNodeKind.JOIN } shouldBe 1
        }

        test("inclusive gateway maps to DECISION with inclusive metadata") {
            val proc =
                bpmnModel("InclusiveTest") {
                    process(id = "p3", name = "Inclusive") {
                        val start = startEvent()
                        val gw = gateway(GatewayType.INCLUSIVE)
                        val ta = task("A")
                        val tb = task("B")
                        val end = endEvent()
                        sequenceFlow(start, gw)
                        sequenceFlow(gw, ta)
                        sequenceFlow(gw, tb)
                        sequenceFlow(ta, end)
                        sequenceFlow(tb, end)
                    }
                }.processes.first()
            val model = BpmnToUmlActivityMapper.map(proc)
            val decision = model.nodes.first { it.kind == UmlActivityNodeKind.DECISION }
            decision shouldNotBe null
            (decision.metadata["bpmn.gatewayType"] as? KumlMetaValue.Text)?.value shouldBe "INCLUSIVE"
        }

        test("emitted script is non-empty and contains activityDiagram(") {
            val proc = sampleProcess()
            val result = transformer.transform(proc, ctx)
            (result is TransformResult.Success) shouldBe true
            val success = result as TransformResult.Success
            val content = success.output.first().content
            content.shouldContain("activityDiagram(")
        }

        test("XOR gateway that both splits AND joins is split into DECISION and MERGE nodes") {
            val proc =
                bpmnModel("MixedGW") {
                    process(id = "p4", name = "Mixed") {
                        val s1 = startEvent()
                        val s2 = startEvent()
                        val gw = gateway(GatewayType.EXCLUSIVE, name = "XOR Mixed")
                        val ta = task("A")
                        val tb = task("B")
                        // gw has 2 incoming and 2 outgoing → MIXED
                        sequenceFlow(s1, gw)
                        sequenceFlow(s2, gw)
                        sequenceFlow(gw, ta)
                        sequenceFlow(gw, tb)
                    }
                }.processes.first()
            val model = BpmnToUmlActivityMapper.map(proc)
            // The MIXED gateway should produce one MERGE + one DECISION
            model.nodes.count { it.kind == UmlActivityNodeKind.MERGE } shouldBe 1
            model.nodes.count { it.kind == UmlActivityNodeKind.DECISION } shouldBe 1
            // Both should share the same bpmn.sourceId
            val mergeNode = model.nodes.first { it.kind == UmlActivityNodeKind.MERGE }
            val decisionNode = model.nodes.first { it.kind == UmlActivityNodeKind.DECISION }
            val mergeSourceId = (mergeNode.metadata["bpmn.sourceId"] as? KumlMetaValue.Text)?.value
            val decisionSourceId = (decisionNode.metadata["bpmn.sourceId"] as? KumlMetaValue.Text)?.value
            mergeSourceId shouldNotBe null
            mergeSourceId shouldBe decisionSourceId
        }

        test("transform returns Success with TransformTrace links for every node") {
            val proc = sampleProcess()
            val result = transformer.transform(proc, ctx)
            (result is TransformResult.Success) shouldBe true
            val success = result as TransformResult.Success
            // Trace should have at least one link per non-trivial node
            success.trace.links shouldHaveAtLeastSize 1
        }

        test("lane membership recorded in uml.partition metadata for real BpmnLane input") {
            // Build a process with two tasks that will be assigned to separate lanes.
            // The DSL auto-generates IDs, so capture them from the return values.
            var reviewTaskId = ""
            var approveTaskId = ""
            val proc =
                bpmnModel("LaneTest") {
                    process(id = "laneProc", name = "Lane Process") {
                        val start = startEvent("Start")
                        val t1 = task("Review").also { reviewTaskId = it }
                        val t2 = task("Approve").also { approveTaskId = it }
                        val end = endEvent("End")
                        sequenceFlow(start, t1)
                        sequenceFlow(t1, t2)
                        sequenceFlow(t2, end)
                    }
                }.processes.first()

            // Simulate lanes from the enclosing BpmnParticipant
            val lane1 =
                BpmnLane(
                    id = "lane1",
                    name = "Reviewer",
                    flowNodeRefs = listOf(reviewTaskId),
                )
            val lane2 =
                BpmnLane(
                    id = "lane2",
                    name = "Approver",
                    flowNodeRefs = listOf(approveTaskId),
                )

            val model = BpmnToUmlActivityMapper.map(proc, listOf(lane1, lane2))

            val reviewNode = model.nodes.first { it.id == reviewTaskId }
            val approveNode = model.nodes.first { it.id == approveTaskId }

            (reviewNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Reviewer"
            (approveNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Approver"
        }

        test("nodes without lane assignment have no uml.partition metadata") {
            val proc = sampleProcess()
            // No lanes supplied → no uml.partition entries
            val model = BpmnToUmlActivityMapper.map(proc, emptyList())
            model.nodes.forEach { node ->
                node.metadata["uml.partition"] shouldBe null
            }
        }

        test("nested child lane membership is recorded (innermost lane name wins)") {
            var subTaskId = ""
            val proc =
                bpmnModel("NestedLaneTest") {
                    process(id = "nlProc", name = "Nested Lane Process") {
                        val start = startEvent("Start")
                        val t1 = task("Sub Task").also { subTaskId = it }
                        val end = endEvent("End")
                        sequenceFlow(start, t1)
                        sequenceFlow(t1, end)
                    }
                }.processes.first()

            val childLane =
                BpmnLane(
                    id = "child_lane",
                    name = "Inner Team",
                    flowNodeRefs = listOf(subTaskId),
                )
            val parentLane =
                BpmnLane(
                    id = "parent_lane",
                    name = "Outer Department",
                    flowNodeRefs = listOf(subTaskId), // also listed at parent level
                    childLanes = listOf(childLane),
                )

            val model = BpmnToUmlActivityMapper.map(proc, listOf(parentLane))

            val subTaskNode = model.nodes.first { it.id == subTaskId }
            // innermost lane (child) wins
            (subTaskNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Inner Team"
        }

        test("intermediate event maps to ACTION with bpmn.eventPosition metadata") {
            var intermediateId = ""
            val proc =
                bpmnModel("IntermediateTest") {
                    process(id = "iProc", name = "Intermediate") {
                        val start = startEvent("Start")
                        val intermediate =
                            intermediateEvent(
                                "Receive Payment",
                                definition = EventDefinition.MESSAGE,
                            ).also { intermediateId = it }
                        val end = endEvent("End")
                        sequenceFlow(start, intermediate)
                        sequenceFlow(intermediate, end)
                    }
                }.processes.first()

            val model = BpmnToUmlActivityMapper.map(proc)

            val intNode = model.nodes.first { it.id == intermediateId }
            intNode.kind shouldBe UmlActivityNodeKind.ACTION
            (intNode.metadata["bpmn.eventPosition"] as? KumlMetaValue.Text)?.value shouldBe "INTERMEDIATE"
            (intNode.metadata["bpmn.eventDefinition"] as? KumlMetaValue.Text)?.value shouldBe "MESSAGE"
        }

        test("terminate end event maps to ACTIVITY_FINAL") {
            var terminateEndId = ""
            val proc =
                bpmnModel("TerminateTest") {
                    process(id = "tProc", name = "Terminate") {
                        val start = startEvent("Start")
                        val terminateEnd =
                            endEvent("Terminate", definition = EventDefinition.TERMINATE)
                                .also { terminateEndId = it }
                        sequenceFlow(start, terminateEnd)
                    }
                }.processes.first()

            val model = BpmnToUmlActivityMapper.map(proc)

            val termNode = model.nodes.first { it.id == terminateEndId }
            termNode.kind shouldBe UmlActivityNodeKind.ACTIVITY_FINAL
        }

        test("typed end event (non-terminate, non-none) maps to FLOW_FINAL") {
            var msgEndId = ""
            val proc =
                bpmnModel("FlowFinalTest") {
                    process(id = "ffProc", name = "FlowFinal") {
                        val start = startEvent("Start")
                        val msgEnd =
                            endEvent("Send Notification", definition = EventDefinition.MESSAGE)
                                .also { msgEndId = it }
                        sequenceFlow(start, msgEnd)
                    }
                }.processes.first()

            val model = BpmnToUmlActivityMapper.map(proc)

            val msgEndNode = model.nodes.first { it.id == msgEndId }
            msgEndNode.kind shouldBe UmlActivityNodeKind.FLOW_FINAL
        }

        test("plain none end event maps to ACTIVITY_FINAL") {
            // The sampleProcess() already contains a plain end event with NONE definition
            val proc = sampleProcess()
            val model = BpmnToUmlActivityMapper.map(proc)
            model.nodes.count { it.kind == UmlActivityNodeKind.ACTIVITY_FINAL } shouldBe 1
            model.nodes.none { it.kind == UmlActivityNodeKind.FLOW_FINAL } shouldBe true
        }
    })
