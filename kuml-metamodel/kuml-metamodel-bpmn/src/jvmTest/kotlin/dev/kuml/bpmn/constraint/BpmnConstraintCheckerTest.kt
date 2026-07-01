package dev.kuml.bpmn.constraint

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyMessageFlow
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventBehaviour
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.SequenceFlow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * V3.1.6 — Tests for [BpmnConstraintChecker].
 *
 * Covers rules 1–8 from the checker's KDoc.
 */
class BpmnConstraintCheckerTest :
    FunSpec({

        val checker = BpmnConstraintChecker()

        // ── Helper: minimal valid process with start + end + sequence flow ──────

        fun validProcess(id: String = "p1"): BpmnProcess {
            val start = BpmnEvent(id = "${id}_start", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val end = BpmnEvent(id = "${id}_end", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flow = SequenceFlow(id = "${id}_flow1", sourceRef = "${id}_start", targetRef = "${id}_end")
            return BpmnProcess(id = id, name = "Valid Process $id", flowNodes = listOf(start, end), sequenceFlows = listOf(flow))
        }

        // ── Rule 1: Missing StartEvent → WARNING ─────────────────────────────

        test("process without StartEvent produces WARNING") {
            val end = BpmnEvent(id = "e1", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val model =
                BpmnModel(
                    name = "NoStart",
                    processes =
                        listOf(BpmnProcess(id = "p1", name = "NoStart", flowNodes = listOf(end))),
                )
            val violations = checker.check(model)
            violations.any { it.severity == ViolationSeverity.WARNING && it.message.contains("no StartEvent") } shouldBe true
        }

        // ── Rule 2: Missing EndEvent → WARNING ───────────────────────────────

        test("process without EndEvent produces WARNING") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val model =
                BpmnModel(
                    name = "NoEnd",
                    processes =
                        listOf(BpmnProcess(id = "p1", name = "NoEnd", flowNodes = listOf(start))),
                )
            val violations = checker.check(model)
            violations.any { it.severity == ViolationSeverity.WARNING && it.message.contains("no EndEvent") } shouldBe true
        }

        // ── Rule 3: SequenceFlow with unknown sourceRef → ERROR ──────────────

        test("sequenceFlow with unknown sourceRef produces ERROR") {
            val end = BpmnEvent(id = "e1", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flow = SequenceFlow(id = "f1", sourceRef = "NONEXISTENT", targetRef = "e1")
            val model =
                BpmnModel(
                    name = "BadSource",
                    processes =
                        listOf(BpmnProcess(id = "p1", name = "P", flowNodes = listOf(end), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("source") && it.message.contains("NONEXISTENT") } shouldBe true
        }

        // ── Rule 4: SequenceFlow with unknown targetRef → ERROR ──────────────

        test("sequenceFlow with unknown targetRef produces ERROR") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val flow = SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "NOWHERE")
            val model =
                BpmnModel(
                    name = "BadTarget",
                    processes =
                        listOf(BpmnProcess(id = "p1", name = "P", flowNodes = listOf(start), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("target") && it.message.contains("NOWHERE") } shouldBe true
        }

        // ── Rule 5: XOR gateway multiple outgoing, no defaultFlow → WARNING ──

        test("XOR gateway with multiple outgoing flows and no defaultFlow produces WARNING") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val gw = BpmnGateway(id = "gw1", name = "Split", gatewayType = GatewayType.EXCLUSIVE, defaultFlow = null)
            val endA = BpmnEvent(id = "eA", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val endB = BpmnEvent(id = "eB", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flows =
                listOf(
                    SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    SequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "eA"),
                    SequenceFlow(id = "f3", sourceRef = "gw1", targetRef = "eB"),
                )
            val model =
                BpmnModel(
                    name = "GwWarning",
                    processes =
                        listOf(BpmnProcess(id = "p1", flowNodes = listOf(start, gw, endA, endB), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.any { it.severity == ViolationSeverity.WARNING && it.message.contains("defaultFlow") } shouldBe true
        }

        // ── Rule 5 negative: XOR gateway with defaultFlow → no gateway WARNING

        test("XOR gateway with defaultFlow set does not produce defaultFlow WARNING") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val gw =
                BpmnGateway(
                    id = "gw1",
                    name = "Split",
                    gatewayType = GatewayType.EXCLUSIVE,
                    defaultFlow = "f2",
                )
            val endA = BpmnEvent(id = "eA", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val endB = BpmnEvent(id = "eB", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flows =
                listOf(
                    SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    SequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "eA"),
                    SequenceFlow(id = "f3", sourceRef = "gw1", targetRef = "eB"),
                )
            val model =
                BpmnModel(
                    name = "GwOk",
                    processes =
                        listOf(BpmnProcess(id = "p1", flowNodes = listOf(start, gw, endA, endB), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.none { it.message.contains("defaultFlow") } shouldBe true
        }

        // ── Rule 5 INCLUSIVE gateway: same rule applies ───────────────────────

        test("INCLUSIVE gateway with multiple outgoing flows and no defaultFlow produces WARNING") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.INCLUSIVE, defaultFlow = null)
            val endA = BpmnEvent(id = "eA", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val endB = BpmnEvent(id = "eB", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flows =
                listOf(
                    SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    SequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "eA"),
                    SequenceFlow(id = "f3", sourceRef = "gw1", targetRef = "eB"),
                )
            val model =
                BpmnModel(
                    name = "InclusiveGwWarn",
                    processes =
                        listOf(BpmnProcess(id = "p1", flowNodes = listOf(start, gw, endA, endB), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.any { it.severity == ViolationSeverity.WARNING && it.message.contains("defaultFlow") } shouldBe true
        }

        // ── Rule 6: Boundary event with invalid attachedToRef → ERROR ────────

        test("boundary event with invalid attachedToRef produces ERROR") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val end = BpmnEvent(id = "e1", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val boundary =
                BpmnEvent(
                    id = "b1",
                    name = "Timeout",
                    position = EventPosition.INTERMEDIATE,
                    definition = EventDefinition.TIMER,
                    behaviour = EventBehaviour.CATCHING,
                    attachedToRef = "NONEXISTENT_TASK",
                )
            val flow = SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "e1")
            val model =
                BpmnModel(
                    name = "BadBoundary",
                    processes =
                        listOf(
                            BpmnProcess(
                                id = "p1",
                                flowNodes = listOf(start, end, boundary),
                                sequenceFlows = listOf(flow),
                            ),
                        ),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("attachedToRef") && it.message.contains("NONEXISTENT_TASK") } shouldBe true
        }

        // ── Rule 7: MessageFlow with same sourceRef and targetRef → ERROR ─────

        test("messageFlow with identical source and target produces ERROR") {
            val collab =
                BpmnCollaboration(
                    id = "c1",
                    messageFlows =
                        listOf(
                            MessageFlow(id = "mf1", sourceRef = "pool_A", targetRef = "pool_A"),
                        ),
                )
            val model = BpmnModel(name = "SelfMsgFlow", collaborations = listOf(collab))
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("source and target are identical") } shouldBe true
        }

        test("messageFlow with different source and target produces no ERROR for rule 7") {
            val collab =
                BpmnCollaboration(
                    id = "c1",
                    messageFlows =
                        listOf(
                            MessageFlow(id = "mf1", sourceRef = "pool_A", targetRef = "pool_B"),
                        ),
                )
            val model = BpmnModel(name = "ValidMsgFlow", collaborations = listOf(collab))
            val violations = checker.check(model)
            violations.none { it.message.contains("source and target are identical") } shouldBe true
        }

        // ── Rule 8: Participant.processRef to nonexistent process → ERROR ─────

        test("participant processRef pointing to nonexistent process produces ERROR") {
            val collab =
                BpmnCollaboration(
                    id = "c1",
                    participants =
                        listOf(
                            BpmnParticipant(id = "part1", name = "Pool A", processRef = "NONEXISTENT_PROCESS"),
                        ),
                )
            val model = BpmnModel(name = "BadProcessRef", collaborations = listOf(collab))
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("processRef") && it.message.contains("NONEXISTENT_PROCESS") } shouldBe true
        }

        test("participant processRef pointing to existing process produces no rule-8 ERROR") {
            val process = validProcess("proc_A")
            val collab =
                BpmnCollaboration(
                    id = "c1",
                    participants =
                        listOf(
                            BpmnParticipant(id = "part1", name = "Pool A", processRef = "proc_A"),
                        ),
                )
            val model = BpmnModel(name = "ValidProcessRef", processes = listOf(process), collaborations = listOf(collab))
            val violations = checker.check(model)
            violations.none { it.message.contains("processRef") } shouldBe true
        }

        // ── Happy path: fully valid model → no violations ─────────────────────

        test("fully valid model with start, end, and sequence flow produces no violations") {
            val model =
                bpmnModel("HappyPath") {
                    process(id = "p1", name = "Simple") {
                        val s = startEvent("Start")
                        val e = endEvent("End")
                        sequenceFlow(s, e)
                    }
                    diagram("Simple", "p1")
                }
            val violations = checker.check(model)
            violations.shouldBeEmpty()
        }

        // ── Empty model (no processes, no collaborations) → no violations ─────

        test("empty model with no processes produces no violations and no crash") {
            val model = BpmnModel(name = "Empty")
            val violations = checker.check(model)
            violations.shouldBeEmpty()
        }

        // ── Multiple violations in one model ──────────────────────────────────

        test("model with multiple violations returns all of them") {
            // Process: no start, no end, invalid sequence flow
            val orphanFlow = SequenceFlow(id = "f1", sourceRef = "MISSING_A", targetRef = "MISSING_B")
            val process = BpmnProcess(id = "p1", name = "Broken", sequenceFlows = listOf(orphanFlow))
            val model = BpmnModel(name = "MultiViolation", processes = listOf(process))
            val violations = checker.check(model)
            // Expect: WARNING(no start), WARNING(no end), ERROR(bad source), ERROR(bad target) = at least 4
            violations.size shouldBe 4
            violations.count { it.severity == ViolationSeverity.WARNING } shouldBe 2
            violations.count { it.severity == ViolationSeverity.ERROR } shouldBe 2
        }

        // ── PARALLEL gateway: rule 5 does NOT apply ───────────────────────────

        test("PARALLEL gateway with multiple outgoing flows produces no rule-5 WARNING") {
            val start = BpmnEvent(id = "s1", position = EventPosition.START, behaviour = EventBehaviour.CATCHING)
            val gw = BpmnGateway(id = "gw1", gatewayType = GatewayType.PARALLEL, defaultFlow = null)
            val endA = BpmnEvent(id = "eA", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val endB = BpmnEvent(id = "eB", position = EventPosition.END, behaviour = EventBehaviour.THROWING)
            val flows =
                listOf(
                    SequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    SequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "eA"),
                    SequenceFlow(id = "f3", sourceRef = "gw1", targetRef = "eB"),
                )
            val model =
                BpmnModel(
                    name = "ParallelGwOk",
                    processes =
                        listOf(BpmnProcess(id = "p1", flowNodes = listOf(start, gw, endA, endB), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.none { it.message.contains("defaultFlow") } shouldBe true
        }

        // ── DSL-built model: order fulfillment ────────────────────────────────

        test("order fulfillment model built via DSL has no constraint errors") {
            val model =
                bpmnModel("OrderFulfillment") {
                    process(id = "order_proc", name = "Order Process") {
                        val s = startEvent("Received")
                        val check = task("Check Stock")
                        val gw = gateway(GatewayType.EXCLUSIVE, "In Stock?")
                        val ship = task("Ship")
                        val reject = task("Reject")
                        val eOk = endEvent("Shipped")
                        val eFail = endEvent("Rejected")
                        sequenceFlow(s, check)
                        sequenceFlow(check, gw)
                        sequenceFlow(gw, ship, condition = "stock > 0")
                        sequenceFlow(gw, reject, condition = "stock == 0")
                        sequenceFlow(ship, eOk)
                        sequenceFlow(reject, eFail)
                    }
                    diagram("Order", "order_proc")
                }
            val violations = checker.check(model)
            // XOR gateway has 2 outgoing but no defaultFlow → 1 WARNING only
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.shouldBeEmpty()
        }

        // ── Choreography helper: minimal valid choreography (start -> task -> end) ──

        fun validChoreography(id: String = "ch1"): BpmnChoreography {
            val start = ChoreographyEvent(id = "${id}_start", position = EventPosition.START)
            val end = ChoreographyEvent(id = "${id}_end", position = EventPosition.END)
            val task =
                ChoreographyTask(
                    id = "${id}_task1",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                    messageFlows = listOf(ChoreographyMessageFlow(id = "${id}_mf1", participantRef = "Buyer", isInitiating = true)),
                )
            return BpmnChoreography(
                id = id,
                tasks = listOf(task),
                events = listOf(start, end),
                sequenceFlows =
                    listOf(
                        ChoreographySequenceFlow(id = "${id}_f1", sourceRef = "${id}_start", targetRef = "${id}_task1"),
                        ChoreographySequenceFlow(id = "${id}_f2", sourceRef = "${id}_task1", targetRef = "${id}_end"),
                    ),
            )
        }

        // ── Rule 9: Choreography SequenceFlow with unknown source/target → ERROR ──

        test("choreography sequenceFlow with unknown sourceRef produces ERROR") {
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val flow = ChoreographySequenceFlow(id = "f1", sourceRef = "NONEXISTENT", targetRef = "e1")
            val model =
                BpmnModel(
                    name = "BadChorSource",
                    choreographies = listOf(BpmnChoreography(id = "c1", events = listOf(end), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("source") && it.message.contains("NONEXISTENT") } shouldBe true
        }

        test("choreography sequenceFlow with unknown targetRef produces ERROR") {
            val start = ChoreographyEvent(id = "s1", position = EventPosition.START)
            val flow = ChoreographySequenceFlow(id = "f1", sourceRef = "s1", targetRef = "NOWHERE")
            val model =
                BpmnModel(
                    name = "BadChorTarget",
                    choreographies = listOf(BpmnChoreography(id = "c1", events = listOf(start), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("target") && it.message.contains("NOWHERE") } shouldBe true
        }

        // ── Rule 10: no free StartEvent → WARNING ─────────────────────────────

        test("choreography without a free StartEvent produces WARNING") {
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val model =
                BpmnModel(
                    name = "NoChorStart",
                    choreographies = listOf(BpmnChoreography(id = "c1", events = listOf(end))),
                )
            val violations = checker.check(model)
            violations.any {
                it.severity == ViolationSeverity.WARNING && it.message.contains("no StartEvent")
            } shouldBe true
        }

        // ── Rule 11: EndEvent with outgoing flow → ERROR ──────────────────────

        test("choreography EndEvent with outgoing flow produces ERROR") {
            val start = ChoreographyEvent(id = "s1", position = EventPosition.START)
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val flows =
                listOf(
                    ChoreographySequenceFlow(id = "f1", sourceRef = "s1", targetRef = "e1"),
                    ChoreographySequenceFlow(id = "f2", sourceRef = "e1", targetRef = "s1"),
                )
            val model =
                BpmnModel(
                    name = "BadChorEnd",
                    choreographies = listOf(BpmnChoreography(id = "c1", events = listOf(start, end), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("outgoing flows") } shouldBe true
        }

        // ── Rule 12: isolated (unreachable) element → WARNING ─────────────────

        test("choreography element unreachable from StartEvent produces WARNING") {
            val start = ChoreographyEvent(id = "s1", position = EventPosition.START)
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val isolated = ChoreographyGateway(id = "gw_isolated", type = GatewayType.PARALLEL)
            val flow = ChoreographySequenceFlow(id = "f1", sourceRef = "s1", targetRef = "e1")
            val model =
                BpmnModel(
                    name = "IsolatedChorNode",
                    choreographies =
                        listOf(
                            BpmnChoreography(
                                id = "c1",
                                events = listOf(start, end),
                                gateways = listOf(isolated),
                                sequenceFlows = listOf(flow),
                            ),
                        ),
                )
            val violations = checker.check(model)
            violations.any {
                it.severity == ViolationSeverity.WARNING && it.elementId == "gw_isolated" && it.message.contains("not reachable")
            } shouldBe true
        }

        // ── Rule 13: condition on flow not leaving EXCLUSIVE/INCLUSIVE gateway → WARNING ──

        test("condition on flow leaving PARALLEL gateway produces WARNING") {
            val start = ChoreographyEvent(id = "s1", position = EventPosition.START)
            val gw = ChoreographyGateway(id = "gw1", type = GatewayType.PARALLEL)
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val flows =
                listOf(
                    ChoreographySequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    ChoreographySequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "e1", condition = "x > 0"),
                )
            val model =
                BpmnModel(
                    name = "BadCondition",
                    choreographies =
                        listOf(BpmnChoreography(id = "c1", events = listOf(start, end), gateways = listOf(gw), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.any {
                it.severity == ViolationSeverity.WARNING && it.message.contains("does not leave an EXCLUSIVE/INCLUSIVE gateway")
            } shouldBe true
        }

        test("condition on flow leaving EXCLUSIVE gateway produces no rule-13 WARNING") {
            val start = ChoreographyEvent(id = "s1", position = EventPosition.START)
            val gw = ChoreographyGateway(id = "gw1", type = GatewayType.EXCLUSIVE)
            val end = ChoreographyEvent(id = "e1", position = EventPosition.END)
            val flows =
                listOf(
                    ChoreographySequenceFlow(id = "f1", sourceRef = "s1", targetRef = "gw1"),
                    ChoreographySequenceFlow(id = "f2", sourceRef = "gw1", targetRef = "e1", condition = "x > 0"),
                )
            val model =
                BpmnModel(
                    name = "GoodCondition",
                    choreographies =
                        listOf(BpmnChoreography(id = "c1", events = listOf(start, end), gateways = listOf(gw), sequenceFlows = flows)),
                )
            val violations = checker.check(model)
            violations.none { it.message.contains("does not leave an EXCLUSIVE/INCLUSIVE gateway") } shouldBe true
        }

        // ── Rule 14: initiating message participantRef mismatch → ERROR ──────

        test("initiating message with mismatched participantRef produces ERROR") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                    messageFlows = listOf(ChoreographyMessageFlow(id = "mf1", participantRef = "Seller", isInitiating = true)),
                )
            val model =
                BpmnModel(
                    name = "BadInitiator",
                    choreographies = listOf(BpmnChoreography(id = "c1", tasks = listOf(task))),
                )
            val violations = checker.check(model)
            val errors = violations.filter { it.severity == ViolationSeverity.ERROR }
            errors.any { it.message.contains("does not match") } shouldBe true
        }

        test("initiating message with matching participantRef produces no rule-14 ERROR") {
            val task =
                ChoreographyTask(
                    id = "t1",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                    messageFlows = listOf(ChoreographyMessageFlow(id = "mf1", participantRef = "Buyer", isInitiating = true)),
                )
            val model =
                BpmnModel(
                    name = "GoodInitiator",
                    choreographies = listOf(BpmnChoreography(id = "c1", tasks = listOf(task))),
                )
            val violations = checker.check(model)
            violations.none { it.message.contains("does not match") } shouldBe true
        }

        // ── Rule 15: broken participant-band continuity between tasks → WARNING ──

        test("connected choreography tasks with no shared participant produces WARNING") {
            val taskA =
                ChoreographyTask(
                    id = "tA",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                )
            val taskB =
                ChoreographyTask(
                    id = "tB",
                    initiatingParticipant = "Shipper",
                    participants = listOf("Shipper", "Warehouse"),
                )
            val flow = ChoreographySequenceFlow(id = "f1", sourceRef = "tA", targetRef = "tB")
            val model =
                BpmnModel(
                    name = "BrokenBand",
                    choreographies = listOf(BpmnChoreography(id = "c1", tasks = listOf(taskA, taskB), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            violations.any {
                it.severity == ViolationSeverity.WARNING && it.message.contains("band continuity broken")
            } shouldBe true
        }

        test("connected choreography tasks sharing a participant produce no rule-15 WARNING") {
            val taskA =
                ChoreographyTask(
                    id = "tA",
                    initiatingParticipant = "Buyer",
                    participants = listOf("Buyer", "Seller"),
                )
            val taskB =
                ChoreographyTask(
                    id = "tB",
                    initiatingParticipant = "Seller",
                    participants = listOf("Seller", "Shipper"),
                )
            val flow = ChoreographySequenceFlow(id = "f1", sourceRef = "tA", targetRef = "tB")
            val model =
                BpmnModel(
                    name = "GoodBand",
                    choreographies = listOf(BpmnChoreography(id = "c1", tasks = listOf(taskA, taskB), sequenceFlows = listOf(flow))),
                )
            val violations = checker.check(model)
            violations.none { it.message.contains("band continuity broken") } shouldBe true
        }

        // ── Happy path: fully valid choreography → no violations ─────────────

        test("fully valid choreography produces no violations") {
            val model = BpmnModel(name = "HappyChoreography", choreographies = listOf(validChoreography()))
            val violations = checker.check(model)
            violations.shouldBeEmpty()
        }
    })
