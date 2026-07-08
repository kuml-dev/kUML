package dev.kuml.erm.dsl

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * DSL-builder tests for V3.4.1: deterministic auto-ids, notation defaults,
 * default-diagram synthesis, infix relationship shortcuts, and the
 * `id()` / `foreignKey()` entity-builder convenience functions.
 */
class ErmDslTest :
    StringSpec({

        "ermModel builds entities, attributes and relationships with deterministic ids" {
            val model =
                ermModel("Shop") {
                    val customer =
                        entity("Customer") {
                            id()
                            attribute(name = "email", type = ErmDataType.Varchar(255), unique = true)
                        }
                    val order =
                        entity("Order") {
                            id()
                            foreignKey(name = "customer_id", references = customer)
                        }
                    relationship(from = customer, to = order, name = "places")
                    diagram(name = "Overview")
                }

            model.entities.map { it.id } shouldBe listOf("entity_0", "entity_1")
            model.entityById("entity_0")!!.attributes.map { it.id } shouldBe listOf("attr_0_0", "attr_0_1")
            model.entityById("entity_1")!!.attributes.map { it.id } shouldBe listOf("attr_1_0", "attr_1_1")
            model.relationships.single().id shouldBe "rel_0"
            model.relationships.single().name shouldBe "places"
        }

        "notation defaults to MARTIN and can be overridden" {
            val model =
                ermModel("N") {
                    entity("A") { id() }
                    diagram(name = "Default")
                    diagram(name = "Chen View", notation = ErmNotation.CHEN)
                }
            model.diagrams[0].notation shouldBe ErmNotation.MARTIN
            model.diagrams[1].notation shouldBe ErmNotation.CHEN
        }

        "a model with no explicit diagram gets a synthesized default diagram" {
            val model =
                ermModel("NoDiagram") {
                    entity("A") { id() }
                }
            model.diagrams.size shouldBe 1
            model.diagrams.single().name shouldBe "NoDiagram"
            model.diagrams.single().notation shouldBe ErmNotation.MARTIN
        }

        "infix oneToMany / manyToMany / oneToOne build the expected cardinalities" {
            val model =
                ermModel("Infix") {
                    val a = entity("A") { id() }
                    val b = entity("B") { id() }
                    val c = entity("C") { id() }
                    val d = entity("D") { id() }
                    a oneToMany b
                    b manyToMany c
                    c oneToOne d
                }
            val (r1, r2, r3) = model.relationships
            r1.sourceCardinality shouldBe Cardinality.ONE
            r1.targetCardinality shouldBe Cardinality.ZERO_MANY
            r2.sourceCardinality shouldBe Cardinality.ZERO_MANY
            r2.targetCardinality shouldBe Cardinality.ZERO_MANY
            r3.sourceCardinality shouldBe Cardinality.ONE
            r3.targetCardinality shouldBe Cardinality.ZERO_ONE
        }

        "id() creates a not-null UUID primary key by default" {
            val model =
                ermModel("Id") {
                    entity("A") { id() }
                }
            val pk = model.entityById("entity_0")!!.primaryKey.single()
            pk.name shouldBe "id"
            pk.type shouldBe ErmDataType.Uuid
            pk.nullable shouldBe false
            pk.primaryKey shouldBe true
        }

        "foreignKey() infers the column type from the target's primary key" {
            val model =
                ermModel("Fk") {
                    val a = entity("A") { id(type = ErmDataType.Integer()) }
                    entity("B") { foreignKey(name = "a_id", references = a) }
                }
            val fkAttr = model.entityById("entity_1")!!.attributeByName("a_id")!!
            fkAttr.type shouldBe ErmDataType.Integer()
            fkAttr.foreignKey!!.targetEntityId shouldBe "entity_0"
        }

        "foreignKey() falls back to Uuid when the target has no single-column primary key" {
            val model =
                ermModel("FkFallback") {
                    val a = entity("A") { attribute(name = "not_a_pk", type = ErmDataType.Text) }
                    entity("B") { foreignKey(name = "a_id", references = a) }
                }
            model.entityById("entity_1")!!.attributeByName("a_id")!!.type shouldBe ErmDataType.Uuid
        }

        "index() and check() land on the entity that declared them" {
            val model =
                ermModel("IdxCheck") {
                    entity("A") {
                        id()
                        attribute(name = "price", type = ErmDataType.Decimal(10, 2))
                        index("price", unique = false, name = "idx_price")
                        check(expression = "price > 0", name = "positive_price")
                    }
                }
            val entity = model.entityById("entity_0")!!
            entity.indexes.single().name shouldBe "idx_price"
            entity.indexes.single().attributeIds shouldBe listOf(entity.attributeByName("price")!!.id)
            entity.checks.single().expression shouldBe "price > 0"
        }

        "weak entities and identifying relationships are wired through the DSL" {
            val model =
                ermModel("Weak") {
                    val order = entity("Order") { id() }
                    val item =
                        entity("OrderItem", weak = true) {
                            foreignKey(name = "order_id", references = order)
                        }
                    relationship(
                        from = order,
                        to = item,
                        name = "contains",
                        kind = RelationshipKind.IDENTIFYING,
                    )
                }
            model.entityById("entity_1")!!.weak shouldBe true
            model.relationships.single().kind shouldBe RelationshipKind.IDENTIFYING
        }

        "view() declares a first-class view referencing entities" {
            val model =
                ermModel("Views") {
                    val a = entity("A") { id() }
                    view(name = "active_a", query = "SELECT * FROM a", references = listOf(a))
                }
            model.views.single().name shouldBe "active_a"
            model.views.single().referencedEntityIds shouldBe listOf("entity_0")
        }
    })
