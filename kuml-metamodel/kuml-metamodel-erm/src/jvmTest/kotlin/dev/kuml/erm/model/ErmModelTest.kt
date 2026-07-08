package dev.kuml.erm.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Core-metamodel tests for V3.4.1: construction of all element types, lookup
 * helpers, primary-key derivation, and [ErmDataType.render] labels.
 */
class ErmModelTest :
    StringSpec({

        fun sampleModel(): ErmModel {
            val customer =
                ErmEntity(
                    id = "entity_0",
                    name = "Customer",
                    attributes =
                        listOf(
                            ErmAttribute("attr_0_0", "id", ErmDataType.Uuid, primaryKey = true, nullable = false),
                            ErmAttribute("attr_0_1", "email", ErmDataType.Varchar(255), unique = true),
                        ),
                    indexes = listOf(ErmIndex("idx_0_0", "idx_email", listOf("attr_0_1"), unique = true)),
                    checks = listOf(ErmCheckConstraint("check_0_0", "email_not_empty", "email <> ''")),
                )
            val order =
                ErmEntity(
                    id = "entity_1",
                    name = "Order",
                    attributes =
                        listOf(
                            ErmAttribute("attr_1_0", "id", ErmDataType.Uuid, primaryKey = true, nullable = false),
                            ErmAttribute(
                                "attr_1_1",
                                "customer_id",
                                ErmDataType.Uuid,
                                foreignKey = ErmForeignKey(targetEntityId = "entity_0"),
                            ),
                        ),
                )
            val rel =
                ErmRelationship(
                    id = "rel_0",
                    name = "places",
                    sourceEntityId = "entity_0",
                    targetEntityId = "entity_1",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                )
            val view =
                ErmView(
                    id = "view_0",
                    name = "active_customers",
                    query = "SELECT * FROM customer WHERE active",
                    referencedEntityIds = listOf("entity_0"),
                )
            val category =
                ErmCategory(
                    id = "category_0",
                    name = "PartyType",
                    supertypeEntityId = "entity_0",
                    subtypeEntityIds = listOf("entity_1"),
                )
            return ErmModel(
                name = "Shop",
                entities = listOf(customer, order),
                relationships = listOf(rel),
                views = listOf(view),
                diagrams = listOf(ErmDiagram(name = "Overview")),
                categories = listOf(category),
            )
        }

        "entityById resolves declared entities" {
            val m = sampleModel()
            m.entityById("entity_0")!!.name shouldBe "Customer"
            m.entityById("entity_1")!!.name shouldBe "Order"
            m.entityById("nope").shouldBeNull()
        }

        "attributeById resolves across all entities" {
            val m = sampleModel()
            m.attributeById("attr_0_1")!!.name shouldBe "email"
            m.attributeById("attr_1_1")!!.name shouldBe "customer_id"
            m.attributeById("nope").shouldBeNull()
        }

        "elementById resolves entities, attributes, indexes, checks, relationships, views and categories" {
            val m = sampleModel()
            m.elementById("entity_0")!!.id shouldBe "entity_0"
            m.elementById("attr_0_0")!!.id shouldBe "attr_0_0"
            m.elementById("idx_0_0")!!.id shouldBe "idx_0_0"
            m.elementById("check_0_0")!!.id shouldBe "check_0_0"
            m.elementById("rel_0")!!.id shouldBe "rel_0"
            m.elementById("view_0")!!.id shouldBe "view_0"
            m.elementById("category_0")!!.id shouldBe "category_0"
            m.elementById("nope").shouldBeNull()
        }

        "categoryById resolves declared categories" {
            val m = sampleModel()
            m.categoryById("category_0")!!.name shouldBe "PartyType"
            m.categoryById("nope").shouldBeNull()
        }

        "categoriesOf finds categories where the entity is either supertype or subtype" {
            val m = sampleModel()
            m.categoriesOf("entity_0").map { it.id } shouldBe listOf("category_0")
            m.categoriesOf("entity_1").map { it.id } shouldBe listOf("category_0")
            m.categoriesOf("nope") shouldBe emptyList()
        }

        "relationshipsOf returns edges touching an entity on either end" {
            val m = sampleModel()
            m.relationshipsOf("entity_0").map { it.id } shouldBe listOf("rel_0")
            m.relationshipsOf("entity_1").map { it.id } shouldBe listOf("rel_0")
            m.relationshipsOf("nope") shouldBe emptyList()
        }

        "firstDiagramOrNull returns the first declared diagram" {
            sampleModel().firstDiagramOrNull()!!.name shouldBe "Overview"
            ErmModel(name = "Empty").firstDiagramOrNull().shouldBeNull()
        }

        "ErmEntity.primaryKey derives from primaryKey-flagged attributes" {
            val m = sampleModel()
            m.entityById("entity_0")!!.primaryKey.map { it.name } shouldBe listOf("id")
        }

        "ErmEntity.attributeByName looks up by declared name" {
            val m = sampleModel()
            m.entityById("entity_0")!!.attributeByName("email")!!.id shouldBe "attr_0_1"
            m.entityById("entity_0")!!.attributeByName("nope").shouldBeNull()
        }

        "Cardinality helpers reflect optionality and multiplicity" {
            Cardinality.ZERO_ONE.optional shouldBe true
            Cardinality.ZERO_ONE.many shouldBe false
            Cardinality.ONE.optional shouldBe false
            Cardinality.ONE.many shouldBe false
            Cardinality.ZERO_MANY.optional shouldBe true
            Cardinality.ZERO_MANY.many shouldBe true
            Cardinality.ONE_MANY.optional shouldBe false
            Cardinality.ONE_MANY.many shouldBe true
        }

        "ErmDataType.render produces human-readable SQL-ish labels" {
            ErmDataType.Varchar(255).render() shouldBe "VARCHAR(255)"
            ErmDataType.Decimal(10, 2).render() shouldBe "DECIMAL(10,2)"
            ErmDataType.Integer().render() shouldBe "INT"
            ErmDataType.Integer(16).render() shouldBe "SMALLINT"
            ErmDataType.Integer(64).render() shouldBe "BIGINT"
            ErmDataType.Real().render() shouldBe "DOUBLE"
            ErmDataType.Real(double = false).render() shouldBe "FLOAT"
            ErmDataType.Text.render() shouldBe "TEXT"
            ErmDataType.Boolean.render() shouldBe "BOOLEAN"
            ErmDataType.Date.render() shouldBe "DATE"
            ErmDataType.Time.render() shouldBe "TIME"
            ErmDataType.Timestamp().render() shouldBe "TIMESTAMP"
            ErmDataType.Timestamp(withTimeZone = true).render() shouldBe "TIMESTAMPTZ"
            ErmDataType.Uuid.render() shouldBe "UUID"
            ErmDataType.Blob.render() shouldBe "BLOB"
            ErmDataType.Json.render() shouldBe "JSON"
            ErmDataType.Custom("TSVECTOR").render() shouldBe "TSVECTOR"
        }
    })
