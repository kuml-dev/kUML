package dev.kuml.bpmn.collaboration

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.MessageFlow
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
 * Tests für das BPMN-Collaboration-Metamodell und die zugehörige DSL.
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
class BpmnCollaborationTest :
    DescribeSpec({

        val json = Json { prettyPrint = false }

        // ── BpmnCollaboration construction + JSON roundtrip ───────────────────────

        describe("BpmnCollaboration") {
            it("constructs with default values") {
                val collab = BpmnCollaboration(id = "collab1")
                collab.id shouldBe "collab1"
                collab.name.shouldBeNull()
                collab.participants.shouldBeEmpty()
                collab.messageFlows.shouldBeEmpty()
            }

            it("round-trips via JSON with name and participants") {
                val collab =
                    BpmnCollaboration(
                        id = "collab1",
                        name = "Order Collaboration",
                        participants =
                            listOf(
                                BpmnParticipant(id = "buyer", name = "Buyer"),
                                BpmnParticipant(id = "seller", name = "Seller"),
                            ),
                    )
                val decoded = json.decodeFromString<BpmnCollaboration>(json.encodeToString(collab))
                decoded shouldBe collab
            }

            it("round-trips via JSON with message flows") {
                val collab =
                    BpmnCollaboration(
                        id = "collab1",
                        messageFlows =
                            listOf(
                                MessageFlow(id = "mf1", sourceRef = "task1", targetRef = "task2"),
                            ),
                    )
                val decoded = json.decodeFromString<BpmnCollaboration>(json.encodeToString(collab))
                decoded.messageFlows shouldHaveSize 1
                decoded.messageFlows[0].id shouldBe "mf1"
            }
        }

        // ── BpmnParticipant ───────────────────────────────────────────────────────

        describe("BpmnParticipant") {
            it("white-box pool has processRef set") {
                val p =
                    BpmnParticipant(
                        id = "p1",
                        name = "Customer",
                        processRef = "proc1",
                        horizontal = true,
                    )
                p.processRef shouldBe "proc1"
                p.horizontal shouldBe true
            }

            it("black-box pool has processRef = null") {
                val p = BpmnParticipant(id = "p2", name = "External System")
                p.processRef.shouldBeNull()
            }

            it("round-trips via JSON with lanes") {
                val p =
                    BpmnParticipant(
                        id = "p3",
                        name = "Sales",
                        lanes =
                            listOf(
                                BpmnLane(id = "lane1", name = "Pre-Sales"),
                                BpmnLane(id = "lane2", name = "Post-Sales"),
                            ),
                    )
                val decoded = json.decodeFromString<BpmnParticipant>(json.encodeToString(p))
                decoded shouldBe p
                decoded.lanes shouldHaveSize 2
            }

            it("vertical pool has horizontal = false") {
                val p = BpmnParticipant(id = "p4", horizontal = false)
                val decoded = json.decodeFromString<BpmnParticipant>(json.encodeToString(p))
                decoded.horizontal shouldBe false
            }
        }

        // ── BpmnLane ──────────────────────────────────────────────────────────────

        describe("BpmnLane") {
            it("stores flowNodeRefs") {
                val lane =
                    BpmnLane(
                        id = "lane1",
                        name = "Procurement",
                        flowNodeRefs = listOf("task1", "task2"),
                    )
                lane.flowNodeRefs shouldBe listOf("task1", "task2")
            }

            it("supports nested childLanes") {
                val child1 = BpmnLane(id = "child1", name = "Sub-Lane A")
                val child2 = BpmnLane(id = "child2", name = "Sub-Lane B")
                val parent = BpmnLane(id = "parent", name = "Parent Lane", childLanes = listOf(child1, child2))
                parent.childLanes shouldHaveSize 2
                parent.childLanes[0].id shouldBe "child1"
            }

            it("round-trips via JSON with nested childLanes") {
                val child = BpmnLane(id = "child", name = "Inner", flowNodeRefs = listOf("t1"))
                val parent = BpmnLane(id = "parent", name = "Outer", childLanes = listOf(child))
                val decoded = json.decodeFromString<BpmnLane>(json.encodeToString(parent))
                decoded shouldBe parent
                decoded.childLanes[0].flowNodeRefs shouldBe listOf("t1")
            }
        }

        // ── MessageFlow ───────────────────────────────────────────────────────────

        describe("MessageFlow") {
            it("stores sourceRef and targetRef") {
                val mf = MessageFlow(id = "mf1", sourceRef = "pool1", targetRef = "pool2")
                mf.sourceRef shouldBe "pool1"
                mf.targetRef shouldBe "pool2"
            }

            it("round-trips via JSON with name") {
                val mf =
                    MessageFlow(
                        id = "mf1",
                        name = "Order Request",
                        sourceRef = "buyer",
                        targetRef = "seller",
                    )
                val decoded = json.decodeFromString<MessageFlow>(json.encodeToString(mf))
                decoded shouldBe mf
                decoded.name shouldBe "Order Request"
            }
        }

        // ── CollaborationDiagram ──────────────────────────────────────────────────

        describe("CollaborationDiagram") {
            it("round-trips via JSON with collaborationId") {
                val diag =
                    CollaborationDiagram(
                        name = "Order Flow View",
                        collaborationId = "collab1",
                        elementIds = listOf("buyer", "seller", "mf1"),
                    )
                val decoded = json.decodeFromString<CollaborationDiagram>(json.encodeToString(diag))
                decoded shouldBe diag
            }

            it("defaults to empty elementIds") {
                val diag = CollaborationDiagram(name = "D", collaborationId = "c1")
                diag.elementIds.shouldBeEmpty()
            }
        }

        // ── BpmnModel with collaborations ─────────────────────────────────────────

        describe("BpmnModel with collaborations") {
            it("stores collaborations alongside processes") {
                val model =
                    BpmnModel(
                        name = "Order System",
                        processes = listOf(BpmnProcess(id = "proc1")),
                        collaborations =
                            listOf(
                                BpmnCollaboration(
                                    id = "collab1",
                                    participants = listOf(BpmnParticipant(id = "buyer", processRef = "proc1")),
                                ),
                            ),
                    )
                model.collaborations shouldHaveSize 1
                model.collaborations[0].id shouldBe "collab1"
            }

            it("elementById finds collaboration by ID") {
                val collab = BpmnCollaboration(id = "collab1", name = "Main Collab")
                val model = BpmnModel(name = "M", collaborations = listOf(collab))
                model.elementById("collab1").shouldNotBeNull().shouldBeInstanceOf<BpmnCollaboration>()
            }

            it("elementById finds participant within collaboration") {
                val participant = BpmnParticipant(id = "buyer", name = "Buyer")
                val collab = BpmnCollaboration(id = "collab1", participants = listOf(participant))
                val model = BpmnModel(name = "M", collaborations = listOf(collab))
                model.elementById("buyer").shouldNotBeNull().shouldBeInstanceOf<BpmnParticipant>()
            }

            it("elementById finds message flow within collaboration") {
                val mf = MessageFlow(id = "mf1", sourceRef = "a", targetRef = "b")
                val collab = BpmnCollaboration(id = "c1", messageFlows = listOf(mf))
                val model = BpmnModel(name = "M", collaborations = listOf(collab))
                model.elementById("mf1").shouldNotBeNull().shouldBeInstanceOf<MessageFlow>()
            }

            it("elementById finds lane within collaboration participant") {
                val lane = BpmnLane(id = "lane1", name = "Sales")
                val p = BpmnParticipant(id = "p1", lanes = listOf(lane))
                val collab = BpmnCollaboration(id = "c1", participants = listOf(p))
                val model = BpmnModel(name = "M", collaborations = listOf(collab))
                model.elementById("lane1").shouldNotBeNull().shouldBeInstanceOf<BpmnLane>()
            }

            it("elementById returns null for unknown ID") {
                val model = BpmnModel(name = "M")
                model.elementById("nonexistent").shouldBeNull()
            }
        }

        // ── DSL: collaboration {} ─────────────────────────────────────────────────

        describe("DSL: collaboration { pool {} }") {
            it("builds a collaboration with pools via DSL") {
                val model =
                    bpmnModel("Order Collab") {
                        collaboration(name = "Order Flow", id = "collab1") {
                            pool("Buyer", id = "buyer")
                            pool("Seller", id = "seller")
                        }
                    }
                model.collaborations shouldHaveSize 1
                val collab = model.collaborations[0]
                collab.name shouldBe "Order Flow"
                collab.participants shouldHaveSize 2
                collab.participants[0].id shouldBe "buyer"
                collab.participants[1].name shouldBe "Seller"
            }

            it("DSL pool with lanes") {
                val model =
                    bpmnModel("M") {
                        collaboration(id = "c1") {
                            pool("Sales Dept", id = "sales") {
                                lane("Pre-Sales")
                                lane("Post-Sales")
                            }
                        }
                    }
                val pool = model.collaborations[0].participants[0]
                pool.id shouldBe "sales"
                pool.lanes shouldHaveSize 2
                pool.lanes[0].name shouldBe "Pre-Sales"
                pool.lanes[1].name shouldBe "Post-Sales"
            }

            it("DSL lane with contains() assigns flowNodeRefs") {
                // Note: ProcessBuilder auto-generates IDs; we capture the returned ID
                var capturedTaskId = ""
                val model =
                    bpmnModel("M") {
                        process(id = "proc1") {
                            capturedTaskId = task("Review")
                        }
                        collaboration(id = "c1") {
                            pool("Pool A", id = "pa") {
                                process("proc1")
                                lane("Sales") {
                                    contains(capturedTaskId)
                                }
                            }
                        }
                    }
                val lane = model.collaborations[0].participants[0].lanes[0]
                lane.flowNodeRefs shouldBe listOf(capturedTaskId)
            }

            it("DSL blackBoxPool() creates participant without processRef") {
                val model =
                    bpmnModel("M") {
                        collaboration(id = "c1") {
                            blackBoxPool("External Partner", id = "ext")
                        }
                    }
                val pool = model.collaborations[0].participants[0]
                pool.id shouldBe "ext"
                pool.name shouldBe "External Partner"
                pool.processRef.shouldBeNull()
            }

            it("DSL messageFlow(from, to) creates MessageFlow in collaboration") {
                val model =
                    bpmnModel("M") {
                        collaboration(id = "c1") {
                            val buyer = blackBoxPool("Buyer", id = "buyer")
                            val seller = blackBoxPool("Seller", id = "seller")
                            messageFlow(from = buyer, to = seller, name = "Order")
                        }
                    }
                val collab = model.collaborations[0]
                collab.messageFlows shouldHaveSize 1
                collab.messageFlows[0].sourceRef shouldBe "buyer"
                collab.messageFlows[0].targetRef shouldBe "seller"
                collab.messageFlows[0].name shouldBe "Order"
            }

            it("DSL collaborationDiagram() creates a CollaborationDiagram in model.diagrams") {
                val model =
                    bpmnModel("M") {
                        collaboration(id = "c1") {
                            blackBoxPool("P1", id = "p1")
                        }
                        collaborationDiagram("Collab View", collaborationId = "c1") {
                            include("p1")
                        }
                    }
                model.diagrams shouldHaveSize 1
                val diag = model.diagrams[0]
                diag.shouldBeInstanceOf<CollaborationDiagram>()
                diag.collaborationId shouldBe "c1"
                diag.elementIds shouldBe listOf("p1")
            }

            it("DSL auto-generates IDs when not specified") {
                val model =
                    bpmnModel("M") {
                        collaboration {
                            pool("A")
                            pool("B")
                            blackBoxPool("C")
                        }
                    }
                val collab = model.collaborations[0]
                collab.id shouldBe "collab_1"
                collab.participants[0].id shouldBe "pool_1"
                collab.participants[1].id shouldBe "pool_2"
                collab.participants[2].id shouldBe "pool_3"
            }

            it("DSL pool with nested lanes") {
                val model =
                    bpmnModel("M") {
                        collaboration(id = "c1") {
                            pool("Big Pool", id = "pool1") {
                                lane("Outer") {
                                    lane("Inner A")
                                    lane("Inner B")
                                }
                            }
                        }
                    }
                val pool = model.collaborations[0].participants[0]
                val outerLane = pool.lanes[0]
                outerLane.name shouldBe "Outer"
                outerLane.childLanes shouldHaveSize 2
                outerLane.childLanes[0].name shouldBe "Inner A"
            }

            it("DSL process() in pool sets processRef") {
                val model =
                    bpmnModel("M") {
                        process(id = "myProc") { }
                        collaboration(id = "c1") {
                            pool("White Box", id = "wb") {
                                process("myProc")
                            }
                        }
                    }
                val pool = model.collaborations[0].participants[0]
                pool.processRef shouldBe "myProc"
            }

            it("DSL processRef via task id roundtrips through BpmnModel") {
                val task = BpmnTask(id = "t1", name = "Do Something")
                val proc = BpmnProcess(id = "proc1", flowNodes = listOf(task))
                val lane = BpmnLane(id = "lane1", flowNodeRefs = listOf("t1"))
                val participant = BpmnParticipant(id = "p1", processRef = "proc1", lanes = listOf(lane))
                val collab = BpmnCollaboration(id = "c1", participants = listOf(participant))
                val model = BpmnModel(name = "M", processes = listOf(proc), collaborations = listOf(collab))

                // Task is found via process, not collaboration
                model.elementById("t1").shouldNotBeNull().shouldBeInstanceOf<BpmnTask>()
                // Lane is found via collaboration
                model.elementById("lane1").shouldNotBeNull().shouldBeInstanceOf<BpmnLane>()
            }
        }
    })
