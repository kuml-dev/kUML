package dev.kuml.bpmn

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnConversation
import dev.kuml.bpmn.model.CallConversation
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ConversationLink
import dev.kuml.bpmn.model.ConversationNode
import dev.kuml.bpmn.model.SubConversation
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
 * Tests für das BPMN-Conversation-Metamodell und die zugehörige DSL.
 *
 * V3.2.3 — BPMN Conversation Diagram: Metamodell und DSL
 */
class ConversationDslTest :
    DescribeSpec({

        val json = Json { prettyPrint = false }

        // ── 1. Grund-Beispiel ─────────────────────────────────────────────────────

        describe("Basic conversation example") {
            it("builds a conversation with participants, nodes, and links") {
                val model =
                    bpmnModel("PdV-Kommunikation") {
                        conversation(id = "conv1", name = "Mitglieder-Kommunikation") {
                            val mitglied = participant("Mitglied")
                            val vorstand = participant("Vorstand")
                            val netzwerk = participant("Netzwerk")
                            val antrag = node("Mitgliedsantrag", mitglied, vorstand)
                            val kampagne = node("Wahlkampagne", vorstand, netzwerk)
                            link(mitglied, antrag)
                            link(vorstand, antrag)
                            link(vorstand, kampagne)
                            link(netzwerk, kampagne)
                        }
                    }

                model.conversations shouldHaveSize 1
                val conv = model.conversations[0]
                conv.id shouldBe "conv1"
                conv.name shouldBe "Mitglieder-Kommunikation"
                conv.participants shouldHaveSize 3
                conv.nodes shouldHaveSize 2
                conv.links shouldHaveSize 4
            }
        }

        // ── 2. ID-Determinismus ───────────────────────────────────────────────────

        describe("ID determinism") {
            it("generates sequential node IDs") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("Node 1", a, b)
                            node("Node 2", a, b)
                        }
                    }
                val conv = model.conversations[0]
                conv.nodes[0].id shouldBe "conv1_node_1"
                conv.nodes[1].id shouldBe "conv1_node_2"
            }

            it("generates sequential link IDs") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            val n = node("N", a, b)
                            link(a, n)
                            link(b, n)
                        }
                    }
                val conv = model.conversations[0]
                conv.links[0].id shouldBe "conv1_link_1"
                conv.links[1].id shouldBe "conv1_link_2"
            }

            it("auto-generates conversation ID when not specified") {
                val model =
                    bpmnModel("M") {
                        conversation {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                    }
                model.conversations[0].id shouldBe "conversation_1"
            }

            it("conversation() returns the stable conversation ID") {
                val model =
                    bpmnModel("M") {
                        val cid =
                            conversation(id = "c1") {
                                val a = participant("A")
                                val b = participant("B")
                                node("N", a, b)
                            }
                        cid shouldBe "c1"
                    }
                model.conversations[0].id shouldBe "c1"
            }

            it("node() returns the stable node ID usable in link()") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("Alpha")
                            val b = participant("Beta")
                            val nid = node("MyNode", a, b)
                            nid shouldBe "conv1_node_1"
                            link(a, nid)
                        }
                    }
                val conv = model.conversations[0]
                conv.links[0].conversationNodeRef shouldBe "conv1_node_1"
            }

            it("participant() returns the participant name as ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val name = participant("Customer")
                            name shouldBe "Customer"
                            val b = participant("Vendor")
                            node("Deal", name, b)
                            link(name, "conv1_node_1")
                        }
                    }
                val conv = model.conversations[0]
                conv.links[0].participantRef shouldBe "Customer"
            }
        }

        // ── 3. CallConversation ───────────────────────────────────────────────────

        describe("CallConversation") {
            it("stores calledCollaborationRef") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            callConversation(
                                "External Collab",
                                a,
                                b,
                                calledCollaborationRef = "extCollab1",
                            )
                        }
                    }
                val node = model.conversations[0].nodes[0]
                node.shouldBeInstanceOf<CallConversation>()
                node.calledCollaborationRef shouldBe "extCollab1"
                node.name shouldBe "External Collab"
                node.participants shouldBe listOf("A", "B")
            }

            it("throws when participants < 2") {
                shouldThrow<IllegalArgumentException> {
                    CallConversation(
                        id = "cc1",
                        participants = listOf("OnlyOne"),
                        calledCollaborationRef = "ref",
                    )
                }
            }
        }

        // ── 4. SubConversation ────────────────────────────────────────────────────

        describe("SubConversation") {
            it("stores children from SubConversationBuilder") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            val c = participant("C")
                            subConversation("Top", a, b) {
                                node("Child", a, c)
                            }
                        }
                    }
                val sub = model.conversations[0].nodes[0]
                sub.shouldBeInstanceOf<SubConversation>()
                sub.children shouldHaveSize 1
                sub.children[0].name shouldBe "Child"
            }

            it("supports nested sub-conversations") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            subConversation("Outer", a, b) {
                                subConversation("Inner", a, b)
                            }
                        }
                    }
                val outer = model.conversations[0].nodes[0] as SubConversation
                outer.children[0].shouldBeInstanceOf<SubConversation>()
            }

            it("throws when participants < 2") {
                shouldThrow<IllegalArgumentException> {
                    SubConversation(id = "sc1", participants = listOf("Solo"))
                }
            }
        }

        // ── 5. ConversationNode invariants ────────────────────────────────────────

        describe("ConversationNode construction invariants") {
            it("throws when participants count < 2") {
                shouldThrow<IllegalArgumentException> {
                    ConversationNode(id = "n1", participants = listOf("Only"))
                }.message!!.contains("at least two participants")
            }

            it("allows exactly 2 participants") {
                val node = ConversationNode(id = "n1", participants = listOf("A", "B"))
                node.participants shouldHaveSize 2
            }

            it("allows more than 2 participants") {
                val node = ConversationNode(id = "n1", participants = listOf("A", "B", "C"))
                node.participants shouldHaveSize 3
            }
        }

        // ── 5b. BpmnConversation participant/node-ID collision guard ──────────────

        describe("BpmnConversation participant-name / node-ID disjoint invariant") {
            it("throws when a participant name equals a ConversationNode ID") {
                val collidingNodeId = "conv1_node_1"
                val ex =
                    shouldThrow<IllegalArgumentException> {
                        BpmnConversation(
                            id = "conv1",
                            // Participant name deliberately set to the auto-generated node ID
                            participants = listOf(collidingNodeId, "LegalParticipant"),
                            nodes =
                                listOf(
                                    ConversationNode(
                                        id = collidingNodeId,
                                        participants = listOf(collidingNodeId, "LegalParticipant"),
                                    ),
                                ),
                        )
                    }
                ex.message!!.contains("collide") shouldBe true
                ex.message!!.contains(collidingNodeId) shouldBe true
            }

            it("accepts disjoint participant names and node IDs") {
                val conv =
                    BpmnConversation(
                        id = "conv1",
                        participants = listOf("Alice", "Bob"),
                        nodes =
                            listOf(
                                ConversationNode(id = "conv1_node_1", participants = listOf("Alice", "Bob")),
                            ),
                    )
                conv.participants shouldHaveSize 2
                conv.nodes shouldHaveSize 1
            }
        }

        // ── 6. ConversationDiagram ────────────────────────────────────────────────

        describe("ConversationDiagram") {
            it("without block produces empty elementIds") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                        conversationDiagram("Übersicht", conversationId = "conv1")
                    }
                model.diagrams shouldHaveSize 1
                val diag = model.diagrams[0].shouldBeInstanceOf<ConversationDiagram>()
                diag.conversationId shouldBe "conv1"
                diag.elementIds.shouldBeEmpty()
            }

            it("with include() fills elementIds") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                        conversationDiagram("Übersicht", conversationId = "conv1") {
                            include("A", "conv1_node_1")
                        }
                    }
                val diag = model.diagrams[0].shouldBeInstanceOf<ConversationDiagram>()
                diag.elementIds shouldBe listOf("A", "conv1_node_1")
            }

            it("multiple conversationDiagrams appear in model.diagrams") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                        conversationDiagram("View A", conversationId = "conv1")
                        conversationDiagram("View B", conversationId = "conv1")
                    }
                model.diagrams shouldHaveSize 2
                model.diagrams.all { it is ConversationDiagram } shouldBe true
            }
        }

        // ── 7. BpmnModel.elementById ──────────────────────────────────────────────

        describe("BpmnModel.elementById for conversations") {
            it("finds conversation by ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                    }
                model.elementById("conv1").shouldNotBeNull().shouldBeInstanceOf<BpmnConversation>()
            }

            it("finds conversation node by ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                    }
                model.elementById("conv1_node_1").shouldNotBeNull().shouldBeInstanceOf<ConversationNode>()
            }

            it("finds conversation link by ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            val n = node("N", a, b)
                            link(a, n)
                        }
                    }
                model.elementById("conv1_link_1").shouldNotBeNull().shouldBeInstanceOf<ConversationLink>()
            }

            it("finds child node inside SubConversation by ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            subConversation("Top", a, b) {
                                node("Child", a, b, id = "childNode1")
                            }
                        }
                    }
                model.elementById("childNode1").shouldNotBeNull().shouldBeInstanceOf<ConversationNode>()
            }

            it("returns null for unknown ID") {
                val model =
                    bpmnModel("M") {
                        conversation(id = "conv1") {
                            val a = participant("A")
                            val b = participant("B")
                            node("N", a, b)
                        }
                    }
                model.elementById("nonexistent").shouldBeNull()
            }
        }

        // ── 8. JSON Serialization Roundtrip ───────────────────────────────────────

        describe("JSON serialization roundtrip") {
            it("round-trips BpmnConversation with ConversationNode via JSON") {
                val conversation =
                    BpmnConversation(
                        id = "conv1",
                        name = "Test Conversation",
                        participants = listOf("Alpha", "Beta"),
                        nodes =
                            listOf(
                                ConversationNode(id = "n1", name = "Node 1", participants = listOf("Alpha", "Beta")),
                            ),
                        links =
                            listOf(
                                ConversationLink(id = "l1", participantRef = "Alpha", conversationNodeRef = "n1"),
                            ),
                    )
                val decoded =
                    json.decodeFromString<BpmnConversation>(json.encodeToString(conversation))
                decoded shouldBe conversation
                decoded.nodes[0].name shouldBe "Node 1"
                decoded.links[0].participantRef shouldBe "Alpha"
            }

            it("round-trips BpmnConversation with CallConversation via JSON") {
                val conversation =
                    BpmnConversation(
                        id = "conv1",
                        participants = listOf("A", "B"),
                        nodes =
                            listOf(
                                CallConversation(
                                    id = "cc1",
                                    participants = listOf("A", "B"),
                                    calledCollaborationRef = "extRef",
                                ),
                            ),
                    )
                val decoded =
                    json.decodeFromString<BpmnConversation>(json.encodeToString(conversation))
                val cc = decoded.nodes[0].shouldBeInstanceOf<CallConversation>()
                cc.calledCollaborationRef shouldBe "extRef"
            }

            it("round-trips BpmnConversation with SubConversation via JSON") {
                val conversation =
                    BpmnConversation(
                        id = "conv1",
                        participants = listOf("A", "B"),
                        nodes =
                            listOf(
                                SubConversation(
                                    id = "sc1",
                                    participants = listOf("A", "B"),
                                    children =
                                        listOf(
                                            ConversationNode(id = "child1", participants = listOf("A", "B")),
                                        ),
                                ),
                            ),
                    )
                val decoded =
                    json.decodeFromString<BpmnConversation>(json.encodeToString(conversation))
                val sc = decoded.nodes[0].shouldBeInstanceOf<SubConversation>()
                sc.children shouldHaveSize 1
                sc.children[0].id shouldBe "child1"
            }

            it("round-trips full BpmnModel with conversations via JSON") {
                val model =
                    bpmnModel("Full Model") {
                        conversation(id = "conv1", name = "PdV-Kommunikation") {
                            val m = participant("Mitglied")
                            val v = participant("Vorstand")
                            val n = node("Mitgliedsantrag", m, v)
                            link(m, n)
                            link(v, n)
                        }
                        conversationDiagram("Übersicht", conversationId = "conv1")
                    }

                val encoded = json.encodeToString(model)
                val decoded =
                    json.decodeFromString<dev.kuml.bpmn.model.BpmnModel>(encoded)

                decoded.name shouldBe "Full Model"
                decoded.conversations shouldHaveSize 1
                decoded.conversations[0].id shouldBe "conv1"
                decoded.conversations[0].nodes shouldHaveSize 1
                decoded.conversations[0].links shouldHaveSize 2
                decoded.diagrams shouldHaveSize 1
                decoded.diagrams[0].shouldBeInstanceOf<ConversationDiagram>()
            }

            it("round-trips ConversationDiagram via JSON") {
                val diag =
                    ConversationDiagram(
                        name = "Test View",
                        conversationId = "conv1",
                        elementIds = listOf("A", "conv1_node_1"),
                    )
                val decoded =
                    json.decodeFromString<ConversationDiagram>(json.encodeToString(diag))
                decoded shouldBe diag
            }
        }
    })
