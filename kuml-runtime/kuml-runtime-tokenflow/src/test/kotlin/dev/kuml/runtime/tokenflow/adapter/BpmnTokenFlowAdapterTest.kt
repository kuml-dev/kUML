package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.TaskType
import dev.kuml.runtime.tokenflow.GatewayFamily
import dev.kuml.runtime.tokenflow.TokenFlowSeverity
import dev.kuml.runtime.tokenflow.TokenNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BpmnTokenFlowAdapterTest :
    FunSpec({
        test("task/event/gateway map to the expected TokenNodeKind, END_EVENT vs TERMINATE_FINAL distinguished") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent(name = "start")
                        val t = task(name = "t", type = TaskType.USER)
                        val gw = gateway(type = GatewayType.EXCLUSIVE, name = "gw")
                        val endPlain = endEvent(name = "endPlain")
                        val endTerminate = endEvent(name = "endTerminate", definition = dev.kuml.bpmn.model.EventDefinition.TERMINATE)
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = gw)
                        sequenceFlow(from = gw, to = endPlain, condition = "a")
                        sequenceFlow(from = gw, to = endTerminate, default = true)
                    }
                }
            val spec = BpmnTokenFlowAdapter.toSpec(model.processes.single())

            spec.nodes.values
                .first { it.name == "start" }
                .kind shouldBe TokenNodeKind.INITIAL
            spec.nodes.values
                .first { it.name == "t" }
                .kind shouldBe TokenNodeKind.ACTION
            spec.nodes.values
                .first { it.name == "endPlain" }
                .kind shouldBe TokenNodeKind.END_EVENT
            spec.nodes.values
                .first { it.name == "endTerminate" }
                .kind shouldBe TokenNodeKind.TERMINATE_FINAL
            val gwNode = spec.nodes.values.first { it.name == "gw" }
            gwNode.kind shouldBe TokenNodeKind.GATEWAY
            gwNode.gateway?.family shouldBe GatewayFamily.EXCLUSIVE
            gwNode.gateway?.diverging shouldBe true
            gwNode.gateway?.converging shouldBe false
        }

        test(
            "converging/diverging is derived from sequenceFlow-based degree, not BpmnFlowNode.incoming/outgoing (DSL leaves those empty)",
        ) {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val gw = gateway(type = GatewayType.PARALLEL)
                        val a = task()
                        val b = task()
                        val end = endEvent()
                        sequenceFlow(from = start, to = gw)
                        sequenceFlow(from = gw, to = a)
                        sequenceFlow(from = gw, to = b)
                        sequenceFlow(from = a, to = end)
                        sequenceFlow(from = b, to = end)
                    }
                }
            val process = model.processes.single()
            // Sanity: the DSL really does leave incoming/outgoing empty.
            process.flowNodes.all { it.incoming.isEmpty() && it.outgoing.isEmpty() } shouldBe true

            val spec = BpmnTokenFlowAdapter.toSpec(process)
            val gwNode = spec.nodes.values.first { it.kind == dev.kuml.runtime.tokenflow.TokenNodeKind.GATEWAY }
            gwNode.gateway?.diverging shouldBe true
            gwNode.gateway?.converging shouldBe false
        }

        test("MIXED-direction gateway stays a single node with its original id — no synthetic _merge/_decision split") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start1 = startEvent()
                        val start2 = startEvent()
                        val mixed = gateway(type = GatewayType.PARALLEL, name = "mixed")
                        val a = task()
                        val b = task()
                        val end1 = endEvent()
                        val end2 = endEvent()
                        sequenceFlow(from = start1, to = mixed)
                        sequenceFlow(from = start2, to = mixed)
                        sequenceFlow(from = mixed, to = a)
                        sequenceFlow(from = mixed, to = b)
                        sequenceFlow(from = a, to = end1)
                        sequenceFlow(from = b, to = end2)
                    }
                }
            val spec = BpmnTokenFlowAdapter.toSpec(model.processes.single())
            val mixedIds = spec.nodes.keys.filter { it.contains("gw") }
            mixedIds shouldBe
                listOf(
                    model.processes
                        .single()
                        .flowNodes
                        .first { it.name == "mixed" }
                        .id,
                )
            val mixedNode = spec.nodes.getValue(mixedIds.single())
            mixedNode.gateway?.converging shouldBe true
            mixedNode.gateway?.diverging shouldBe true
        }

        test("defaultEdgeId resolves from a SequenceFlow.isDefault=true edge (the DSL/OKF-example path)") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val gw = gateway(type = GatewayType.EXCLUSIVE, name = "gw")
                        val a1 = task()
                        val a2 = task()
                        sequenceFlow(from = start, to = gw)
                        sequenceFlow(from = gw, to = a1, condition = "x")
                        sequenceFlow(from = gw, to = a2, default = true)
                    }
                }
            val process = model.processes.single()
            val spec = BpmnTokenFlowAdapter.toSpec(process)
            val gwNode = spec.nodes.values.first { it.name == "gw" }
            val expectedDefaultEdge = process.sequenceFlows.first { it.isDefault }.id
            gwNode.gateway?.defaultEdgeId shouldBe expectedDefaultEdge
        }

        test("defaultEdgeId resolves from BpmnGateway.defaultFlow when set explicitly on the gateway") {
            // Built directly against the model classes (not the DSL) so the
            // default-flow id can be chosen up front and cross-referenced.
            val process =
                dev.kuml.bpmn.model.BpmnProcess(
                    id = "p",
                    flowNodes =
                        listOf(
                            dev.kuml.bpmn.model
                                .BpmnEvent(id = "start", position = dev.kuml.bpmn.model.EventPosition.START),
                            dev.kuml.bpmn.model
                                .BpmnGateway(id = "gw", gatewayType = GatewayType.EXCLUSIVE, defaultFlow = "flowDefault"),
                            dev.kuml.bpmn.model
                                .BpmnTask(id = "a1"),
                            dev.kuml.bpmn.model
                                .BpmnTask(id = "a2"),
                        ),
                    sequenceFlows =
                        listOf(
                            dev.kuml.bpmn.model
                                .SequenceFlow(id = "flowToGw", sourceRef = "start", targetRef = "gw"),
                            dev.kuml.bpmn.model.SequenceFlow(
                                id = "flowCond",
                                sourceRef = "gw",
                                targetRef = "a1",
                                conditionExpression = "x",
                            ),
                            dev.kuml.bpmn.model
                                .SequenceFlow(id = "flowDefault", sourceRef = "gw", targetRef = "a2"),
                        ),
                )
            val spec = BpmnTokenFlowAdapter.toSpec(process)
            spec.nodes
                .getValue("gw")
                .gateway
                ?.defaultEdgeId shouldBe "flowDefault"
        }

        test("EVENT_BASED / COMPLEX gateways are reported as UNSUPPORTED_GATEWAY ERROR issues") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val gw = gateway(type = GatewayType.EVENT_BASED)
                        val a = task()
                        sequenceFlow(from = start, to = gw)
                        sequenceFlow(from = gw, to = a)
                    }
                }
            val spec = BpmnTokenFlowAdapter.toSpec(model.processes.single())
            spec.validate().any { it.severity == TokenFlowSeverity.ERROR && it.code == "UNSUPPORTED_GATEWAY" } shouldBe true
        }

        // Security review (feature/tokenflow-execution-engine, finding "BPMN sub-process
        // content / boundary events / loop characteristics silently dropped"): these three
        // constructs previously collapsed into a single opaque ACTION node with zero signal —
        // `kuml simulate` would run the happy path only and still report success.

        test("expanded sub-process with inner content -> ERROR UNSUPPORTED_SUBPROCESS_CONTENT") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val sub =
                            subProcess(name = "Review", expanded = true) {
                                startEvent(name = "Inner start")
                                task(name = "Inner task")
                                endEvent(name = "Inner end")
                            }
                        val end = endEvent()
                        sequenceFlow(from = start, to = sub)
                        sequenceFlow(from = sub, to = end)
                    }
                }
            val issues = BpmnTokenFlowAdapter.toSpec(model.processes.single()).validate()
            issues.any { it.severity == TokenFlowSeverity.ERROR && it.code == "UNSUPPORTED_SUBPROCESS_CONTENT" } shouldBe true
        }

        test("collapsed (non-expanded) sub-process has no inner content to lose -> no UNSUPPORTED_SUBPROCESS_CONTENT") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val sub = subProcess(name = "Review", expanded = false)
                        val end = endEvent()
                        sequenceFlow(from = start, to = sub)
                        sequenceFlow(from = sub, to = end)
                    }
                }
            val issues = BpmnTokenFlowAdapter.toSpec(model.processes.single()).validate()
            issues.any { it.code == "UNSUPPORTED_SUBPROCESS_CONTENT" } shouldBe false
        }

        test("task with loopCharacteristics -> ERROR UNSUPPORTED_LOOP_CHARACTERISTICS") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val t = task(name = "Retry") { standardLoop(condition = "attempts < 3") }
                        val end = endEvent()
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = end)
                    }
                }
            val issues = BpmnTokenFlowAdapter.toSpec(model.processes.single()).validate()
            issues.any { it.severity == TokenFlowSeverity.ERROR && it.code == "UNSUPPORTED_LOOP_CHARACTERISTICS" } shouldBe true
        }

        test("task with a boundary event attached (attachedToRef) -> ERROR UNSUPPORTED_BOUNDARY_EVENT") {
            // attachedToRef, not BpmnActivity.boundaryEvents, is what BpmnXmlImporter and
            // BpmnConstraintChecker's own "attachedToRef must exist" rule rely on — this is the
            // path a real (non-DSL-hand-wired) model actually takes.
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val t = task(name = "Review")
                        boundaryEvent(attachedTo = t, definition = dev.kuml.bpmn.model.EventDefinition.TIMER)
                        val end = endEvent()
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = end)
                    }
                }
            val issues = BpmnTokenFlowAdapter.toSpec(model.processes.single()).validate()
            issues.any { it.severity == TokenFlowSeverity.ERROR && it.code == "UNSUPPORTED_BOUNDARY_EVENT" } shouldBe true
        }

        test("task with no boundary event / loop / expanded sub-process -> none of the three new ERRORs fire") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val t = task(name = "Plain")
                        val end = endEvent()
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = end)
                    }
                }
            val issues = BpmnTokenFlowAdapter.toSpec(model.processes.single()).validate()
            issues.any {
                it.code in setOf("UNSUPPORTED_SUBPROCESS_CONTENT", "UNSUPPORTED_LOOP_CHARACTERISTICS", "UNSUPPORTED_BOUNDARY_EVENT")
            } shouldBe false
        }

        test("every TokenFlowNode/Edge id is a verbatim BPMN flow-node/sequence-flow id (BpmnTokenTimelineBuilder compatibility)") {
            val model =
                bpmnModel(name = "m") {
                    process(id = "p") {
                        val start = startEvent()
                        val t = task()
                        val end = endEvent()
                        sequenceFlow(from = start, to = t)
                        sequenceFlow(from = t, to = end)
                    }
                }
            val process = model.processes.single()
            val spec = BpmnTokenFlowAdapter.toSpec(process)
            val flowNodeIds = process.flowNodes.map { it.id }.toSet()
            val seqFlowIds = process.sequenceFlows.map { it.id }.toSet()
            spec.nodes.keys shouldBe flowNodeIds
            spec.edges.map { it.id }.toSet() shouldBe seqFlowIds
        }
    })
