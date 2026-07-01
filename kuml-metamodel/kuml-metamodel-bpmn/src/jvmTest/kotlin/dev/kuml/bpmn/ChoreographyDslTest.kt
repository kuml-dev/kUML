package dev.kuml.bpmn

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnChoreography
import dev.kuml.bpmn.model.BpmnLoopType
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographyEvent
import dev.kuml.bpmn.model.ChoreographyGateway
import dev.kuml.bpmn.model.ChoreographyMessageFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests für das BPMN-Choreography-Metamodell und die zugehörige DSL.
 *
 * V3.2.1 — BPMN Choreography: Metamodell und DSL
 */
class ChoreographyDslTest :
    DescribeSpec({

        val json = Json { prettyPrint = false }

        // ── 1. Target-Beispiel aus dem Plan baut fehlerfrei ──────────────────────

        describe("Target-example from the implementation plan") {
            it("builds a choreography with start event, task, end event, and two sequence flows") {
                val model =
                    bpmnModel("Bestellung") {
                        choreography(id = "ch1", name = "Bestellprozess") {
                            val start = startEvent(name = "Bestellung eingegangen")
                            val order =
                                task(
                                    name = "Bestellung aufgeben",
                                    initiatingParticipant = "Käufer",
                                    participants = arrayOf("Käufer", "Händler"),
                                ) {
                                    message(name = "Bestellanfrage", participantRef = "Käufer", isInitiating = true)
                                    message(name = "Bestätigung", participantRef = "Händler", isInitiating = false)
                                }
                            val end = endEvent(name = "Bestellung bestätigt")
                            sequenceFlow(from = start, to = order)
                            sequenceFlow(from = order, to = end)
                        }
                    }

                model.choreographies shouldHaveSize 1
                val ch = model.choreographies[0]
                ch.id shouldBe "ch1"
                ch.name shouldBe "Bestellprozess"
                ch.tasks shouldHaveSize 1
                ch.events shouldHaveSize 2
                ch.sequenceFlows shouldHaveSize 2
                ch.gateways.shouldBeEmpty()
            }
        }

        // ── 2. ChoreographyTask mit zwei Messages ─────────────────────────────────

        describe("ChoreographyTask messages") {
            it("stores one initiating and one non-initiating message") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                name = "Exchange",
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            ) {
                                message(name = "Request", participantRef = "A", isInitiating = true)
                                message(name = "Response", participantRef = "B", isInitiating = false)
                            }
                        }
                    }
                val task = model.choreographies[0].tasks[0]
                task.messageFlows shouldHaveSize 2
                task.messageFlows.count { it.isInitiating } shouldBe 1
                task.messageFlows.first { it.isInitiating }.participantRef shouldBe "A"
                task.messageFlows.first { !it.isInitiating }.participantRef shouldBe "B"
            }
        }

        // ── 3. ID-Determinismus ───────────────────────────────────────────────────

        describe("ID determinism") {
            it("generates sequential task IDs") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                name = "Task 1",
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            )
                            task(
                                name = "Task 2",
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            )
                        }
                    }
                val ch = model.choreographies[0]
                ch.tasks[0].id shouldBe "ch1_task_1"
                ch.tasks[1].id shouldBe "ch1_task_2"
            }

            it("generates sequential sequence flow IDs") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            val s = startEvent()
                            val t =
                                task(
                                    initiatingParticipant = "A",
                                    participants = arrayOf("A", "B"),
                                )
                            val e = endEvent()
                            sequenceFlow(from = s, to = t)
                            sequenceFlow(from = t, to = e)
                        }
                    }
                val ch = model.choreographies[0]
                ch.sequenceFlows[0].id shouldBe "ch1_flow_1"
                ch.sequenceFlows[1].id shouldBe "ch1_flow_2"
            }

            it("generates start and end event IDs with positional prefixes") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            startEvent(name = "Start")
                            endEvent(name = "End")
                        }
                    }
                val ch = model.choreographies[0]
                ch.events[0].id shouldBe "ch1_start_1"
                ch.events[0].position shouldBe EventPosition.START
                ch.events[1].id shouldBe "ch1_end_2"
                ch.events[1].position shouldBe EventPosition.END
            }

            it("generates message IDs scoped to the task") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            ) {
                                message(participantRef = "A", isInitiating = true)
                                message(participantRef = "B", isInitiating = false)
                            }
                        }
                    }
                val task = model.choreographies[0].tasks[0]
                task.messageFlows[0].id shouldBe "ch1_task_1_msg_1"
                task.messageFlows[1].id shouldBe "ch1_task_1_msg_2"
            }

            it("auto-generates choreography ID when not specified") {
                val model =
                    bpmnModel("M") {
                        choreography {
                            startEvent()
                        }
                    }
                model.choreographies[0].id shouldBe "choreography_1"
            }
        }

        // ── 4. require-Verletzungen ────────────────────────────────────────────────

        describe("ChoreographyTask construction invariants") {
            it("throws when participants count != 2 (too few)") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A"),
                    )
                }.message!!.contains("exactly two participants")
            }

            it("throws when participants count != 2 (too many)") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B", "C"),
                    )
                }
            }

            it("throws when initiatingParticipant is not in participants") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "X",
                        participants = listOf("A", "B"),
                    )
                }.message!!.contains("initiatingParticipant")
            }

            it("throws when two messages are both initiating") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        messageFlows =
                            listOf(
                                ChoreographyMessageFlow(id = "m1", participantRef = "A", isInitiating = true),
                                ChoreographyMessageFlow(id = "m2", participantRef = "A", isInitiating = true),
                            ),
                    )
                }.message!!.contains("at most one initiating message")
            }

            it("throws when isMultiInstance=true but loopType is NONE") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        isMultiInstance = true,
                        loopType = BpmnLoopType.NONE,
                    )
                }.message!!.contains("isMultiInstance=true requires loopType")
            }

            it("throws when isMultiInstance=true but loopType is STANDARD") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        isMultiInstance = true,
                        loopType = BpmnLoopType.STANDARD,
                    )
                }.message!!.contains("isMultiInstance=true requires loopType")
            }

            it("throws when isMultiInstance=true but loopType is null") {
                shouldThrow<IllegalArgumentException> {
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        isMultiInstance = true,
                        loopType = null,
                    )
                }.message!!.contains("isMultiInstance=true requires loopType")
            }

            it("allows isMultiInstance=true with loopType MULTI_INSTANCE_PARALLEL") {
                val task =
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        isMultiInstance = true,
                        loopType = BpmnLoopType.MULTI_INSTANCE_PARALLEL,
                    )
                task.isMultiInstance shouldBe true
                task.loopType shouldBe BpmnLoopType.MULTI_INSTANCE_PARALLEL
            }

            it("allows isMultiInstance=true with loopType MULTI_INSTANCE_SEQUENTIAL") {
                val task =
                    ChoreographyTask(
                        id = "t1",
                        initiatingParticipant = "A",
                        participants = listOf("A", "B"),
                        isMultiInstance = true,
                        loopType = BpmnLoopType.MULTI_INSTANCE_SEQUENTIAL,
                    )
                task.isMultiInstance shouldBe true
                task.loopType shouldBe BpmnLoopType.MULTI_INSTANCE_SEQUENTIAL
            }
        }

        // ── 5. Gateway + bedingte SequenceFlows ───────────────────────────────────

        describe("Gateway and conditional sequence flows") {
            it("stores gateway type and conditional flows") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            val start = startEvent()
                            val gw = gateway(GatewayType.EXCLUSIVE, name = "Route?")
                            val taskA =
                                task(
                                    name = "Path A",
                                    initiatingParticipant = "A",
                                    participants = arrayOf("A", "B"),
                                )
                            val taskB =
                                task(
                                    name = "Path B",
                                    initiatingParticipant = "A",
                                    participants = arrayOf("A", "B"),
                                )
                            sequenceFlow(from = start, to = gw)
                            sequenceFlow(from = gw, to = taskA, condition = "priority == HIGH")
                            sequenceFlow(from = gw, to = taskB, condition = "priority != HIGH")
                        }
                    }
                val ch = model.choreographies[0]
                ch.gateways shouldHaveSize 1
                ch.gateways[0].type shouldBe GatewayType.EXCLUSIVE
                ch.gateways[0].name shouldBe "Route?"
                ch.sequenceFlows shouldHaveSize 3
                val conditional = ch.sequenceFlows.filter { it.condition != null }
                conditional shouldHaveSize 2
                conditional.any { it.condition == "priority == HIGH" } shouldBe true
                conditional.any { it.condition == "priority != HIGH" } shouldBe true
            }
        }

        // ── 6. Loop-Marker ────────────────────────────────────────────────────────

        describe("Loop type and isMultiInstance") {
            it("stores loopType MULTI_INSTANCE_PARALLEL and isMultiInstance = true") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                name = "Multi",
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            ) {
                                isMultiInstance = true
                                loopType = BpmnLoopType.MULTI_INSTANCE_PARALLEL
                            }
                        }
                    }
                val task = model.choreographies[0].tasks[0]
                task.isMultiInstance shouldBe true
                task.loopType shouldBe BpmnLoopType.MULTI_INSTANCE_PARALLEL
            }

            it("stores loopType STANDARD") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            ) {
                                loopType = BpmnLoopType.STANDARD
                            }
                        }
                    }
                model.choreographies[0].tasks[0].loopType shouldBe BpmnLoopType.STANDARD
            }

            it("defaults loopType to null and isMultiInstance to false") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            )
                        }
                    }
                val task = model.choreographies[0].tasks[0]
                task.loopType.shouldBeNull()
                task.isMultiInstance shouldBe false
            }
        }

        // ── 7. ChoreographyDiagram ────────────────────────────────────────────────

        describe("ChoreographyDiagram") {
            it("without block produces empty elementIds") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") { startEvent() }
                        choreographyDiagram("View", choreographyId = "ch1")
                    }
                model.diagrams shouldHaveSize 1
                val diag = model.diagrams[0].shouldBeInstanceOf<ChoreographyDiagram>()
                diag.choreographyId shouldBe "ch1"
                diag.elementIds.shouldBeEmpty()
            }

            it("with include() fills elementIds") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") { startEvent() }
                        choreographyDiagram("View", choreographyId = "ch1") {
                            include("ch1_start_1", "ch1_task_1")
                        }
                    }
                val diag = model.diagrams[0].shouldBeInstanceOf<ChoreographyDiagram>()
                diag.elementIds shouldBe listOf("ch1_start_1", "ch1_task_1")
            }

            it("multiple choreographyDiagrams appear in model.diagrams") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") { startEvent() }
                        choreographyDiagram("View A", choreographyId = "ch1")
                        choreographyDiagram("View B", choreographyId = "ch1")
                    }
                model.diagrams shouldHaveSize 2
                model.diagrams.all { it is ChoreographyDiagram } shouldBe true
            }
        }

        // ── 8. BpmnModel.elementById ──────────────────────────────────────────────

        describe("BpmnModel.elementById for choreographies") {
            it("finds choreography by ID") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") { startEvent() }
                    }
                model.elementById("ch1").shouldNotBeNull().shouldBeInstanceOf<BpmnChoreography>()
            }

            it("finds task within choreography") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            )
                        }
                    }
                model.elementById("ch1_task_1").shouldNotBeNull().shouldBeInstanceOf<ChoreographyTask>()
            }

            it("finds gateway within choreography") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            gateway(GatewayType.PARALLEL)
                        }
                    }
                model.elementById("ch1_gw_1").shouldNotBeNull().shouldBeInstanceOf<ChoreographyGateway>()
            }

            it("finds event within choreography") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            startEvent(name = "Start")
                        }
                    }
                model.elementById("ch1_start_1").shouldNotBeNull().shouldBeInstanceOf<ChoreographyEvent>()
            }

            it("finds message flow nested inside task") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") {
                            task(
                                initiatingParticipant = "A",
                                participants = arrayOf("A", "B"),
                            ) {
                                message(participantRef = "A", isInitiating = true)
                            }
                        }
                    }
                model.elementById("ch1_task_1_msg_1").shouldNotBeNull().shouldBeInstanceOf<ChoreographyMessageFlow>()
            }

            it("returns null for unknown ID") {
                val model =
                    bpmnModel("M") {
                        choreography(id = "ch1") { startEvent() }
                    }
                model.elementById("nonexistent").shouldBeNull()
            }
        }

        // ── 9. JSON Serialization Roundtrip ───────────────────────────────────────

        describe("JSON serialization roundtrip") {
            it("round-trips BpmnChoreography via JSON") {
                val choreography =
                    BpmnChoreography(
                        id = "ch1",
                        name = "Test Choreography",
                        tasks =
                            listOf(
                                ChoreographyTask(
                                    id = "t1",
                                    name = "Interact",
                                    initiatingParticipant = "A",
                                    participants = listOf("A", "B"),
                                    messageFlows =
                                        listOf(
                                            ChoreographyMessageFlow(
                                                id = "m1",
                                                name = "Req",
                                                participantRef = "A",
                                                isInitiating = true,
                                            ),
                                        ),
                                ),
                            ),
                        events =
                            listOf(
                                ChoreographyEvent(id = "ev1", name = "Start", position = EventPosition.START),
                                ChoreographyEvent(id = "ev2", name = "End", position = EventPosition.END),
                            ),
                    )
                val decoded = json.decodeFromString<BpmnChoreography>(json.encodeToString(choreography))
                decoded shouldBe choreography
                decoded.tasks[0].messageFlows[0].isInitiating shouldBe true
            }

            it("round-trips full BpmnModel with choreographies via JSON") {
                val model =
                    bpmnModel("Full Model") {
                        choreography(id = "ch1", name = "Order Choreography") {
                            val start = startEvent(name = "Start")
                            val task =
                                task(
                                    name = "Place Order",
                                    initiatingParticipant = "Buyer",
                                    participants = arrayOf("Buyer", "Seller"),
                                ) {
                                    message(name = "Request", participantRef = "Buyer", isInitiating = true)
                                    message(name = "Confirm", participantRef = "Seller", isInitiating = false)
                                }
                            val end = endEvent(name = "End")
                            sequenceFlow(from = start, to = task)
                            sequenceFlow(from = task, to = end)
                        }
                        choreographyDiagram("Order View", choreographyId = "ch1")
                    }

                val encoded = json.encodeToString(model)
                val decoded = json.decodeFromString<dev.kuml.bpmn.model.BpmnModel>(encoded)

                decoded.name shouldBe "Full Model"
                decoded.choreographies shouldHaveSize 1
                decoded.choreographies[0].id shouldBe "ch1"
                decoded.choreographies[0].tasks shouldHaveSize 1
                decoded.choreographies[0].tasks[0].messageFlows shouldHaveSize 2
                decoded.diagrams shouldHaveSize 1
                decoded.diagrams[0].shouldBeInstanceOf<ChoreographyDiagram>()
            }

            it("round-trips ChoreographyDiagram via JSON") {
                val diag =
                    ChoreographyDiagram(
                        name = "Test View",
                        choreographyId = "ch1",
                        elementIds = listOf("ch1_start_1", "ch1_task_1"),
                    )
                val decoded = json.decodeFromString<ChoreographyDiagram>(json.encodeToString(diag))
                decoded shouldBe diag
            }
        }
    })
