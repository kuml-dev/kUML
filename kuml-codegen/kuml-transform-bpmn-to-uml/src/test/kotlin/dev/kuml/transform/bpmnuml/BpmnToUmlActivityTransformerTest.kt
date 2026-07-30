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
            bpmnModel(name = "Test") {
                process(id = "proc1", name = "My Process") {
                    val start = startEvent(name = "Start")
                    val t1 = task(name = "Task One")
                    val gw = gateway(type = GatewayType.EXCLUSIVE, name = "Decision")
                    val t2 = task(name = "Task Two")
                    val t3 = task(name = "Task Three")
                    val end = endEvent(name = "End")
                    sequenceFlow(from = start, to = t1)
                    sequenceFlow(from = t1, to = gw)
                    sequenceFlow(from = gw, to = t2, condition = "condA")
                    sequenceFlow(from = gw, to = t3, condition = "condB")
                    sequenceFlow(from = t2, to = end)
                    sequenceFlow(from = t3, to = end)
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
                bpmnModel(name = "ParTest") {
                    process(id = "p2", name = "Parallel") {
                        val start = startEvent()
                        val fork = gateway(type = GatewayType.PARALLEL)
                        val ta = task(name = "A")
                        val tb = task(name = "B")
                        val join = gateway(type = GatewayType.PARALLEL)
                        val end = endEvent()
                        sequenceFlow(from = start, to = fork)
                        sequenceFlow(from = fork, to = ta)
                        sequenceFlow(from = fork, to = tb)
                        sequenceFlow(from = ta, to = join)
                        sequenceFlow(from = tb, to = join)
                        sequenceFlow(from = join, to = end)
                    }
                }.processes.first()
            val model = BpmnToUmlActivityMapper.map(proc)
            model.nodes.count { it.kind == UmlActivityNodeKind.FORK } shouldBe 1
            model.nodes.count { it.kind == UmlActivityNodeKind.JOIN } shouldBe 1
        }

        test("inclusive gateway maps to DECISION with inclusive metadata") {
            val proc =
                bpmnModel(name = "InclusiveTest") {
                    process(id = "p3", name = "Inclusive") {
                        val start = startEvent()
                        val gw = gateway(type = GatewayType.INCLUSIVE)
                        val ta = task(name = "A")
                        val tb = task(name = "B")
                        val end = endEvent()
                        sequenceFlow(from = start, to = gw)
                        sequenceFlow(from = gw, to = ta)
                        sequenceFlow(from = gw, to = tb)
                        sequenceFlow(from = ta, to = end)
                        sequenceFlow(from = tb, to = end)
                    }
                }.processes.first()
            val model = BpmnToUmlActivityMapper.map(proc)
            val decision = model.nodes.first { it.kind == UmlActivityNodeKind.DECISION }
            decision shouldNotBe null
            (decision.metadata["bpmn.gatewayType"] as? KumlMetaValue.Text)?.value shouldBe "INCLUSIVE"
        }

        test("emitted script is non-empty and contains activityDiagram(") {
            val proc = sampleProcess()
            val result = transformer.transform(source = proc, ctx = ctx)
            (result is TransformResult.Success) shouldBe true
            val success = result as TransformResult.Success
            val content = success.output.first().content
            content.shouldContain("activityDiagram(")
        }

        test("XOR gateway that both splits AND joins is split into DECISION and MERGE nodes") {
            val proc =
                bpmnModel(name = "MixedGW") {
                    process(id = "p4", name = "Mixed") {
                        val s1 = startEvent()
                        val s2 = startEvent()
                        val gw = gateway(type = GatewayType.EXCLUSIVE, name = "XOR Mixed")
                        val ta = task(name = "A")
                        val tb = task(name = "B")
                        // gw has 2 incoming and 2 outgoing → MIXED
                        sequenceFlow(from = s1, to = gw)
                        sequenceFlow(from = s2, to = gw)
                        sequenceFlow(from = gw, to = ta)
                        sequenceFlow(from = gw, to = tb)
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
            val result = transformer.transform(source = proc, ctx = ctx)
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
                bpmnModel(name = "LaneTest") {
                    process(id = "laneProc", name = "Lane Process") {
                        val start = startEvent(name = "Start")
                        val t1 = task(name = "Review").also { reviewTaskId = it }
                        val t2 = task(name = "Approve").also { approveTaskId = it }
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = t1)
                        sequenceFlow(from = t1, to = t2)
                        sequenceFlow(from = t2, to = end)
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

            val model = BpmnToUmlActivityMapper.map(process = proc, lanes = listOf(lane1, lane2))

            val reviewNode = model.nodes.first { it.id == reviewTaskId }
            val approveNode = model.nodes.first { it.id == approveTaskId }

            (reviewNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Reviewer"
            (approveNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Approver"
        }

        test("nodes without lane assignment have no uml.partition metadata") {
            val proc = sampleProcess()
            // No lanes supplied → no uml.partition entries
            val model = BpmnToUmlActivityMapper.map(process = proc, lanes = emptyList())
            model.nodes.forEach { node ->
                node.metadata["uml.partition"] shouldBe null
            }
        }

        test("nested child lane membership is recorded (innermost lane name wins)") {
            var subTaskId = ""
            val proc =
                bpmnModel(name = "NestedLaneTest") {
                    process(id = "nlProc", name = "Nested Lane Process") {
                        val start = startEvent(name = "Start")
                        val t1 = task(name = "Sub Task").also { subTaskId = it }
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = t1)
                        sequenceFlow(from = t1, to = end)
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

            val model = BpmnToUmlActivityMapper.map(process = proc, lanes = listOf(parentLane))

            val subTaskNode = model.nodes.first { it.id == subTaskId }
            // innermost lane (child) wins
            (subTaskNode.metadata["uml.partition"] as? KumlMetaValue.Text)?.value shouldBe "Inner Team"
        }

        test("intermediate event maps to ACTION with bpmn.eventPosition metadata") {
            var intermediateId = ""
            val proc =
                bpmnModel(name = "IntermediateTest") {
                    process(id = "iProc", name = "Intermediate") {
                        val start = startEvent(name = "Start")
                        val intermediate =
                            intermediateEvent(
                                name = "Receive Payment",
                                definition = EventDefinition.MESSAGE,
                            ).also { intermediateId = it }
                        val end = endEvent(name = "End")
                        sequenceFlow(from = start, to = intermediate)
                        sequenceFlow(from = intermediate, to = end)
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
                bpmnModel(name = "TerminateTest") {
                    process(id = "tProc", name = "Terminate") {
                        val start = startEvent(name = "Start")
                        val terminateEnd =
                            endEvent(name = "Terminate", definition = EventDefinition.TERMINATE)
                                .also { terminateEndId = it }
                        sequenceFlow(from = start, to = terminateEnd)
                    }
                }.processes.first()

            val model = BpmnToUmlActivityMapper.map(proc)

            val termNode = model.nodes.first { it.id == terminateEndId }
            termNode.kind shouldBe UmlActivityNodeKind.ACTIVITY_FINAL
        }

        test("typed end event (non-terminate, non-none) maps to FLOW_FINAL") {
            var msgEndId = ""
            val proc =
                bpmnModel(name = "FlowFinalTest") {
                    process(id = "ffProc", name = "FlowFinal") {
                        val start = startEvent(name = "Start")
                        val msgEnd =
                            endEvent(name = "Send Notification", definition = EventDefinition.MESSAGE)
                                .also { msgEndId = it }
                        sequenceFlow(from = start, to = msgEnd)
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
