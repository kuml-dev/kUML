package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.NodeId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class ErmRenderPlanTest :
    FunSpec({
        fun entity(
            id: String,
            name: String,
        ) = ErmEntity(
            id = id,
            name = name,
            attributes = listOf(ErmAttribute(id = "${id}_id", name = "id", type = ErmDataType.Uuid, primaryKey = true)),
        )

        val model =
            ErmModel(
                name = "Shop",
                entities = listOf(entity("customer", "Customer"), entity("order", "Order")),
                relationships =
                    listOf(
                        ErmRelationship(
                            id = "rel_places",
                            name = "places",
                            sourceEntityId = "customer",
                            targetEntityId = "order",
                            sourceCardinality = Cardinality.ONE,
                            targetCardinality = Cardinality.ZERO_MANY,
                        ),
                    ),
            )

        test("Martin plan uses entity-per-node graph and widened hints") {
            val plan = ErmRenderPlan.of(model = model, diagram = ErmDiagram(name = "D", notation = ErmNotation.MARTIN))
            plan.notation shouldBe ErmNotation.MARTIN
            plan.hints shouldBe ErmLayoutBridge.WIDENED_SPACING_HINTS
            plan.graph.nodes.map { it.id } shouldBe listOf(NodeId("customer"), NodeId("order"))
        }

        test("Chen plan expands relationships into layout nodes") {
            val plan = ErmRenderPlan.of(model = model, diagram = ErmDiagram(name = "D", notation = ErmNotation.CHEN))
            plan.notation shouldBe ErmNotation.CHEN
            (plan.graph.nodes.size > 2) shouldBe true
        }

        test("IDEF1X plan keeps the real entity ids") {
            val plan = ErmRenderPlan.of(model = model, diagram = ErmDiagram(name = "D", notation = ErmNotation.IDEF1X))
            plan.notation shouldBe ErmNotation.IDEF1X
            plan.graph.nodes.map { it.id } shouldContain NodeId("customer")
        }

        test("notation override beats the diagram notation") {
            val plan =
                ErmRenderPlan.of(
                    model = model,
                    diagram = ErmDiagram(name = "D", notation = ErmNotation.MARTIN),
                    notationOverride = ErmNotation.CHEN,
                )
            plan.notation shouldBe ErmNotation.CHEN
        }

        test("violations are passed through") {
            val broken =
                model.copy(
                    relationships =
                        model.relationships +
                            ErmRelationship(
                                id = "rel_bad",
                                name = "bad",
                                sourceEntityId = "customer",
                                targetEntityId = "missing",
                                sourceCardinality = Cardinality.ONE,
                                targetCardinality = Cardinality.ONE,
                            ),
                )
            ErmRenderPlan.of(model = broken, diagram = ErmDiagram(name = "D")).violations.isNotEmpty() shouldBe true
            ErmRenderPlan.of(model = model, diagram = ErmDiagram(name = "D")).violations.isEmpty() shouldBe true
        }
    })
