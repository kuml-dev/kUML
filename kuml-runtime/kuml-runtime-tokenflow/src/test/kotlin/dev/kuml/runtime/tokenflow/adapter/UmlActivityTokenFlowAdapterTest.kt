package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.runtime.tokenflow.GatewayFamily
import dev.kuml.runtime.tokenflow.TokenNodeKind
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlActivityNode
import dev.kuml.uml.UmlActivityNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UmlActivityTokenFlowAdapterTest :
    FunSpec({
        fun n(
            id: String,
            kind: UmlActivityNodeKind,
            metadata: Map<String, KumlMetaValue> = emptyMap(),
        ) = UmlActivityNode(id = id, name = id, kind = kind, metadata = metadata)

        test("every UmlActivityNodeKind maps to the expected TokenNodeKind/GatewaySpec") {
            val nodes =
                listOf(
                    n("init", UmlActivityNodeKind.INITIAL),
                    n("act", UmlActivityNodeKind.ACTION),
                    n("fin", UmlActivityNodeKind.ACTIVITY_FINAL),
                    n("ff", UmlActivityNodeKind.FLOW_FINAL),
                    n("obj", UmlActivityNodeKind.OBJECT),
                    n("dec", UmlActivityNodeKind.DECISION),
                    n("merge", UmlActivityNodeKind.MERGE),
                    n("fork", UmlActivityNodeKind.FORK),
                    n("join", UmlActivityNodeKind.JOIN),
                )
            val diagram = KumlDiagram(name = "d", type = DiagramType.ACTIVITY, elements = nodes)
            val spec = UmlActivityTokenFlowAdapter.toSpec(diagram)

            spec.nodes.getValue("init").kind shouldBe TokenNodeKind.INITIAL
            spec.nodes.getValue("act").kind shouldBe TokenNodeKind.ACTION
            spec.nodes.getValue("fin").kind shouldBe TokenNodeKind.TERMINATE_FINAL
            spec.nodes.getValue("ff").kind shouldBe TokenNodeKind.FLOW_FINAL
            spec.nodes.getValue("obj").kind shouldBe TokenNodeKind.OBJECT_NODE

            val dec = spec.nodes.getValue("dec")
            dec.kind shouldBe TokenNodeKind.GATEWAY
            dec.gateway?.family shouldBe GatewayFamily.EXCLUSIVE
            dec.gateway?.diverging shouldBe true
            dec.gateway?.converging shouldBe false

            val merge = spec.nodes.getValue("merge")
            merge.gateway?.family shouldBe GatewayFamily.EXCLUSIVE
            merge.gateway?.converging shouldBe true
            merge.gateway?.diverging shouldBe false

            val fork = spec.nodes.getValue("fork")
            fork.gateway?.family shouldBe GatewayFamily.PARALLEL
            fork.gateway?.diverging shouldBe true

            val join = spec.nodes.getValue("join")
            join.gateway?.family shouldBe GatewayFamily.PARALLEL
            join.gateway?.converging shouldBe true
        }

        test("bpmn.gatewayType=INCLUSIVE metadata upgrades a Decision/Merge to the INCLUSIVE family") {
            val decision = n("dec", UmlActivityNodeKind.DECISION, mapOf("bpmn.gatewayType" to KumlMetaValue.Text("INCLUSIVE")))
            val diagram = KumlDiagram(name = "d", type = DiagramType.ACTIVITY, elements = listOf(decision))
            val spec = UmlActivityTokenFlowAdapter.toSpec(diagram)
            spec.nodes
                .getValue("dec")
                .gateway
                ?.family shouldBe GatewayFamily.INCLUSIVE
        }

        test("edges carry guard and isObjectFlow through unchanged") {
            val a = n("a", UmlActivityNodeKind.ACTION)
            val b = n("b", UmlActivityNodeKind.ACTION)
            val edge = UmlActivityEdge(id = "e1", sourceId = "a", targetId = "b", guard = "cond", isObjectFlow = true)
            val diagram = KumlDiagram(name = "d", type = DiagramType.ACTIVITY, elements = listOf(a, b, edge))
            val spec = UmlActivityTokenFlowAdapter.toSpec(diagram)
            val mapped = spec.edges.single()
            mapped.guard shouldBe "cond"
            mapped.isObjectFlow shouldBe true
        }

        test("a non-ACTIVITY diagram is rejected") {
            val diagram = KumlDiagram(name = "d", type = DiagramType.CLASS, elements = emptyList())
            shouldThrow<IllegalArgumentException> { UmlActivityTokenFlowAdapter.toSpec(diagram) }
        }
    })
